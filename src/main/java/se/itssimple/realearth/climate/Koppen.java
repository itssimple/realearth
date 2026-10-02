package se.itssimple.realearth.climate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import se.itssimple.realearth.RealEarth;

/**
 * The Köppen-Geiger climate of any place on Earth, 1991-2020, at 1 km.
 *
 * Source: Beck, H. E., et al. (2023). High-resolution (1 km) Köppen-Geiger maps for 1901-2099 based on
 * constrained CMIP6 projections. Scientific Data 10, 724. CC BY 4.0. Bundled with the mod, re-tiled
 * into 256x256 deflate tiles that are inflated on first use, so the whole planet costs 6.5 MB on disk
 * and only the regions actually visited cost memory.
 *
 * Class numbers follow the source: 1 Af, 2 Am, 3 Aw, 4 BWh, 5 BWk, 6 BSh, 7 BSk, 8 Csa, 9 Csb, 10 Csc,
 * 11 Cwa, 12 Cwb, 13 Cwc, 14 Cfa, 15 Cfb, 16 Cfc, 17 Dsa, 18 Dsb, 19 Dsc, 20 Dsd, 21 Dwa, 22 Dwb,
 * 23 Dwc, 24 Dwd, 25 Dfa, 26 Dfb, 27 Dfc, 28 Dfd, 29 ET, 30 EF; 0 is sea.
 */
public final class Koppen {
	public static final int SEA = 0;

	private static final String[] CODES = {
		"sea", "Af", "Am", "Aw", "BWh", "BWk", "BSh", "BSk", "Csa", "Csb", "Csc", "Cwa", "Cwb", "Cwc", "Cfa", "Cfb", "Cfc",
		"Dsa", "Dsb", "Dsc", "Dsd", "Dwa", "Dwb", "Dwc", "Dwd", "Dfa", "Dfb", "Dfc", "Dfd", "ET", "EF"
	};

	private static final String[] NAMES = {
		"sea", "tropical rainforest", "tropical monsoon", "tropical savanna", "hot desert", "cold desert", "hot steppe",
		"cold steppe", "hot-summer Mediterranean", "warm-summer Mediterranean", "cold-summer Mediterranean",
		"monsoon humid subtropical", "subtropical highland", "cold subtropical highland", "humid subtropical",
		"oceanic", "subpolar oceanic", "hot dry-summer continental", "warm dry-summer continental", "dry-summer subarctic",
		"very cold dry-summer subarctic", "hot monsoon continental", "warm monsoon continental", "monsoon subarctic",
		"very cold monsoon subarctic", "hot-summer humid continental", "warm-summer humid continental", "subarctic",
		"very cold subarctic", "tundra", "ice cap"
	};

	private static final double PIXELS_PER_DEGREE = 120.0;

	private static volatile Koppen instance;

	private final int width;
	private final int height;
	private final int tileSize;
	private final int tilesAcross;
	private final byte[] file;
	private final int dataStart;
	private final Map<Integer, byte[]> tiles = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
			return size() > 256;
		}
	};

	private Koppen(byte[] file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(file);
		if (b.getInt() != 0x4B475A31) { // "KGZ1"
			throw new IOException("not a KGZ1 climate file");
		}
		this.width = b.getInt();
		this.height = b.getInt();
		this.tileSize = b.getInt();
		int count = b.getInt();
		this.tilesAcross = (width + tileSize - 1) / tileSize;
		this.file = file;
		this.dataStart = 20 + count * 8;
	}

	/** The bundled map, loaded on first use. Null only if the jar is damaged. */
	public static Koppen get() {
		Koppen k = instance;
		if (k == null) {
			synchronized (Koppen.class) {
				if (instance == null) {
					try (InputStream in = Koppen.class.getResourceAsStream("/realearth/koppen.kgz")) {
						instance = new Koppen(in.readAllBytes());
					} catch (IOException | NullPointerException e) {
						RealEarth.LOGGER.error("Could not load the bundled climate map; climate falls back to latitude", e);
						return null;
					}
				}
				k = instance;
			}
		}
		return k;
	}

	/** Climate class at a place, nearest 1 km pixel. */
	public int classAt(double latitude, double longitude) {
		int x = (int) Math.floor((longitude + 180.0) * PIXELS_PER_DEGREE);
		int y = (int) Math.floor((90.0 - latitude) * PIXELS_PER_DEGREE);
		x = Math.floorMod(x, width);
		y = Math.max(0, Math.min(height - 1, y));
		byte[] tile = tile((y / tileSize) * tilesAcross + x / tileSize);
		return tile == null ? SEA : tile[(y % tileSize) * tileSize + (x % tileSize)] & 0xFF;
	}

	private byte[] tile(int index) {
		synchronized (tiles) {
			byte[] cached = tiles.get(index);
			if (cached != null) {
				return cached.length == 0 ? null : cached;
			}
		}
		ByteBuffer b = ByteBuffer.wrap(file);
		int offset = b.getInt(20 + index * 8);
		int length = b.getInt(20 + index * 8 + 4);
		byte[] out = new byte[0];
		if (length > 0) {
			Inflater inflater = new Inflater();
			try {
				inflater.setInput(file, dataStart + offset, length);
				out = new byte[tileSize * tileSize];
				int n = 0;
				while (n < out.length && !inflater.finished()) {
					n += inflater.inflate(out, n, out.length - n);
				}
			} catch (DataFormatException e) {
				RealEarth.LOGGER.warn("Damaged climate tile {}", index);
				out = new byte[0];
			} finally {
				inflater.end();
			}
		}
		synchronized (tiles) {
			tiles.put(index, out);
		}
		return out.length == 0 ? null : out;
	}

	public static String code(int climate) {
		return climate >= 0 && climate < CODES.length ? CODES[climate] : "?";
	}

	public static String name(int climate) {
		return climate >= 0 && climate < NAMES.length ? NAMES[climate] : "unknown";
	}

	public static boolean tropical(int c) {
		return c >= 1 && c <= 3;
	}

	public static boolean arid(int c) {
		return c >= 4 && c <= 7;
	}

	public static boolean desert(int c) {
		return c == 4 || c == 5;
	}

	public static boolean hotArid(int c) {
		return c == 4 || c == 6;
	}

	public static boolean temperate(int c) {
		return c >= 8 && c <= 16;
	}

	public static boolean mediterranean(int c) {
		return c >= 8 && c <= 10;
	}

	public static boolean continental(int c) {
		return c >= 17 && c <= 28;
	}

	/** Dsc, Dsd, Dwc, Dwd, Dfc, Dfd: the boreal forest belt. */
	public static boolean subarctic(int c) {
		return c == 19 || c == 20 || c == 23 || c == 24 || c == 27 || c == 28;
	}

	/** Dsd, Dwd, Dfd: winters below -38 C. */
	public static boolean veryCold(int c) {
		return c == 20 || c == 24 || c == 28;
	}

	public static boolean polar(int c) {
		return c == 29 || c == 30;
	}

	public static boolean iceCap(int c) {
		return c == 30;
	}

	/** Cfc, Csc, Cwc, and the subarctic: cool enough that conifers dominate. */
	public static boolean coolSummer(int c) {
		return c == 10 || c == 13 || c == 16 || subarctic(c);
	}
}
