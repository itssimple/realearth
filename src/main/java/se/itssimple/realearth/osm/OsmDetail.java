package se.itssimple.realearth.osm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Http;
import se.itssimple.realearth.data.PmTiles;
import se.itssimple.realearth.geo.Mercator;

/**
 * What OpenStreetMap says of a map tile's buildings beyond their outlines -- roof shapes, roof heights
 * and colours, facade colours, and the parts a landmark is mapped in (a tower, its spire, a dome), each
 * with its own height and roof -- which the OpenFreeMap tiles leave out. Read from the Overture Maps
 * buildings tiles (OpenStreetMap's buildings and building parts with their 3D tags, as vector tiles in
 * one PMTiles archive per monthly release), a tile at a time, and kept on disk for good.
 */
public final class OsmDetail {
	/** A building or a building part with 3D detail, in global z15 sample coordinates. */
	public record Shape(List<double[]> rings, boolean part, Roof roof, double roofMeters, int roofColour, int colour, double heightMeters,
		double minMeters, double minX, double minY, double maxX, double maxY) {
	}

	/** Roof shapes, as OSM's roof:shape values are grouped here. */
	public enum Roof { NONE, FLAT, GABLED, HIPPED, PYRAMIDAL, SKILLION, DOME, ONION, CONE, ROUND, MANSARD }

	/** A tile's detail. */
	public record Tile(List<Shape> shapes, boolean failed) {
		public static final Tile EMPTY = new Tile(List.of(), false);
		public static final Tile FAILED = new Tile(List.of(), true);
	}

	private static final Set<String> LAYERS = Set.of("building", "building_part");

	private final String template;
	private final String release;
	private final String catalog;
	private final Path root;
	private final ExecutorService downloads;
	private volatile PmTiles archive;
	private final Map<Long, CompletableFuture<Tile>> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Tile>> eldest) {
			return size() > 64;
		}
	};

	/**
	 * @param template the archive's URL with {release} for the release's name
	 * @param release a release ("2026-10-21.0"), or "latest" for the newest in {@code catalog}
	 * @param catalog Overture's release catalogue (STAC), listing the releases
	 */
	OsmDetail(String template, String release, String catalog, Path root) {
		this.template = template;
		this.release = release;
		this.catalog = catalog;
		this.root = root;
		AtomicInteger n = new AtomicInteger();
		this.downloads = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "RealEarth-Buildings-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		se.itssimple.realearth.data.Net.pool("building detail", downloads);
	}

	/** The detail of a z14 map tile: from disk, else read from the archive and kept. */
	public CompletableFuture<Tile> tile(int x, int y) {
		int n = 1 << OsmWater.ZOOM;
		int wx = Math.floorMod(x, n);
		int wy = Math.max(0, Math.min(n - 1, y));
		long key = ((long) wx << 32) | wy;
		synchronized (cache) {
			return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(wx, wy), downloads));
		}
	}

	private Path file(int x, int y) {
		return root.resolve(Integer.toString(OsmWater.ZOOM)).resolve(Integer.toString(x)).resolve(y + ".mvt");
	}

	private Tile load(int x, int y) {
		Path file = file(x, y);
		byte[] data = Http.readCache(file);
		if (data == null) {
			try {
				data = archive().tile(OsmWater.ZOOM, x, y);
			} catch (Http.Missing e) {
				// The release is gone from the bucket (they are kept for a while): look for the newest.
				archive = null;
				return failed(x, y, e);
			} catch (IOException | RuntimeException e) {
				return failed(x, y, e);
			}
			// No tile: no buildings there. Kept as an empty file, so it is not asked for again.
			Http.writeCache(file, data == null ? new byte[0] : data);
		}
		if (data.length == 0) {
			return Tile.EMPTY;
		}
		try {
			return parse(VectorTile.decode(data, LAYERS), x, y);
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Unreadable building detail {}; discarding: {}", file, e.toString());
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
			return failed(x, y, e);
		}
	}

	private Tile failed(int x, int y, Exception e) {
		RealEarth.LOGGER.warn("Building detail for map tile {}/{}/{} unavailable ({}); its buildings are built from their outlines",
			OsmWater.ZOOM, x, y, e.getMessage());
		synchronized (cache) {
			cache.remove(((long) x << 32) | y); // asked again another time
		}
		return Tile.FAILED;
	}

	/** The archive of the release in use: the configured one, or the newest in the catalogue (remembered on disk). */
	private synchronized PmTiles archive() throws IOException {
		PmTiles a = archive;
		if (a != null) {
			return a;
		}
		String name = release;
		if (name == null || name.isBlank() || name.equalsIgnoreCase("latest")) {
			Path known = root.resolve("release.txt");
			try {
				name = newest(new String(Http.get(catalog, 0, 0), StandardCharsets.UTF_8));
				Http.writeCache(known, name.getBytes(StandardCharsets.UTF_8));
			} catch (IOException | RuntimeException e) {
				byte[] old = Http.readCache(known);
				if (old == null) {
					throw e instanceof IOException io ? io : new IOException(e);
				}
				name = new String(old, StandardCharsets.UTF_8).strip();
			}
		}
		a = new PmTiles(template.replace("{release}", name));
		archive = a;
		RealEarth.LOGGER.info("Building detail from Overture Maps release {}", name);
		return a;
	}

	/** The newest release a STAC catalogue links to: its child catalogues are named by release, which sort by date. */
	static String newest(String catalogJson) throws IOException {
		String best = null;
		for (JsonElement link : JsonParser.parseString(catalogJson).getAsJsonObject().getAsJsonArray("links")) {
			String href = link.getAsJsonObject().get("href").getAsString();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d{4}-\\d{2}-\\d{2}\\.\\d+)/").matcher(href);
			if (m.find() && (best == null || m.group(1).compareTo(best) > 0)) {
				best = m.group(1);
			}
		}
		if (best == null) {
			throw new IOException("no release in the catalogue");
		}
		return best;
	}

	/** A tile's buildings and parts: each an outer ring and its holes, with what is known of it. */
	static Tile parse(List<VectorTile.Feature> features, int tileX, int tileY) {
		double samplesPerTile = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		List<Shape> shapes = new ArrayList<>();
		for (VectorTile.Feature f : features) {
			if (f.type() != VectorTile.POLYGON || f.flag("is_underground")) {
				continue;
			}
			double scale = samplesPerTile / f.extent();
			boolean part = f.layer().equals("building_part");
			double roofMeters = f.number("roof_height");
			double height = f.number("height");
			if (Double.isNaN(height)) {
				double floors = f.number("num_floors");
				height = Double.isNaN(floors) ? Double.NaN : floors * 3 + (Double.isNaN(roofMeters) ? 0 : roofMeters);
			}
			double min = f.number("min_height");
			if (Double.isNaN(min)) {
				double floor = f.number("min_floor");
				min = Double.isNaN(floor) ? 0 : floor * 3;
			}
			int roofColour = Colours.parse(f.string("roof_color"));
			if (roofColour == Colours.NONE) {
				roofColour = roofMaterial(f.string("roof_material"));
			}
			Roof roof = roof(f.string("roof_shape"));
			int colour = Colours.parse(f.string("facade_color"));
			List<double[]> current = null;
			for (int[] p : f.parts()) {
				double[] ring = new double[p.length];
				for (int i = 0; i < p.length; i += 2) {
					ring[i] = tileX * samplesPerTile + p[i] * scale - 0.5;
					ring[i + 1] = tileY * samplesPerTile + p[i + 1] * scale - 0.5;
				}
				if (current == null || signedArea(ring) > 0) {
					if (current != null) {
						shapes.add(shape(current, part, roof, roofMeters, roofColour, colour, height, min));
					}
					current = new ArrayList<>();
				}
				current.add(ring);
			}
			if (current != null) {
				shapes.add(shape(current, part, roof, roofMeters, roofColour, colour, height, min));
			}
		}
		return new Tile(List.copyOf(shapes), false);
	}

	private static Shape shape(List<double[]> rings, boolean part, Roof roof, double roofMeters, int roofColour, int colour, double height, double min) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 0; i < r.length; i += 2) {
				minX = Math.min(minX, r[i]);
				maxX = Math.max(maxX, r[i]);
				minY = Math.min(minY, r[i + 1]);
				maxY = Math.max(maxY, r[i + 1]);
			}
		}
		return new Shape(rings, part, roof, roofMeters, roofColour, colour, height, min, minX, minY, maxX, maxY);
	}

	/** Surveyor's formula, in tile orientation (y down): positive for an outer ring. */
	private static double signedArea(double[] ring) {
		double a = 0;
		int n = ring.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			a += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1];
		}
		return a / 2;
	}

	static Roof roof(String shape) {
		if (shape == null) {
			return Roof.NONE;
		}
		return switch (shape.strip().toLowerCase(Locale.ROOT)) {
			case "flat" -> Roof.FLAT;
			case "gabled", "saltbox", "double_saltbox", "quadruple_saltbox", "gambrel", "side_hipped" -> Roof.GABLED;
			case "hipped", "half_hipped", "half-hipped", "many", "crosspitched" -> Roof.HIPPED;
			case "pyramidal", "pyramid" -> Roof.PYRAMIDAL;
			case "skillion", "lean_to" -> Roof.SKILLION;
			case "dome" -> Roof.DOME;
			case "onion" -> Roof.ONION;
			case "cone", "conical", "spherical" -> Roof.CONE;
			case "round", "barrel" -> Roof.ROUND;
			case "mansard" -> Roof.MANSARD;
			default -> Roof.NONE;
		};
	}

	/** A roof's colour from what it is made of, where no colour is given. */
	private static int roofMaterial(String material) {
		if (material == null) {
			return Colours.NONE;
		}
		return switch (material.strip().toLowerCase(Locale.ROOT)) {
			case "roof_tiles", "tile", "tiles", "clay" -> 0xa0442b;
			case "slate", "eternit", "tar_paper" -> 0x4a4e55;
			case "copper" -> 0x5fa58a;
			case "metal", "tin", "zinc", "steel" -> 0x8a8f96;
			case "glass" -> Colours.GLASS;
			case "grass", "plants" -> 0x4f7a32;
			case "thatch" -> 0xb59a5a;
			case "wood" -> 0x6b4a2b;
			case "concrete", "gravel", "stone" -> 0x9a9a96;
			default -> Colours.NONE;
		};
	}

	/** Stops reading; for a server shutting down. */
	public void close() {
		downloads.shutdownNow();
	}
}
