package se.itssimple.realearth.osm;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Http;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;

/**
 * Where the water is, from OpenStreetMap: seas, lakes, ponds, riverbanks and docks (the "water"
 * layer), and rivers, canals, streams and ditches drawn as lines ("waterway"). Read from OpenFreeMap's
 * vector tiles at zoom 14 (OpenMapTiles schema; data (c) OpenStreetMap contributors, ODbL).
 *
 * This says only WHERE water is. How high it stands is worked out from the ground around it (see
 * ProcessedTile), because no map knows a lake's surface elevation.
 *
 * Shapes are returned in the global zoom-15 sample grid the terrain uses, so they rasterise straight
 * onto it.
 */
public final class OsmWater {
	public static final int ZOOM = 14;
	private static final Set<String> LAYERS = Set.of("water", "waterway");

	/** A water area: rings of interleaved x,y in global z15 sample coordinates. Even-odd filled. */
	public record Area(List<double[]> rings, boolean sea) {
	}

	/** A waterway centreline, and how wide to draw it. */
	public record Line(double[] points, double widthMeters) {
	}

	/** The water in one zoom-14 tile. {@code known} is false when the map has no tile or could not be fetched. */
	public record Shapes(List<Area> areas, List<Line> lines, boolean known) {
		/** The map has no such tile: water comes from elevation, and that is final. */
		public static final Shapes UNKNOWN = new Shapes(List.of(), List.of(), false);
		/** The tile could not be downloaded (compared by identity): water comes from elevation for now. */
		public static final Shapes FAILED = new Shapes(List.of(), List.of(), false);

		public boolean failed() {
			return this == FAILED;
		}
	}

	private final String tileJsonUrl;
	private final Path root;
	private final Executor downloads;
	private volatile String template;
	private final Map<Long, CompletableFuture<Shapes>> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Shapes>> eldest) {
			return size() > 96;
		}
	};

	public OsmWater(String tileJsonUrl, Path root, Executor downloads) {
		this.tileJsonUrl = tileJsonUrl;
		this.root = root;
		this.downloads = downloads;
	}

	public CompletableFuture<Shapes> tile(int x, int y) {
		int n = 1 << ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = ((long) wx << 32) | wy;
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(wx, wy), downloads));
		}
	}

	/** The current tile URL template. OpenFreeMap versions its planet, so it is read from the TileJSON. */
	private String template() throws IOException {
		String t = template;
		if (t == null) {
			synchronized (this) {
				if (template == null) {
					Path cached = root.resolve("tilejson-template.txt");
					try {
						byte[] json = Http.get(tileJsonUrl, 0, 0);
						template = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
							.getAsJsonObject().getAsJsonArray("tiles").get(0).getAsString();
						Http.writeCache(cached, template.getBytes(StandardCharsets.UTF_8));
					} catch (IOException | RuntimeException e) {
						byte[] old = Http.readCache(cached);
						if (old == null) {
							throw e instanceof IOException io ? io : new IOException(e);
						}
						template = new String(old, StandardCharsets.UTF_8);
					}
				}
				t = template;
			}
		}
		return t;
	}

	private Shapes load(int x, int y) {
		Path file = root.resolve(Integer.toString(ZOOM)).resolve(Integer.toString(x)).resolve(y + ".pbf");
		byte[] data = Http.readCache(file);
		if (data == null) {
			try {
				String url = template().replace("{z}", Integer.toString(ZOOM)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
				data = Http.get(url, 0, 0);
				Http.writeCache(file, data);
			} catch (Http.Missing e) {
				return Shapes.UNKNOWN;
			} catch (IOException e) {
				RealEarth.LOGGER.warn("Map data for tile {}/{}/{} unavailable ({}); water there comes from elevation alone", ZOOM, x, y, e.getMessage());
				forget(x, y);
				return Shapes.FAILED;
			}
		}
		try {
			return convert(VectorTile.decode(data, LAYERS), x, y);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable map tile {}; discarding: {}", file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			forget(x, y);
			return Shapes.FAILED;
		}
	}

	private void forget(int x, int y) {
		synchronized (cache) {
			cache.remove(((long) x << 32) | y);
		}
	}

	/** Makes sure one tile is on disk, without decoding it: for downloading an area in advance. Blocking. */
	public TileStore.Prefetch prefetch(int x, int y) {
		int n = 1 << ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		Path file = root.resolve(Integer.toString(ZOOM)).resolve(Integer.toString(wx)).resolve(wy + ".pbf");
		if (Files.exists(file)) {
			return TileStore.Prefetch.CACHED;
		}
		try {
			String url = template().replace("{z}", Integer.toString(ZOOM)).replace("{x}", Integer.toString(wx)).replace("{y}", Integer.toString(wy));
			Http.writeCache(file, Http.get(url, 0, 0));
			return TileStore.Prefetch.DOWNLOADED;
		} catch (Http.Missing e) {
			return TileStore.Prefetch.CACHED;
		} catch (IOException e) {
			return TileStore.Prefetch.FAILED;
		}
	}

	private static Shapes convert(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<Area> areas = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			if (f.flag("intermittent") || "tunnel".equals(f.string("brunnel"))) {
				continue;
			}
			String cls = f.string("class");
			double scale = samplesPerTile / f.extent();
			List<double[]> parts = new ArrayList<>();
			for (int[] p : f.parts()) {
				double[] d = new double[p.length];
				for (int i = 0; i < p.length; i += 2) {
					d[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
					d[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
				}
				parts.add(d);
			}
			if (f.layer().equals("water") && f.type() == VectorTile.POLYGON) {
				if ("swimming_pool".equals(cls)) {
					continue;
				}
				areas.add(new Area(parts, "ocean".equals(cls)));
			} else if (f.layer().equals("waterway") && f.type() == VectorTile.LINE) {
				double width = switch (cls == null ? "" : cls) {
					case "river" -> 12.0;
					case "canal" -> 8.0;
					case "stream" -> 3.0;
					case "drain" -> 2.0;
					case "ditch" -> 1.5;
					default -> 0.0;
				};
				if (width > 0) {
					for (double[] part : parts) {
						lines.add(new Line(part, width));
					}
				}
			}
		}
		return new Shapes(areas, lines, true);
	}

	/** The zoom-14 tiles covering a range of global z15 sample coordinates. */
	public static List<int[]> tilesCovering(long minX, long minY, long maxX, long maxY) {
		int per = Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - ZOOM));
		List<int[]> out = new ArrayList<>();
		Map<Long, Boolean> seen = new HashMap<>();
		for (long ty = Math.floorDiv(minY, per); ty <= Math.floorDiv(maxY, per); ty++) {
			for (long tx = Math.floorDiv(minX, per); tx <= Math.floorDiv(maxX, per); tx++) {
				if (seen.putIfAbsent((tx << 32) | (ty & 0xFFFFFFFFL), Boolean.TRUE) == null) {
					out.add(new int[] {(int) tx, (int) ty});
				}
			}
		}
		return out;
	}
}
