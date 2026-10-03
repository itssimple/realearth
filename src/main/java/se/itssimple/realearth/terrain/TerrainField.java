package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import se.itssimple.realearth.EarthConfig;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;

/**
 * The terrain of the Earth dimension: block columns in, ground and water heights out.
 *
 * Everything is asynchronous. A chunk asks for the processed tiles under it, which ask for the raw
 * tiles around them, which come from disk or the network; world generation is handed a future and
 * never blocks a worker on I/O.
 *
 * Interpolation from the source grid to one-metre blocks is a uniform cubic B-spline. Unlike
 * bilinear filtering it is C2-continuous, so slopes change smoothly instead of showing the grid as
 * creases every few metres, and unlike Catmull-Rom it never overshoots, so it cannot invent ridges
 * or pits next to cliffs.
 */
public final class TerrainField implements AutoCloseable {
	private final TileStore store;
	private final EarthConfig config;
	private final Projection projection;
	private final VerticalScale scale;
	private final ExecutorService workers;
	private final Map<Long, CompletableFuture<ProcessedTile>> processed;
	private final Map<Long, CompletableFuture<ChunkTerrain>> chunks;

	private final OsmWater map;
	private final WorldCover cover;

	/** How long a result built from stand-ins is reused before the data is asked for again. */
	private static final long RETRY_DEGRADED_MS = 30_000;

	/**
	 * @param map   OpenStreetMap water, or null to take water from elevation alone
	 * @param cover ESA land cover, or null to leave the ground cover to climate alone
	 */
	public TerrainField(TileStore store, EarthConfig config, Projection projection, VerticalScale scale, OsmWater map, WorldCover cover) {
		this.map = map;
		this.cover = cover;
		this.store = store;
		this.config = config;
		this.projection = projection;
		this.scale = scale;
		AtomicInteger n = new AtomicInteger();
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		this.workers = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "RealEarth-Terrain-" + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
		this.processed = lru(48);
		Buildings.clearCache();
		this.chunks = lru(2048);
	}

	private static <V> Map<Long, V> lru(int max) {
		return new LinkedHashMap<>(max, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
				return size() > max;
			}
		};
	}

	public Projection projection() {
		return projection;
	}

	public VerticalScale scale() {
		return scale;
	}

	private volatile int startX;
	private volatile int startZ;

	/** Sets where players start; Overworld portals lead to positions relative to it. */
	public void setStart(double latitude, double longitude) {
		startX = (int) Math.floor(projection.blockX(latitude, longitude));
		startZ = (int) Math.floor(projection.blockZ(latitude));
	}

	public int startX() {
		return startX;
	}

	public int startZ() {
		return startZ;
	}

	/**
	 * Block Y of an elevation in metres at a latitude. Heights follow the projection's horizontal
	 * scale, so terrain keeps its true proportions wherever the map is enlarged or shrunk.
	 */
	public double toY(double meters, double latitude) {
		return scale.toY(meters * projection.blocksPerMeter(latitude));
	}

	/** Elevation in metres of a block Y at a latitude: the inverse of {@link #toY}. */
	public double toElevation(double y, double latitude) {
		return scale.toElevation(y) / projection.blocksPerMeter(latitude);
	}

	public TileStore store() {
		return store;
	}

	private static long key(int a, int b) {
		return ((long) a << 32) | (b & 0xFFFFFFFFL);
	}

	private CompletableFuture<ProcessedTile> processedTile(int tx, int ty) {
		int x = Math.floorMod(tx, Mercator.TILES_PER_AXIS);
		int y = Math.max(0, Math.min(Mercator.TILES_PER_AXIS - 1, ty));
		synchronized (processed) {
			CompletableFuture<ProcessedTile> existing = processed.get(key(x, y));
			if (existing != null && isStale(existing)) {
				processed.remove(key(x, y));
			}
			return processed.computeIfAbsent(key(x, y), k -> {
				@SuppressWarnings("unchecked")
				CompletableFuture<float[]>[] raw = new CompletableFuture[9];
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						raw[(dy + 1) * 3 + (dx + 1)] = store.tile(x + dx, y + dy);
					}
				}
				List<CompletableFuture<OsmWater.Shapes>> shapes = new ArrayList<>();
				if (map != null) {
					int margin = 48;
					for (int[] t : OsmWater.tilesCovering((long) x * Mercator.TILE_SIZE - margin, (long) y * Mercator.TILE_SIZE - margin,
						(long) (x + 1) * Mercator.TILE_SIZE + margin, (long) (y + 1) * Mercator.TILE_SIZE + margin)) {
						shapes.add(map.tile(t[0], t[1]));
					}
				}
				CompletableFuture<?>[] all = new CompletableFuture[9 + shapes.size()];
				System.arraycopy(raw, 0, all, 0, 9);
				for (int i = 0; i < shapes.size(); i++) {
					all[9 + i] = shapes.get(i);
				}
				return CompletableFuture.allOf(all).thenApplyAsync(v -> {
					float[][] tiles = new float[9][];
					for (int i = 0; i < 9; i++) {
						tiles[i] = raw[i].join();
					}
					List<OsmWater.Shapes> water = shapes.stream().map(CompletableFuture::join).toList();
					ProcessedTile tile = ProcessedTile.build(tiles, x, y, config, map == null ? null : water);
					tile.degraded = water.stream().anyMatch(OsmWater.Shapes::failed);
					for (float[] t : tiles) {
						tile.degraded |= store.isDegraded(t);
					}
					return tile;
				}, workers);
			});
		}
	}

	/** The terrain of a chunk, computed once and remembered for a while. */
	public CompletableFuture<ChunkTerrain> chunk(int chunkX, int chunkZ) {
		synchronized (chunks) {
			CompletableFuture<ChunkTerrain> existing = chunks.get(key(chunkX, chunkZ));
			if (existing != null && !existing.isCompletedExceptionally() && !isStale(existing)) {
				return existing;
			}
			CompletableFuture<ChunkTerrain> created = computeChunk(chunkX, chunkZ);
			chunks.put(key(chunkX, chunkZ), created);
			return created;
		}
	}

	/** True for a finished result built from stand-ins long enough ago that the data is worth asking for again. */
	private static boolean isStale(CompletableFuture<?> f) {
		if (!f.isDone() || f.isCompletedExceptionally()) {
			return false;
		}
		Object v = f.join();
		long now = System.currentTimeMillis();
		if (v instanceof ChunkTerrain t) {
			return t.degraded && now - t.createdAt > RETRY_DEGRADED_MS;
		}
		if (v instanceof ProcessedTile t) {
			return t.degraded && now - t.createdAt > RETRY_DEGRADED_MS;
		}
		return false;
	}

	/** Forgets a chunk's terrain, so the next request computes it afresh. */
	public void forgetChunk(int chunkX, int chunkZ) {
		synchronized (chunks) {
			chunks.remove(key(chunkX, chunkZ));
		}
	}

	public OsmWater map() {
		return map;
	}

	/** The chunk's terrain if it is already known, without starting any work. */
	public ChunkTerrain chunkIfReady(int chunkX, int chunkZ) {
		CompletableFuture<ChunkTerrain> f;
		synchronized (chunks) {
			f = chunks.get(key(chunkX, chunkZ));
		}
		return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
	}

	/** Zoom of the coarse elevation used for quick, approximate answers. ~1.2 km per sample. */
	private static final int COARSE_ZOOM = 7;

	/**
	 * Approximate ground elevation from a zoom-7 tile (one tile covers ~300 km), for questions asked
	 * far from where chunks are being generated -- /locate, explorer maps, stronghold placement --
	 * where fetching full-resolution tiles would mean thousands of downloads. Blocks only on the first
	 * question about a region; those tiles are few and cached.
	 */
	public double coarseElevationMeters(double latitude, double longitude) {
		double scale = (double) (1 << COARSE_ZOOM) / (1 << Mercator.ZOOM);
		double sx = (Mercator.sampleX(longitude) + 0.5) * scale - 0.5;
		double sy = (Mercator.sampleY(latitude) + 0.5) * scale - 0.5;
		int px = (int) Math.round(sx);
		int py = (int) Math.round(sy);
		int size = Mercator.TILE_SIZE;
		try {
			float[] tile = store.tile(COARSE_ZOOM, Math.floorDiv(px, size), Math.floorDiv(py, size)).get(30, TimeUnit.SECONDS);
			return tile[Math.floorMod(py, size) * size + Math.floorMod(px, size)];
		} catch (Exception e) {
			return 100.0;
		}
	}

	/** Thrown instead of answering from stand-ins, by callers that would rather wait for the real data. */
	public static final class Unavailable extends Exception {
		public Unavailable() {
			super("data not downloaded yet");
		}
	}

	/**
	 * Raw elevation at any zoom, bilinear between sample centres. Throws {@link Unavailable} rather
	 * than answer from a stand-in tile. Blocks on the tile fetch; meant for
	 * worker threads that need terrain far from any chunk (level-of-detail rendering).
	 */
	public double elevationAt(double latitude, double longitude, int zoom) throws Exception {
		double scale = (double) (1 << zoom) / (1 << Mercator.ZOOM);
		double sx = (Mercator.sampleX(longitude) + 0.5) * scale - 0.5;
		double sy = (Mercator.sampleY(latitude) + 0.5) * scale - 0.5;
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double fx = sx - x0;
		double fy = sy - y0;
		int size = Mercator.TILE_SIZE;
		long last = (long) size << zoom;
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			long gx = Math.floorMod(x0 + (i & 1), last);
			long gy = Math.max(0, Math.min(last - 1, y0 + (i >> 1)));
			float[] tile = store.tile(zoom, (int) (gx / size), (int) (gy / size)).get(60, TimeUnit.SECONDS);
			if (store.isDegraded(tile)) {
				throw new Unavailable();
			}
			v[i] = tile[(int) (gy % size) * size + (int) (gx % size)];
		}
		return (v[0] * (1 - fx) + v[1] * fx) * (1 - fy) + (v[2] * (1 - fx) + v[3] * fx) * fy;
	}

	/**
	 * Smoothed ground height (the processed z15 grid chunks are built from), bilinear. For distant
	 * terrain near the player, so it meets the real chunks without a step.
	 */
	public double smoothedElevationAt(double latitude, double longitude) throws Exception {
		double sx = Mercator.sampleX(longitude);
		double sy = Mercator.sampleY(latitude);
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double fx = sx - x0;
		double fy = sy - y0;
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			long gx = Math.floorMod(x0 + (i & 1), Mercator.SAMPLES_PER_AXIS);
			long gy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, y0 + (i >> 1)));
			ProcessedTile tile = processedTile((int) (gx / Mercator.TILE_SIZE), (int) (gy / Mercator.TILE_SIZE)).get(120, TimeUnit.SECONDS);
			if (tile.degraded) {
				throw new Unavailable();
			}
			v[i] = tile.height[indexIn(gx, gy)];
		}
		return (v[0] * (1 - fx) + v[1] * fx) * (1 - fy) + (v[2] * (1 - fx) + v[3] * fx) * fy;
	}

	public WorldCover landCover() {
		return cover;
	}

	/** Blocks until the chunk is known. For commands and portals, never for world generation. */
	public ChunkTerrain chunkBlocking(int chunkX, int chunkZ, long timeoutSeconds) throws Exception {
		return chunk(chunkX, chunkZ).get(timeoutSeconds, TimeUnit.SECONDS);
	}

	private CompletableFuture<ChunkTerrain> computeChunk(int chunkX, int chunkZ) {
		int n = ChunkTerrain.SPAN;
		double[] sx = new double[n * n];
		double[] sy = new double[n * n];
		boolean[] onGlobe = new boolean[n * n];
		ChunkTerrain terrain = new ChunkTerrain();

		long minX = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long minY = Long.MAX_VALUE;
		long maxY = Long.MIN_VALUE;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int k = j * n + i;
				double bx = chunkX * 16 + (i - 1) + 0.5;
				double bz = chunkZ * 16 + (j - 1) + 0.5;
				double lat = projection.latitudeAt(bz);
				double lonOffset = projection.longitudeOffsetAt(bx, lat);
				onGlobe[k] = projection.onGlobe(lat, lonOffset);
				double lon = Projection.wrapLongitude(projection.originLongitude() + lonOffset);
				terrain.latitude[k] = Math.max(-90, Math.min(90, lat));
				terrain.longitude[k] = lon;
				if (!onGlobe[k]) {
					continue;
				}
				sx[k] = Mercator.sampleX(lon);
				sy[k] = Mercator.sampleY(lat);
				long fx = (long) Math.floor(sx[k]);
				long fy = (long) Math.floor(sy[k]);
				minX = Math.min(minX, fx - 1);
				maxX = Math.max(maxX, fx + 2);
				minY = Math.min(minY, fy - 1);
				maxY = Math.max(maxY, fy + 2);
			}
		}

		Map<Long, CompletableFuture<ProcessedTile>> needed = new HashMap<>();
		if (minX <= maxX) {
			// A chunk straddling the antimeridian spans the whole sample range; collect per column.
			if (maxX - minX > 4L * Mercator.TILE_SIZE) {
				for (int k = 0; k < n * n; k++) {
					if (onGlobe[k]) {
						addTiles(needed, (long) Math.floor(sx[k]) - 1, (long) Math.floor(sx[k]) + 2,
							(long) Math.floor(sy[k]) - 1, (long) Math.floor(sy[k]) + 2);
					}
				}
			} else {
				// A few samples more than the spline needs: roads level to their centreline, which
				// may lie just outside the chunk.
				addTiles(needed, minX - 4, maxX + 4, minY - 4, maxY + 4);
			}
		}

		List<CompletableFuture<OsmWater.Shapes>> wayTiles = new ArrayList<>();
		List<int[]> wayTileCoords = new ArrayList<>();
		if (map != null && (config.useRoads || config.useRailways || config.useBuildings) && minX <= maxX && maxX - minX <= 4L * Mercator.TILE_SIZE) {
			for (int[] w : OsmWater.tilesCovering(minX - 4, minY - 4, maxX + 4, maxY + 4)) {
				wayTiles.add(map.tile(w[0], w[1]));
				wayTileCoords.add(new int[] {Math.floorMod(w[0], 1 << OsmWater.ZOOM), w[1]});
			}
		}
		long chunkMinX = minX;
		long chunkMinY = minY;
		long chunkMaxX = maxX;
		long chunkMaxY = maxY;

		CompletableFuture<WorldCover.Lookup> coverLookup = cover == null
			? CompletableFuture.completedFuture((lat, lon) -> WorldCover.NONE)
			: cover.prepare(terrain.latitude, terrain.longitude, 0).exceptionally(e -> new WorldCover.Lookup() {
				@Override
				public int classAt(double lat, double lon) {
					return WorldCover.NONE;
				}

				@Override
				public boolean degraded() {
					return true;
				}
			});

		CompletableFuture<?>[] all = new CompletableFuture[needed.size() + 1 + wayTiles.size()];
		int a = 0;
		for (CompletableFuture<ProcessedTile> f : needed.values()) {
			all[a++] = f;
		}
		all[a++] = coverLookup;
		for (CompletableFuture<OsmWater.Shapes> f : wayTiles) {
			all[a++] = f;
		}
		return CompletableFuture.allOf(all).thenCompose(v -> {
			// Buildings stand on the ground under their whole footprint, which may reach well beyond
			// this chunk: fetch the terrain there too before going on.
			List<OsmWater.Shapes> shapes = wayTiles.stream().map(CompletableFuture::join).toList();
			long nearing = se.itssimple.realearth.Perf.start();
			List<Buildings.Candidate> candidates = config.useBuildings
				? Buildings.near(this, wayTileCoords, shapes, chunkX, chunkZ, chunkMinX, chunkMinY, chunkMaxX, chunkMaxY)
				: List.of();
			se.itssimple.realearth.Perf.stop("terrain.buildings.near", nearing);
			Map<Long, CompletableFuture<ProcessedTile>> withBuildings = new HashMap<>(needed);
			for (Buildings.Candidate c : candidates) {
				if (c.planned()) {
					continue;
				}
				for (double[] p : Buildings.heightPoints(c)) {
					addTiles(withBuildings, (long) Math.floor(p[0]) - 2, (long) Math.floor(p[0]) + 3, (long) Math.floor(p[1]) - 2, (long) Math.floor(p[1]) + 3);
				}
			}
			return CompletableFuture.allOf(withBuildings.values().toArray(CompletableFuture[]::new)).thenApplyAsync(w -> {
				Map<Long, ProcessedTile> all2 = new HashMap<>();
				withBuildings.forEach((key, f) -> all2.put(key, f.join()));
				long started = se.itssimple.realearth.Perf.start();
				ChunkTerrain built = build(terrain, all2, needed.keySet(), coverLookup.join(), shapes, candidates, sx, sy, onGlobe, chunkX, chunkZ);
				se.itssimple.realearth.Perf.stop("terrain", started);
				return built;
			}, workers);
		});
	}

	/** The chunk's terrain from everything fetched for it. */
	private ChunkTerrain build(ChunkTerrain terrain, Map<Long, ProcessedTile> tiles, java.util.Set<Long> own, WorldCover.Lookup landCover,
		List<OsmWater.Shapes> shapes, List<Buildings.Candidate> candidates, double[] sx, double[] sy, boolean[] onGlobe, int chunkX, int chunkZ) {
		int n = ChunkTerrain.SPAN;
		{
			terrain.degraded = landCover.degraded() || tiles.values().stream().anyMatch(t -> t.degraded);
			Koppen climate = Koppen.get();
			for (int k = 0; k < n * n; k++) {
				terrain.cover[k] = (byte) landCover.classAt(terrain.latitude[k], terrain.longitude[k]);
				terrain.climate[k] = (byte) (climate == null ? 0 : climate.classAt(terrain.latitude[k], terrain.longitude[k]));
				if (onGlobe[k]) {
					sampleColumn(tiles, sx[k], sy[k], terrain, k);
				} else {
					// Corners of the projection beyond the antimeridian: open ocean.
					terrain.groundMeters[k] = -3000f;
					terrain.waterMeters[k] = 0f;
				}
			}
			if (!shapes.isEmpty()) {
				List<OsmWater.Way> ways = new ArrayList<>();
				for (OsmWater.Shapes s : shapes) {
					terrain.degraded |= s.failed();
					ways.addAll(s.ways());
				}
				if (config.useRoads || config.useRailways) {
					long started = se.itssimple.realearth.Perf.start();
					WayRaster.apply(this, terrain, tiles, sx, sy, onGlobe, ways, chunkX, chunkZ, config.useRoads, config.useRailways);
					se.itssimple.realearth.Perf.stop("terrain.ways", started);
				}
			}
			for (int k = 0; k < n * n; k++) {
				finishColumn(terrain, k);
			}
			if (!candidates.isEmpty()) {
				long started = se.itssimple.realearth.Perf.start();
				Buildings.plan(this, terrain, candidates, tiles, sx, sy, onGlobe, chunkX, chunkZ, config.storeyMeters, config.maxFloors);
				se.itssimple.realearth.Perf.stop("terrain.buildings", started);
			}
			return terrain;
		}
	}

	private void addTiles(Map<Long, CompletableFuture<ProcessedTile>> out, long minX, long maxX, long minY, long maxY) {
		long lastY = Mercator.SAMPLES_PER_AXIS - 1;
		int tx0 = (int) Math.floorDiv(minX, Mercator.TILE_SIZE);
		int tx1 = (int) Math.floorDiv(maxX, Mercator.TILE_SIZE);
		int ty0 = (int) Math.floorDiv(Math.max(0, Math.min(lastY, minY)), Mercator.TILE_SIZE);
		int ty1 = (int) Math.floorDiv(Math.max(0, Math.min(lastY, maxY)), Mercator.TILE_SIZE);
		for (int ty = ty0; ty <= ty1; ty++) {
			int row = ty;
			for (int tx = tx0; tx <= tx1; tx++) {
				int wx = Math.floorMod(tx, Mercator.TILES_PER_AXIS);
				out.computeIfAbsent(key(wx, row), k -> processedTile(wx, row));
			}
		}
	}

	private static ProcessedTile tileFor(Map<Long, ProcessedTile> tiles, long gx, long gy) {
		long wx = Math.floorMod(gx, Mercator.SAMPLES_PER_AXIS);
		long wy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, gy));
		return tiles.get(key((int) (wx / Mercator.TILE_SIZE), (int) (wy / Mercator.TILE_SIZE)));
	}

	private static int indexIn(long gx, long gy) {
		long wx = Math.floorMod(gx, Mercator.SAMPLES_PER_AXIS);
		long wy = Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, gy));
		return (int) ((wy % Mercator.TILE_SIZE) * Mercator.TILE_SIZE + (wx % Mercator.TILE_SIZE));
	}

	/** Cubic B-spline basis weights for the four samples around a fractional position t in [0, 1). */
	private static void bspline(double t, double[] w) {
		double t2 = t * t;
		double t3 = t2 * t;
		double u = 1.0 - t;
		w[0] = u * u * u / 6.0;
		w[1] = (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
		w[2] = (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
		w[3] = t3 / 6.0;
	}

	/** Smoothed ground height at a sample position, by the same B-spline as columns; NaN off the tiles at hand. */
	static double splineHeight(Map<Long, ProcessedTile> tiles, double sx, double sy) {
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double[] wx = new double[4];
		double[] wy = new double[4];
		bspline(sx - x0, wx);
		bspline(sy - y0, wy);
		double height = 0;
		for (int b = 0; b < 4; b++) {
			double row = 0;
			for (int a = 0; a < 4; a++) {
				long gx = x0 - 1 + a;
				long gy = y0 - 1 + b;
				ProcessedTile tile = tileFor(tiles, gx, gy);
				if (tile == null) {
					return Double.NaN;
				}
				row += wx[a] * tile.height[indexIn(gx, gy)];
			}
			height += wy[b] * row;
		}
		return height;
	}

	private void sampleColumn(Map<Long, ProcessedTile> tiles, double sx, double sy, ChunkTerrain out, int k) {
		long x0 = (long) Math.floor(sx);
		long y0 = (long) Math.floor(sy);
		double[] wx = new double[4];
		double[] wy = new double[4];
		bspline(sx - x0, wx);
		bspline(sy - y0, wy);

		double height = 0;
		for (int b = 0; b < 4; b++) {
			double row = 0;
			for (int a = 0; a < 4; a++) {
				long gx = x0 - 1 + a;
				long gy = y0 - 1 + b;
				row += wx[a] * tileFor(tiles, gx, gy).height[indexIn(gx, gy)];
			}
			height += wy[b] * row;
		}

		// Lakes: bilinear coverage of the four nearest samples. More than half wet is water.
		double fx = sx - x0;
		double fy = sy - y0;
		double wet = 0;
		double depth = 0;
		double levelSum = 0;
		float level = Float.NaN;
		for (int b = 0; b < 2; b++) {
			for (int a = 0; a < 2; a++) {
				long gx = x0 + a;
				long gy = y0 + b;
				ProcessedTile tile = tileFor(tiles, gx, gy);
				int idx = indexIn(gx, gy);
				float l = tile.lakeLevel[idx];
				if (!Float.isNaN(l)) {
					double w = (a == 0 ? 1 - fx : fx) * (b == 0 ? 1 - fy : fy);
					wet += w;
					depth += w * tile.lakeDepth[idx];
					levelSum += w * l;
					level = Float.isNaN(level) ? l : Math.max(level, l);
				}
			}
		}

		// With OpenStreetMap water: the map decides, block by block, from its 4x-resolution raster.
		double px = sx + 0.5;
		double py = sy + 0.5;
		ProcessedTile own = tileFor(tiles, (long) Math.floor(px), (long) Math.floor(py));
		if (own.mapWater != null) {
			long wpx = Math.floorMod((long) Math.floor(px), Mercator.SAMPLES_PER_AXIS);
			int fi = (int) Math.floor((px - Math.floor(px) + (wpx % Mercator.TILE_SIZE)) * ProcessedTile.FINE);
			int fj = (int) Math.floor((py - Math.floor(py) + (Math.max(0, Math.min(Mercator.SAMPLES_PER_AXIS - 1, (long) Math.floor(py))) % Mercator.TILE_SIZE)) * ProcessedTile.FINE);
			fi = Math.max(0, Math.min(ProcessedTile.FINE_SIZE - 1, fi));
			fj = Math.max(0, Math.min(ProcessedTile.FINE_SIZE - 1, fj));
			int mapped = own.mapWater[fj * ProcessedTile.FINE_SIZE + fi];
			if (mapped == 0) {
				out.waterMeters[k] = Float.NaN;
				out.groundMeters[k] = (float) height;
				return;
			}
			double surface = mapped == 2 ? 0.0 : wet > 0 ? levelSum / wet : height;
			double below = wet > 0 ? Math.max(1.0, depth / wet) : 1.0;
			if (mapped == 2 || surface <= 0.5) {
				out.waterMeters[k] = 0f;
				out.groundMeters[k] = (float) Math.min(height, -below);
			} else {
				out.lake[k] = true;
				out.waterMeters[k] = (float) surface;
				out.groundMeters[k] = (float) Math.min(height, surface - below);
			}
			return;
		}

		if (wet >= 0.5 && level > 0.5f) {
			out.lake[k] = true;
			out.waterMeters[k] = level;
			out.groundMeters[k] = (float) Math.min(height, level - depth / wet);
		} else if (wet >= 0.5) {
			// Flat at or below sea level is sea, not a lake: coastal water the source flattened to 0,
			// or deep ocean whose coarse bathymetry was upsampled into perfectly flat patches.
			out.waterMeters[k] = 0f;
			out.groundMeters[k] = (float) Math.min(height, -depth / wet);
		} else if (height < 0) {
			out.waterMeters[k] = 0f;
			out.groundMeters[k] = (float) height;
		} else {
			out.waterMeters[k] = Float.NaN;
			out.groundMeters[k] = (float) height;
		}
	}

	/** Metres to blocks. The top solid block's upper face sits at the nearest whole Y to the ground. */
	private void finishColumn(ChunkTerrain t, int k) {
		int top = (int) Math.round(toY(t.groundMeters[k], t.latitude[k])) - 1;
		if (!Float.isNaN(t.deckMeters[k])) {
			int deck = (int) Math.round(toY(t.deckMeters[k], t.latitude[k])) - 1;
			int below = Math.max(top, t.waterMeters[k] != t.waterMeters[k] ? top : (int) Math.round(toY(t.waterMeters[k], t.latitude[k])) - 1);
			// A deck clears what is under it by at least two blocks, or it is no bridge.
			t.deckY[k] = deck > below + 2 ? deck : below + 3;
		}
		t.topSolidY[k] = top;
		if (!Float.isNaN(t.waterMeters[k])) {
			int water = (int) Math.round(toY(t.waterMeters[k], t.latitude[k])) - 1;
			// Where the map is shrunk, a metre of water is less than a block and could round away;
			// mapped water always keeps at least one block.
			if (water <= top) {
				double perMeter = projection.blocksPerMeter(t.latitude[k]);
				t.groundMeters[k] = (float) Math.min(t.groundMeters[k], t.waterMeters[k] - 1.0 / perMeter);
				top = Math.min(water - 1, (int) Math.round(toY(t.groundMeters[k], t.latitude[k])) - 1);
				t.topSolidY[k] = top;
			}
			t.waterTopY[k] = water;
		} else {
			t.waterTopY[k] = Integer.MIN_VALUE;
		}
	}

	@Override
	public void close() {
		workers.shutdownNow();
		store.close();
	}
}
