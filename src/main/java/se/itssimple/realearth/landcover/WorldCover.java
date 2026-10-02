package se.itssimple.realearth.landcover;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Http;
import se.itssimple.realearth.data.TileStore;

/**
 * What covers the ground anywhere on land, at 10 m: ESA WorldCover 2021 (v200), CC BY 4.0.
 *
 * The data is published as one cloud-optimised GeoTIFF per 3x3 degrees: 36,000 pixels square in
 * deflate-compressed 1024x1024 tiles, with overviews at 20, 40 ... 640 m. Only the tiles actually
 * needed are fetched, with HTTP range requests, and kept on disk compressed. Squares with no land
 * have no file at all.
 */
public final class WorldCover {
	public static final int NONE = 0;
	public static final int TREES = 10;
	public static final int SHRUBS = 20;
	public static final int GRASS = 30;
	public static final int CROPS = 40;
	public static final int BUILT = 50;
	public static final int BARE = 60;
	public static final int SNOW_ICE = 70;
	public static final int WATER = 80;
	public static final int WETLAND = 90;
	public static final int MANGROVES = 95;
	public static final int MOSS = 100;

	private static final int FILE_DEGREES = 3;
	private static final int PIXELS_PER_DEGREE = 12000;
	private static final int HEADER_BYTES = 65536;

	private final String urlTemplate;
	private final Path root;
	private final Executor downloads;
	private final Map<String, CompletableFuture<Header>> headers = new HashMap<>();
	private final Map<String, CompletableFuture<byte[]>> tiles = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<byte[]>> eldest) {
			return size() > 48;
		}
	};

	private record Level(int width, int height, int tileWidth, int tileHeight, long[] offsets, long[] counts) {
		int tilesAcross() {
			return (width + tileWidth - 1) / tileWidth;
		}
	}

	private record Header(Level[] levels) {
	}

	private static final Header ABSENT = new Header(new Level[0]);
	/** Stand-in for a header that could not be downloaded; compared by identity. */
	private static final Header FAILED = new Header(new Level[0]);
	/** Stand-in for a tile that could not be downloaded; compared by identity. */
	private static final byte[] FAILED_TILE = new byte[0];

	public WorldCover(String urlTemplate, Path root, Executor downloads) {
		this.urlTemplate = urlTemplate;
		this.root = root;
		this.downloads = downloads;
	}

	/** Name of the 3-degree square holding a point, as ESA names its files: N57E018. */
	static String squareName(double latitude, double longitude) {
		int lat = (int) Math.floor(latitude / FILE_DEGREES) * FILE_DEGREES;
		int lon = (int) Math.floor(longitude / FILE_DEGREES) * FILE_DEGREES;
		return String.format(Locale.ROOT, "%s%02d%s%03d", lat >= 0 ? "N" : "S", Math.abs(lat), lon >= 0 ? "E" : "W", Math.abs(lon));
	}

	private String url(String square) {
		return urlTemplate.replace("{tile}", square);
	}

	private CompletableFuture<Header> header(String square) {
		synchronized (headers) {
			return headers.computeIfAbsent(square, s -> CompletableFuture.supplyAsync(() -> loadHeader(s), downloads));
		}
	}

	private Header loadHeader(String square) {
		Path dir = root.resolve(square);
		if (Files.exists(dir.resolve("missing"))) {
			return ABSENT;
		}
		byte[] bytes = Http.readCache(dir.resolve("header.bin"));
		if (bytes == null) {
			try {
				bytes = Http.get(url(square), 0, HEADER_BYTES);
				Http.writeCache(dir.resolve("header.bin"), bytes);
			} catch (Http.Missing m) {
				Http.writeCache(dir.resolve("missing"), new byte[0]);
				return ABSENT;
			} catch (IOException e) {
				RealEarth.LOGGER.warn("Land cover for {} unavailable: {}", square, e.getMessage());
				synchronized (headers) {
					headers.remove(square); // try again later
				}
				return FAILED;
			}
		}
		try {
			return parse(bytes);
		} catch (RuntimeException | IOException e) {
			RealEarth.LOGGER.warn("Unreadable land cover header for {}; discarding: {}", square, e.toString());
			try {
				Files.deleteIfExists(dir.resolve("header.bin"));
			} catch (IOException ignored) {
			}
			synchronized (headers) {
				headers.remove(square);
			}
			return FAILED;
		}
	}

	/** Reads the chain of TIFF directories: the full image, then each overview. Classic TIFF only. */
	private static Header parse(byte[] bytes) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(bytes);
		b.order(bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		if ((b.getShort(2) & 0xFFFF) != 42) {
			throw new IOException("not a classic TIFF");
		}
		List<Level> levels = new ArrayList<>();
		long ifd = b.getInt(4) & 0xFFFFFFFFL;
		while (ifd != 0 && ifd + 2 <= bytes.length) {
			int n = b.getShort((int) ifd) & 0xFFFF;
			int width = 0;
			int height = 0;
			int tileW = 0;
			int tileH = 0;
			long[] offsets = null;
			long[] counts = null;
			for (int i = 0; i < n; i++) {
				int e = (int) ifd + 2 + i * 12;
				int tag = b.getShort(e) & 0xFFFF;
				int type = b.getShort(e + 2) & 0xFFFF;
				int count = b.getInt(e + 4);
				switch (tag) {
					case 256 -> width = value(b, e, type);
					case 257 -> height = value(b, e, type);
					case 322 -> tileW = value(b, e, type);
					case 323 -> tileH = value(b, e, type);
					case 324 -> offsets = array(b, e, type, count);
					case 325 -> counts = array(b, e, type, count);
					case 259 -> {
						if (value(b, e, type) != 8) {
							throw new IOException("expected deflate compression");
						}
					}
					default -> {
					}
				}
			}
			levels.add(new Level(width, height, tileW, tileH, offsets, counts));
			ifd = b.getInt((int) ifd + 2 + n * 12) & 0xFFFFFFFFL;
		}
		return new Header(levels.toArray(Level[]::new));
	}

	private static int value(ByteBuffer b, int entry, int type) {
		return type == 3 ? b.getShort(entry + 8) & 0xFFFF : b.getInt(entry + 8);
	}

	private static long[] array(ByteBuffer b, int entry, int type, int count) {
		long[] out = new long[count];
		int size = type == 3 ? 2 : 4;
		int at = count * size <= 4 ? entry + 8 : b.getInt(entry + 8);
		for (int i = 0; i < count; i++) {
			out[i] = size == 2 ? b.getShort(at + i * 2) & 0xFFFF : b.getInt(at + i * 4) & 0xFFFFFFFFL;
		}
		return out;
	}

	private CompletableFuture<byte[]> tile(String square, int level, int index) {
		String key = square + "/" + level + "/" + index;
		synchronized (tiles) {
			return tiles.computeIfAbsent(key, k -> header(square).thenApplyAsync(h -> loadTile(square, h, level, index), downloads));
		}
	}

	private byte[] loadTile(String square, Header header, int level, int index) {
		if (header == FAILED) {
			synchronized (tiles) {
				tiles.remove(square + "/" + level + "/" + index);
			}
			return FAILED_TILE;
		}
		if (level >= header.levels.length) {
			return null;
		}
		Level l = header.levels[level];
		if (index >= l.offsets.length || l.counts[index] == 0) {
			return null;
		}
		Path file = root.resolve(square).resolve("L" + level).resolve(index + ".bin");
		byte[] compressed = Http.readCache(file);
		if (compressed == null) {
			try {
				compressed = Http.get(url(square), l.offsets[index], l.counts[index]);
				Http.writeCache(file, compressed);
			} catch (IOException e) {
				RealEarth.LOGGER.warn("Land cover tile {} unavailable: {}", file, e.getMessage());
				synchronized (tiles) {
					tiles.remove(square + "/" + level + "/" + index);
				}
				return FAILED_TILE;
			}
		}
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(compressed);
			byte[] out = new byte[l.tileWidth * l.tileHeight];
			int n = 0;
			while (n < out.length && !inflater.finished()) {
				int got = inflater.inflate(out, n, out.length - n);
				if (got == 0 && inflater.needsInput()) {
					break;
				}
				n += got;
			}
			return out;
		} catch (DataFormatException e) {
			RealEarth.LOGGER.warn("Damaged land cover tile {}; discarding", file);
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return null;
		} finally {
			inflater.end();
		}
	}

	/** Land cover for a set of points, all fetched before the answer is used. */
	public interface Lookup {
		int classAt(double latitude, double longitude);

		/** True when some of the data could not be downloaded and those points answer NONE for now. */
		default boolean degraded() {
			return false;
		}
	}

	/**
	 * Fetches what is needed to answer for these points at the given level (0 = 10 m, 1 = 20 m, ...
	 * 6 = 640 m) and completes with a lookup that answers for any point inside the same tiles.
	 */
	public CompletableFuture<Lookup> prepare(double[] latitudes, double[] longitudes, int level) {
		Map<String, CompletableFuture<byte[]>> needed = new HashMap<>();
		Map<String, CompletableFuture<Header>> squares = new HashMap<>();
		for (int i = 0; i < latitudes.length; i++) {
			String square = squareName(latitudes[i], longitudes[i]);
			squares.computeIfAbsent(square, this::header);
		}
		return CompletableFuture.allOf(squares.values().toArray(CompletableFuture[]::new)).thenCompose(v -> {
			boolean failedHeader = false;
			for (int i = 0; i < latitudes.length; i++) {
				String square = squareName(latitudes[i], longitudes[i]);
				Header h = squares.get(square).join();
				failedHeader |= h == FAILED;
				if (level >= h.levels.length) {
					continue;
				}
				int index = tileIndex(h.levels[level], latitudes[i], longitudes[i], level);
				needed.computeIfAbsent(square + "/" + index, k -> tile(square, level, index));
			}
			boolean anyHeaderFailed = failedHeader;
			return CompletableFuture.allOf(needed.values().toArray(CompletableFuture[]::new)).thenApply(w -> {
				boolean failed = anyHeaderFailed || needed.values().stream().anyMatch(f -> f.join() == FAILED_TILE);
				return new Lookup() {
					@Override
					public int classAt(double lat, double lon) {
						String square = squareName(lat, lon);
						CompletableFuture<Header> hf = squares.get(square);
						if (hf == null) {
							return NONE;
						}
						Header h = hf.join();
						if (level >= h.levels.length) {
							return NONE;
						}
						Level l = h.levels[level];
						int index = tileIndex(l, lat, lon, level);
						CompletableFuture<byte[]> tf = needed.get(square + "/" + index);
						byte[] t = tf == null ? null : tf.join();
						if (t == null || t == FAILED_TILE) {
							return NONE;
						}
						int[] p = pixel(l, lat, lon, level);
						return t[(p[1] % l.tileHeight) * l.tileWidth + (p[0] % l.tileWidth)] & 0xFF;
					}

					@Override
					public boolean degraded() {
						return failed;
					}
				};
			});
		});
	}

	private final java.util.Set<Path> prefetching = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Makes sure the tile holding a point at a level is on disk, without decoding it: for downloading
	 * an area in advance. Blocking.
	 */
	public TileStore.Prefetch prefetch(double lat, double lon, int level) {
		String square = squareName(lat, lon);
		Header h = header(square).join();
		if (h == FAILED) {
			return TileStore.Prefetch.FAILED;
		}
		if (level >= h.levels.length) {
			return TileStore.Prefetch.CACHED;
		}
		Level l = h.levels[level];
		int index = tileIndex(l, lat, lon, level);
		if (index >= l.offsets.length || l.counts[index] == 0) {
			return TileStore.Prefetch.CACHED;
		}
		Path file = root.resolve(square).resolve("L" + level).resolve(index + ".bin");
		if (Files.exists(file) || !prefetching.add(file)) {
			return TileStore.Prefetch.CACHED;
		}
		try {
			Http.writeCache(file, Http.get(url(square), l.offsets[index], l.counts[index]));
			return TileStore.Prefetch.DOWNLOADED;
		} catch (IOException e) {
			return TileStore.Prefetch.FAILED;
		} finally {
			prefetching.remove(file);
		}
	}

	private static int[] pixel(Level l, double lat, double lon, int level) {
		double latSW = Math.floor(lat / FILE_DEGREES) * FILE_DEGREES;
		double lonSW = Math.floor(lon / FILE_DEGREES) * FILE_DEGREES;
		double scale = PIXELS_PER_DEGREE / (double) (1 << level);
		int x = (int) Math.floor((lon - lonSW) * scale);
		int y = (int) Math.floor((latSW + FILE_DEGREES - lat) * scale);
		return new int[] {Math.max(0, Math.min(l.width - 1, x)), Math.max(0, Math.min(l.height - 1, y))};
	}

	private static int tileIndex(Level l, double lat, double lon, int level) {
		int[] p = pixel(l, lat, lon, level);
		return (p[1] / l.tileHeight) * l.tilesAcross() + p[0] / l.tileWidth;
	}

	public static String name(int cover) {
		return switch (cover) {
			case TREES -> "tree cover";
			case SHRUBS -> "shrubland";
			case GRASS -> "grassland";
			case CROPS -> "cropland";
			case BUILT -> "built-up";
			case BARE -> "bare / sparse vegetation";
			case SNOW_ICE -> "snow and ice";
			case WATER -> "permanent water";
			case WETLAND -> "herbaceous wetland";
			case MANGROVES -> "mangroves";
			case MOSS -> "moss and lichen";
			default -> "no data";
		};
	}
}
