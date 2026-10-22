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
	private static final Set<String> LAYERS = Set.of("water", "waterway", "transportation", "building", "poi", "landuse", "place",
		"transportation_name");
	private static final Set<String> PLACE_LAYER = Set.of("place");

	/** A water area: rings of interleaved x,y in global z15 sample coordinates. Even-odd filled. */
	public record Area(List<double[]> rings, boolean sea) {
	}

	/** A waterway centreline, and how wide to draw it. */
	public record Line(double[] points, double widthMeters) {
	}

	/**
	 * A road, track, path or railway centreline, in global z15 sample coordinates, with its bounding
	 * box. Tunnels are left out: they run inside the ground.
	 */
	public record Way(double[] points, double widthMeters, byte kind, boolean bridge, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A building footprint: rings of interleaved x,y in global z15 sample coordinates (even-odd), its
	 * height in metres (NaN when the map doesn't say), and its bounding box. Its index in the tile is
	 * stable, so tile and index identify it.
	 */
	public record Building(List<double[]> rings, double heightMeters, int index, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * A point of interest -- a shop, café, church, school -- in global z15 sample coordinates, with its
	 * OpenMapTiles class and subclass, name (or null), and floor (0 for the ground floor or unknown).
	 */
	public record Poi(double x, double y, String cls, String subclass, String name, int level) {
	}

	/**
	 * A named place: a city, town or village, or a district of one (suburb, quarter, neighbourhood), in
	 * global z15 sample coordinates. Lower rank is more important. {@code capital} is the admin level the
	 * place is the capital of (2 a country's), or 0.
	 */
	public record Place(double x, double y, String cls, String name, int rank, int capital) {
	}

	/** A named street or path: its centreline in global z15 sample coordinates, its name, and its class. */
	public record Street(double[] points, String name, String cls, double minX, double minY, double maxX, double maxY) {
	}

	/** What land is used for (residential, commercial, industrial, school...): rings like an Area's, and its box. */
	public record Land(List<double[]> rings, String cls, double minX, double minY, double maxX, double maxY) {
	}

	/**
	 * The water, ways, buildings, points of interest and land use in one zoom-14 tile. {@code known} is
	 * false when the map has no tile or could not be fetched.
	 */
	public record Shapes(List<Area> areas, List<Line> lines, boolean known, List<Way> ways, List<Building> buildings, List<Poi> pois,
		List<Land> landuse, List<Place> places, List<Street> streets) {
		/** The map has no such tile: water comes from elevation, and that is final. */
		public static final Shapes UNKNOWN = new Shapes(List.of(), List.of(), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		/** The tile could not be downloaded (compared by identity): water comes from elevation for now. */
		public static final Shapes FAILED = new Shapes(List.of(), List.of(), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

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
		List<Way> ways = new ArrayList<>();
		List<Building> buildings = new ArrayList<>();
		List<Poi> pois = new ArrayList<>();
		List<Land> landuse = new ArrayList<>();
		List<Place> places = new ArrayList<>();
		List<Street> streets = new ArrayList<>();
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
			if (f.layer().equals("building") && f.type() == VectorTile.POLYGON) {
				// Parts that float (render_min_height above 0) are upper storeys of something else.
				double base = f.number("render_min_height");
				if (f.flag("hide_3d") || (!Double.isNaN(base) && base > 0.5)) {
					continue;
				}
				// At this zoom the map merges all of a tile's buildings of the same height into one
				// feature: split it back into buildings, each an outer ring with the holes after it
				// (outer rings wind one way, holes the other).
				List<double[]> current = null;
				for (double[] ring : parts) {
					if (current == null || signedArea(ring) > 0) {
						if (current != null) {
							addBuilding(buildings, current, f.number("render_height"));
						}
						current = new ArrayList<>();
					}
					current.add(ring);
				}
				if (current != null) {
					addBuilding(buildings, current, f.number("render_height"));
				}
				continue;
			}
			if (f.layer().equals("place") && f.type() == VectorTile.POINT) {
				addPlaces(places, f, parts);
				continue;
			}
			if (f.layer().equals("transportation_name") && f.type() == VectorTile.LINE && f.string("name") != null) {
				for (double[] part : parts) {
					if (part.length < 4) {
						continue;
					}
					double minX = Double.MAX_VALUE;
					double minY = Double.MAX_VALUE;
					double maxX = -Double.MAX_VALUE;
					double maxY = -Double.MAX_VALUE;
					for (int i = 0; i < part.length; i += 2) {
						minX = Math.min(minX, part[i]);
						maxX = Math.max(maxX, part[i]);
						minY = Math.min(minY, part[i + 1]);
						maxY = Math.max(maxY, part[i + 1]);
					}
					streets.add(new Street(part, f.string("name"), cls, minX, minY, maxX, maxY));
				}
				continue;
			}
			if (f.layer().equals("poi") && f.type() == VectorTile.POINT) {
				double level = f.number("level");
				for (double[] p : parts) {
					pois.add(new Poi(p[0], p[1], cls, f.string("subclass"), f.string("name"), Double.isNaN(level) ? 0 : (int) level));
				}
				continue;
			}
			if (f.layer().equals("landuse") && f.type() == VectorTile.POLYGON && cls != null) {
				double minX = Double.MAX_VALUE;
				double minY = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE;
				double maxY = -Double.MAX_VALUE;
				for (double[] ring : parts) {
					for (int i = 0; i < ring.length; i += 2) {
						minX = Math.min(minX, ring[i]);
						maxX = Math.max(maxX, ring[i]);
						minY = Math.min(minY, ring[i + 1]);
						maxY = Math.max(maxY, ring[i + 1]);
					}
				}
				landuse.add(new Land(parts, cls, minX, minY, maxX, maxY));
				continue;
			}
			if (f.layer().equals("water") && f.type() == VectorTile.POLYGON) {
				if ("swimming_pool".equals(cls)) {
					continue;
				}
				areas.add(new Area(parts, "ocean".equals(cls)));
			} else if (f.layer().equals("transportation") && f.type() == VectorTile.LINE) {
				byte kind = Ways.kind(cls, f.string("subclass"));
				if (kind != Ways.NONE) {
					boolean bridge = "bridge".equals(f.string("brunnel"));
					double width = Ways.widthMeters(kind, cls);
					for (double[] part : parts) {
						if (part.length >= 4) {
							double minX = Double.MAX_VALUE;
							double minY = Double.MAX_VALUE;
							double maxX = -Double.MAX_VALUE;
							double maxY = -Double.MAX_VALUE;
							for (int i = 0; i < part.length; i += 2) {
								minX = Math.min(minX, part[i]);
								maxX = Math.max(maxX, part[i]);
								minY = Math.min(minY, part[i + 1]);
								maxY = Math.max(maxY, part[i + 1]);
							}
							ways.add(new Way(part, width, kind, bridge, minX, minY, maxX, maxY));
						}
					}
				}
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
		return new Shapes(areas, lines, true, ways, buildings, pois, landuse, places, streets);
	}

	/** Cities, towns, villages and their districts; not islands, countries, states or lone farms. */
	private static void addPlaces(List<Place> places, VectorTile.Feature f, List<double[]> parts) {
		String cls = f.string("class");
		String name = f.string("name");
		if (name == null || cls == null) {
			return;
		}
		switch (cls) {
			case "city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood" -> {
				double rank = f.number("rank");
				double capital = f.number("capital");
				for (double[] p : parts) {
					places.add(new Place(p[0], p[1], cls, name, Double.isNaN(rank) ? 99 : (int) rank, Double.isNaN(capital) ? 0 : (int) capital));
				}
			}
			default -> {
			}
		}
	}

	private final Map<Long, CompletableFuture<List<Place>>> placeCache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<List<Place>>> eldest) {
			return size() > 64;
		}
	};

	/**
	 * The named places in a tile at any zoom up to 14. A low zoom tile covers a wide area and keeps only
	 * its bigger places, cities and towns: that is how far a town's name reaches. At 14, the tile's own.
	 */
	public CompletableFuture<List<Place>> places(int zoom, int x, int y) {
		if (zoom >= ZOOM) {
			return tile(x, y).thenApply(Shapes::places);
		}
		int n = 1 << zoom;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = (long) zoom << 56 | (long) wx << 28 | wy;
		synchronized (placeCache) {
			return placeCache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> loadPlaces(zoom, wx, wy), downloads));
		}
	}

	private List<Place> loadPlaces(int zoom, int x, int y) {
		Path file = root.resolve(Integer.toString(zoom)).resolve(Integer.toString(x)).resolve(y + ".pbf");
		try {
			byte[] data = Http.readCache(file);
			if (data == null) {
				String url = template().replace("{z}", Integer.toString(zoom)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
				data = Http.get(url, 0, 0);
				Http.writeCache(file, data);
			}
			double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - zoom));
			List<Place> places = new ArrayList<>();
			for (VectorTile.Feature f : VectorTile.decode(data, PLACE_LAYER)) {
				if (f.type() != VectorTile.POINT) {
					continue;
				}
				double scale = samplesPerTile / f.extent();
				List<double[]> parts = new ArrayList<>();
				for (int[] p : f.parts()) {
					parts.add(new double[] {x * samplesPerTile + p[0] * scale - 0.5, y * samplesPerTile + p[1] * scale - 0.5});
				}
				addPlaces(places, f, parts);
			}
			return places;
		} catch (Http.Missing e) {
			return List.of();
		} catch (IOException | RuntimeException e) {
			synchronized (placeCache) {
				placeCache.remove((long) zoom << 56 | (long) x << 28 | y);
			}
			return List.of();
		}
	}

	private static void addBuilding(List<Building> buildings, List<double[]> rings, double height) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] ring : rings) {
			for (int i = 0; i < ring.length; i += 2) {
				minX = Math.min(minX, ring[i]);
				maxX = Math.max(maxX, ring[i]);
				minY = Math.min(minY, ring[i + 1]);
				maxY = Math.max(maxY, ring[i + 1]);
			}
		}
		buildings.add(new Building(rings, height, buildings.size(), minX, minY, maxX, maxY));
	}

	/** Surveyor's formula, in tile orientation (y down): positive for an outer ring, as vector tiles define it. */
	private static double signedArea(double[] ring) {
		double a = 0;
		int n = ring.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			a += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1];
		}
		return a / 2;
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
