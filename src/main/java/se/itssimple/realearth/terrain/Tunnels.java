package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Tunnels for roads, paths and railways, dug through the ground.
 *
 * <ul>
 * <li><b>Runs.</b> The map splits a tunnel at every tile edge (each copy reaching a little into the next
 * tile) and wherever a tag changes. Its pieces are joined into one run, reading the map tiles further
 * along until both of its real ends are found, so every chunk it passes under sees the same tunnel.</li>
 * <li><b>The floor</b> starts at the ground at each portal, where the ways outside carry on from it, and
 * ramps down from there as steeply as a bridge's approach until the roof has ground over it; under the
 * ground it keeps that cover, at a railway's or a road's grade, and under a hill it runs no higher than
 * the straight line between the portals. A short one, mostly a way under a road, stays level. Where a
 * tunnel ends in another (a branch, a ramp down to the main tube), that end is down at depth, not at a
 * portal.</li>
 * <li><b>The bore</b> is the way's width and a block more each side, {@link #HEADROOM_BLOCKS} high, lined
 * with stone where it runs through the ground, with a lantern under its roof every so often.</li>
 * </ul>
 */
public final class Tunnels {
	/** Clear height above a tunnel's floor, in blocks: about four metres. */
	public static final int HEADROOM_BLOCKS = 4;
	/** Every this many blocks along a tunnel, a lantern. */
	private static final int LIGHT_EVERY = 10;
	/** Two piece ends this close (samples) are one point; a piece end this close to another's line lies on it. */
	private static final double JOIN = 1.0;
	/** Most map tiles read to follow one chunk's tunnels to their ends. */
	private static final int MAX_EXTRA_TILES = 24;

	/** Steepest a tunnel's floor slopes under the ground: a railway's (rise over run), and a road's or path's. */
	private static final double RAIL_GRADE = 0.035;
	private static final double ROAD_GRADE = 0.07;
	/** Ground kept over a tunnel's roof, in blocks, where its floor can go deep enough. */
	private static final int COVER_BLOCKS = 3;
	/**
	 * A tunnel shorter than this keeps the straight floor between its portals: it is mostly a way passing
	 * under a road or a railway, which stays level rather than dipping to go under the ground.
	 */
	private static final double DEEP_MIN_METERS = 200;
	/** Spacing of the ground samples along a tunnel that its floor is worked out from, in metres. */
	private static final double PROFILE_STEP_METERS = 8;
	/** Each tunnel's floor, in metres every PROFILE_STEP along it: worked out once, the same for every chunk. */
	private static final Map<String, double[]> PROFILES = new ConcurrentHashMap<>();

	private Tunnels() {
	}

	/** One tunnel: its pieces joined end to end, in sample coordinates, and its floor along it (metres). */
	static final class Run {
		final double[] points;
		final double[] arc;
		final byte kind;
		final double widthMeters;
		/** Floor heights at evenly spaced points from one end to the other, both ends included. */
		double[] profile = {0, 0};
		/** Whether each end comes out into the open; otherwise it meets another tunnel under the ground. */
		boolean portalStart = true;
		boolean portalEnd = true;
		final double minX;
		final double minY;
		final double maxX;
		final double maxY;

		Run(double[] points, byte kind, double widthMeters) {
			this.points = points;
			this.kind = kind;
			this.widthMeters = widthMeters;
			int n = points.length / 2;
			arc = new double[n];
			double x0 = Double.MAX_VALUE;
			double y0 = Double.MAX_VALUE;
			double x1 = -Double.MAX_VALUE;
			double y1 = -Double.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				if (i > 0) {
					arc[i] = arc[i - 1] + Math.hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1]);
				}
				x0 = Math.min(x0, points[2 * i]);
				x1 = Math.max(x1, points[2 * i]);
				y0 = Math.min(y0, points[2 * i + 1]);
				y1 = Math.max(y1, points[2 * i + 1]);
			}
			minX = x0;
			minY = y0;
			maxX = x1;
			maxY = y1;
		}

		double length() {
			return arc[arc.length - 1];
		}

		/** Floor height in metres at arc length s (samples), between the profile's points. */
		double floorAt(double s) {
			double u = length() > 0 ? Math.max(0, Math.min(1, s / length())) : 0;
			double i = u * (profile.length - 1);
			int a = Math.min(profile.length - 2, (int) Math.floor(i));
			return profile[a] + (profile[a + 1] - profile[a]) * (i - a);
		}

		/** The point at arc length s along the line. */
		double[] pointAt(double s) {
			int n = arc.length;
			if (n == 1 || s <= 0) {
				return new double[] {points[0], points[1]};
			}
			for (int i = 1; i < n; i++) {
				if (s <= arc[i] || i == n - 1) {
					double seg = arc[i] - arc[i - 1];
					double u = seg > 0 ? Math.max(0, Math.min(1, (s - arc[i - 1]) / seg)) : 0;
					return new double[] {points[2 * i - 2] + (points[2 * i] - points[2 * i - 2]) * u,
						points[2 * i - 1] + (points[2 * i + 1] - points[2 * i - 1]) * u};
				}
			}
			return new double[] {points[2 * n - 2], points[2 * n - 1]};
		}

		boolean rail() {
			return kind == Ways.RAIL || kind == Ways.TRAM;
		}

		/** As a map way, for laying track along it. */
		OsmWater.Way asWay() {
			return new OsmWater.Way(points, widthMeters, kind, false, minX, minY, maxX, maxY, -1, -1);
		}
	}

	/**
	 * The tunnels reaching within {@code margin} samples of a box, each complete from end to end: map
	 * tiles further along are read as needed (blocking). Their floors are worked out too.
	 */
	static List<Run> near(TerrainField field, List<OsmWater.Shapes> shapes, double minX, double minY, double maxX, double maxY, double margin) {
		List<OsmWater.Way> pool = new ArrayList<>();
		// The ways on the ground too: an end they carry on from is a portal, whatever else meets it there.
		List<OsmWater.Way> surface = new ArrayList<>();
		Set<Long> tiles = new HashSet<>();
		for (OsmWater.Shapes s : shapes) {
			surface.addAll(s.ways());
			for (OsmWater.Way w : s.tunnels()) {
				pool.add(w);
				tiles.add(key(w.tileX(), w.tileY()));
			}
		}
		List<OsmWater.Way> seeds = new ArrayList<>();
		for (OsmWater.Way w : pool) {
			if (w.maxX() >= minX - margin && w.minX() <= maxX + margin && w.maxY() >= minY - margin && w.minY() <= maxY + margin) {
				seeds.add(w);
			}
		}
		if (seeds.isEmpty()) {
			return List.of();
		}
		Comparator<OsmWater.Way> order = Comparator.comparingDouble(OsmWater.Way::minX).thenComparingDouble(OsmWater.Way::minY)
			.thenComparingDouble(OsmWater.Way::maxX).thenComparingDouble(OsmWater.Way::maxY);
		seeds.sort(order);
		int[] extra = {0};
		Map<OsmWater.Way, Boolean> used = new IdentityHashMap<>();
		List<Run> runs = new ArrayList<>();
		for (OsmWater.Way seed : seeds) {
			if (used.containsKey(seed)) {
				continue;
			}
			used.put(seed, true);
			List<OsmWater.Way> taken = new ArrayList<>(List.of(seed));
			List<double[]> line = new ArrayList<>();
			double[] p = seed.points();
			for (int i = 0; i < p.length; i += 2) {
				line.add(new double[] {p[i], p[i + 1]});
			}
			// Both ways from the seed, until neither end goes on.
			for (int side = 0; side < 2; side++) {
				for (int guard = 0; guard < 256; guard++) {
					double[] end = line.get(line.size() - 1);
					if (!extend(line, pool, used, taken, seed.kind(), order)) {
						// No piece goes on from here: a real end, unless the map cut it at a tile edge.
						if (extra[0] >= MAX_EXTRA_TILES || !cutAtTileEdge(end, line, seed) || !readTile(field, end, pool, surface, tiles)) {
							break;
						}
						extra[0]++;
						if (!extend(line, pool, used, taken, seed.kind(), order)) {
							break;
						}
					}
				}
				java.util.Collections.reverse(line);
			}
			// The same tunnel's other copies, lying along it, are part of it already.
			for (OsmWater.Way w : pool) {
				if (!used.containsKey(w) && w.kind() == seed.kind() && liesAlong(w, line)) {
					used.put(w, true);
				}
			}
			// One way round for everyone: from the end that sorts first.
			double[] a = line.get(0);
			double[] b = line.get(line.size() - 1);
			if (a[0] > b[0] || a[0] == b[0] && a[1] > b[1]) {
				java.util.Collections.reverse(line);
			}
			double[] pts = new double[line.size() * 2];
			for (int i = 0; i < line.size(); i++) {
				pts[2 * i] = line.get(i)[0];
				pts[2 * i + 1] = line.get(i)[1];
			}
			Run run = new Run(pts, seed.kind(), seed.widthMeters());
			run.portalStart = !junction(pool, surface, taken, line, a, run.rail());
			run.portalEnd = !junction(pool, surface, taken, line, b, run.rail());
			floor(field, run);
			runs.add(run);
		}
		return runs;
	}

	/** Carries the line on from its last point with a piece that starts there or that the end lies on. True if it did. */
	private static boolean extend(List<double[]> line, List<OsmWater.Way> pool, Map<OsmWater.Way, Boolean> used, List<OsmWater.Way> taken, byte kind,
		Comparator<OsmWater.Way> order) {
		double[] end = line.get(line.size() - 1);
		List<OsmWater.Way> candidates = new ArrayList<>();
		for (OsmWater.Way w : pool) {
			if (!used.containsKey(w) && w.kind() == kind && w.maxX() >= end[0] - JOIN && w.minX() <= end[0] + JOIN && w.maxY() >= end[1] - JOIN
				&& w.minY() <= end[1] + JOIN) {
				candidates.add(w);
			}
		}
		candidates.sort(order);
		for (OsmWater.Way w : candidates) {
			double[] p = w.points();
			int n = p.length / 2;
			// End to end.
			if (Math.hypot(p[0] - end[0], p[1] - end[1]) <= JOIN) {
				for (int i = 1; i < n; i++) {
					line.add(new double[] {p[2 * i], p[2 * i + 1]});
				}
				used.put(w, true);
				taken.add(w);
				return true;
			}
			if (Math.hypot(p[2 * n - 2] - end[0], p[2 * n - 1] - end[1]) <= JOIN) {
				for (int i = n - 2; i >= 0; i--) {
					line.add(new double[] {p[2 * i], p[2 * i + 1]});
				}
				used.put(w, true);
				taken.add(w);
				return true;
			}
			// Overlapping: the end lies on the piece; carry on along it, away from the line.
			double[] near = new double[3];
			if (nearestOn(p, end[0], end[1], near) <= JOIN) {
				int segment = (int) near[2];
				boolean forward = !onLine(p[2 * n - 2], p[2 * n - 1], line);
				boolean backward = !onLine(p[0], p[1], line);
				if (forward == backward) {
					continue;
				}
				if (forward) {
					for (int i = segment + 1; i < n; i++) {
						line.add(new double[] {p[2 * i], p[2 * i + 1]});
					}
				} else {
					for (int i = segment; i >= 0; i--) {
						line.add(new double[] {p[2 * i], p[2 * i + 1]});
					}
				}
				used.put(w, true);
				taken.add(w);
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a run's end meets another tunnel of its family (rail with rail, road with road) under the
	 * ground, with no way of its family on the ground carrying on from it: a branch, a ramp joining the
	 * main tube, lines parting at a station. Such an end is no portal, and the floor stays down there.
	 */
	private static boolean junction(List<OsmWater.Way> pool, List<OsmWater.Way> surface, List<OsmWater.Way> taken, List<double[]> line, double[] end,
		boolean rail) {
		for (OsmWater.Way w : surface) {
			boolean wRail = w.kind() == Ways.RAIL || w.kind() == Ways.TRAM;
			double[] q = w.points();
			if (wRail == rail && (Math.hypot(q[0] - end[0], q[1] - end[1]) <= JOIN || Math.hypot(q[q.length - 2] - end[0], q[q.length - 1] - end[1]) <= JOIN)) {
				return false;
			}
		}
		double[] near = new double[3];
		for (OsmWater.Way w : pool) {
			boolean wRail = w.kind() == Ways.RAIL || w.kind() == Ways.TRAM;
			if (wRail != rail || taken.contains(w) || w.maxX() < end[0] - JOIN || w.minX() > end[0] + JOIN || w.maxY() < end[1] - JOIN
				|| w.minY() > end[1] + JOIN || liesAlong(w, line)) {
				continue;
			}
			if (nearestOn(w.points(), end[0], end[1], near) <= JOIN) {
				return true;
			}
		}
		return false;
	}

	/** Whether a line end lies outside the core of the map tile its piece came from: the map cut it there. */
	private static boolean cutAtTileEdge(double[] end, List<double[]> line, OsmWater.Way seed) {
		double per = perTile();
		int tx = (int) Math.floor((end[0] + 0.5) / per);
		int ty = (int) Math.floor((end[1] + 0.5) / per);
		// The end's own tile is where its piece came from, unless it lies in the margin of the next.
		double fx = (end[0] + 0.5) / per - tx;
		double fy = (end[1] + 0.5) / per - ty;
		return fx < 0.02 || fx > 0.98 || fy < 0.02 || fy > 0.98;
	}

	/** Reads the map tiles round a point, adding their tunnel pieces to the pool. True if any tile was new. */
	private static boolean readTile(TerrainField field, double[] at, List<OsmWater.Way> pool, List<OsmWater.Way> surface, Set<Long> tiles) {
		if (field.map() == null) {
			return false;
		}
		boolean any = false;
		for (int[] t : OsmWater.tilesCovering((long) Math.floor(at[0] - 2), (long) Math.floor(at[1] - 2), (long) Math.ceil(at[0] + 2),
			(long) Math.ceil(at[1] + 2))) {
			int x = Math.floorMod(t[0], 1 << OsmWater.ZOOM);
			if (!tiles.add(key(x, t[1]))) {
				continue;
			}
			try {
				OsmWater.Shapes shapes = field.map().tile(x, t[1]).get(60, TimeUnit.SECONDS);
				pool.addAll(shapes.tunnels());
				surface.addAll(shapes.ways());
				any = true;
			} catch (Exception e) {
				// Not to be had: the tunnel ends where its pieces do.
			}
		}
		return any;
	}

	private static boolean liesAlong(OsmWater.Way w, List<double[]> line) {
		double[] p = w.points();
		for (int i = 0; i < p.length; i += 2) {
			if (!onLine(p[i], p[i + 1], line)) {
				return false;
			}
		}
		return true;
	}

	private static boolean onLine(double x, double y, List<double[]> line) {
		for (int i = 0; i + 1 < line.size(); i++) {
			if (BuildingPlan.segment(x, y, line.get(i)[0], line.get(i)[1], line.get(i + 1)[0], line.get(i + 1)[1]) <= JOIN) {
				return true;
			}
		}
		return false;
	}

	/** Distance from (x, y) to the polyline; out[0..1] the nearest point, out[2] the segment's index. */
	private static double nearestOn(double[] p, double x, double y, double[] out) {
		double best = Double.MAX_VALUE;
		for (int i = 0; i + 3 < p.length; i += 2) {
			double d = BuildingPlan.segment(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]);
			if (d < best) {
				best = d;
				out[2] = i / 2;
			}
		}
		return best;
	}

	/** A run's floor: worked out from the ground along it the first time the tunnel is met (see {@link #profile}). */
	private static void floor(TerrainField field, Run run) {
		int n = run.points.length / 2;
		String key = run.kind + ":" + Math.round(run.points[0] * 4) + "," + Math.round(run.points[1] * 4) + ":"
			+ Math.round(run.points[2 * n - 2] * 4) + "," + Math.round(run.points[2 * n - 1] * 4) + ":" + run.portalStart + run.portalEnd;
		run.profile = PROFILES.computeIfAbsent(key, k -> profile(field, run));
	}

	/**
	 * The floor along a run, every PROFILE_STEP metres. At each portal it is the ground there. Along the
	 * way it wants to be deep enough for ground over the roof (under the lowest ground nearby), and no
	 * higher than the straight line between the portals. Under the ground it slopes no steeper than the
	 * run's grade; from each portal it ramps down to that depth at a bridge approach's gradient, and a
	 * tunnel too short to get there goes as deep as its ramps allow.
	 */
	private static double[] profile(TerrainField field, Run run) {
		double[] mid = run.pointAt(run.length() / 2);
		double lat = Mercator.latitudeOfSampleY(mid[1]);
		double spacing = Mercator.sampleSpacingMeters(lat);
		double perMeter = field.projection().blocksPerMeter(lat);
		double meters = run.length() * spacing;
		int n = Math.max(2, (int) Math.ceil(meters / PROFILE_STEP_METERS) + 1);
		double step = meters / (n - 1);
		double[] ground = new double[n];
		for (int i = 0; i < n; i++) {
			double[] p = run.pointAt(run.length() * i / (n - 1));
			ground[i] = ground(field, p[0], p[1]);
		}
		// Floor to ground over the roof: the floor block, the headroom, the roof's lining, and the cover.
		double depth = (HEADROOM_BLOCKS + 2 + COVER_BLOCKS) / perMeter;
		// A portal's floor is the ground there; where the end meets another tunnel, it is down at depth.
		double start = run.portalStart ? ground[0] : ground[0] - depth;
		double end = run.portalEnd ? ground[n - 1] : ground[n - 1] - depth;
		if (meters < DEEP_MIN_METERS) {
			return new double[] {start, end};
		}
		double[] floor = new double[n];
		for (int i = 0; i < n; i++) {
			double lowest = ground[i];
			for (int j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 2); j++) {
				lowest = Math.min(lowest, ground[j]);
			}
			double straight = start + (end - start) * i / (n - 1);
			floor[i] = Math.min(straight, lowest - depth);
		}
		// No steeper than the grade (or the line between the ends, where that is steeper). A junction end is
		// held at its depth, so the passes start from it.
		if (!run.portalStart) {
			floor[0] = start;
		}
		if (!run.portalEnd) {
			floor[n - 1] = end;
		}
		double rise = Math.max(run.rail() ? RAIL_GRADE : ROAD_GRADE, Math.abs(end - start) / Math.max(1, meters)) * step;
		for (int i = 1; i < n; i++) {
			floor[i] = Math.min(floor[i], floor[i - 1] + rise);
		}
		for (int i = n - 2; i >= 0; i--) {
			floor[i] = Math.min(floor[i], floor[i + 1] + rise);
		}
		// And from each portal's ground a ramp down to that, as steep as a bridge's approach: the stretch
		// open to the sky is short, walled by the lining.
		double ramp = Math.max(Bridges.GRADIENT, Math.abs(end - start) / Math.max(1, meters)) * step;
		for (int i = 0; i < n; i++) {
			double fromStart = run.portalStart ? start - ramp * i : Double.NEGATIVE_INFINITY;
			double fromEnd = run.portalEnd ? end - ramp * (n - 1 - i) : Double.NEGATIVE_INFINITY;
			floor[i] = Math.max(floor[i], Math.max(fromStart, fromEnd));
		}
		if (Boolean.getBoolean("realearth.selftest")) {
			double lowest = Double.MAX_VALUE;
			for (double f : floor) {
				lowest = Math.min(lowest, f);
			}
			se.itssimple.realearth.RealEarth.LOGGER.info("[tunnels] run kind {}{}{} at {},{}: {} m, {} points, ground {}..{} m, floor ends {}/{} lowest {} m", run.kind,
				run.portalStart ? "" : " (junction at start)", run.portalEnd ? "" : " (junction at end)",
				Math.round(lat * 1e4) / 1e4, Math.round(Projection.wrapLongitude((mid[0] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0) * 1e4) / 1e4,
				Math.round(meters), run.points.length / 2, Math.round(start), Math.round(end), Math.round(floor[0]), Math.round(floor[n - 1]), Math.round(lowest));
			int last = run.points.length - 2;
			se.itssimple.realearth.RealEarth.LOGGER.info("[tunnels]   ends {},{} and {},{}",
				Math.round(Mercator.latitudeOfSampleY(run.points[1]) * 1e5) / 1e5, Math.round(Projection.wrapLongitude((run.points[0] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0) * 1e5) / 1e5,
				Math.round(Mercator.latitudeOfSampleY(run.points[last + 1]) * 1e5) / 1e5,
				Math.round(Projection.wrapLongitude((run.points[last] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0) * 1e5) / 1e5);
		}
		return floor;
	}

	private static double ground(TerrainField field, double sx, double sy) {
		double lat = Mercator.latitudeOfSampleY(sy);
		double lon = Projection.wrapLongitude((sx + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
		try {
			return field.elevationAt(lat, lon, Mercator.ZOOM);
		} catch (Exception e) {
			return field.coarseElevationMeters(lat, lon);
		}
	}

	/**
	 * Marks the chunk's tunnel columns: the bore (and a block more each side), its lining a block further
	 * out, and the floor's height; track laid along the railway ones. (A shallow one takes its way through
	 * a building over it: see Buildings.)
	 */
	static void apply(TerrainField field, ChunkTerrain t, List<Run> runs, double[] sx, double[] sy, boolean[] onGlobe, int chunkX, int chunkZ) {
		int n = ChunkTerrain.SPAN;
		for (Run run : runs) {
			for (int k = 0; k < n * n; k++) {
				if (!onGlobe[k] || t.tunnel[k] == ChunkTerrain.TUNNEL_BORE) {
					continue;
				}
				double spacing = Mercator.sampleSpacingMeters(t.latitude[k]);
				double perMeter = field.projection().blocksPerMeter(t.latitude[k]);
				double[] near = new double[2];
				double d = distance(run, sx[k], sy[k], near) * spacing * perMeter;
				double half = Math.max(0.5, run.widthMeters / 2 * perMeter);
				if (d > half + 2) {
					continue;
				}
				double floorMeters = run.floorAt(near[0]);
				int floorY = (int) Math.round(field.toY(floorMeters, t.latitude[k])) - 1;
				boolean bore = d <= half + 1;
				if (!bore && t.tunnel[k] != 0) {
					continue;
				}
				t.tunnel[k] = bore ? ChunkTerrain.TUNNEL_BORE : ChunkTerrain.TUNNEL_WALL;
				t.tunnelY[k] = floorY;
				if (bore) {
					t.tunnelKind[k] = run.rail() ? Ways.RAIL_BED : run.kind;
				}
			}
			if (run.rail()) {
				double[] along = new double[2];
				RailTracks.trace(field, t, run.asWay(), chunkX, chunkZ, null, true, (px, py) -> {
					distance(run, px, py, along);
					return run.floorAt(along[0]);
				});
			}
		}
		RailTracks.joinPortals(t);
	}

	/** Whether a tunnel column on its centreline has a lantern: one every LIGHT_EVERY blocks, by where it is. */
	public static boolean lit(ChunkTerrain t, int x, int z, int worldX, int worldZ) {
		int k = ChunkTerrain.index(x, z);
		return t.tunnel[k] == ChunkTerrain.TUNNEL_BORE && Math.floorMod(worldX * 7 + worldZ * 13, LIGHT_EVERY) == 0
			&& (t.tunnelKind[k] == Ways.RAIL || t.tunnelKind[k] != Ways.RAIL_BED && middle(t, x, z));
	}

	/** A bore column with bore on all four sides: away from the walls. */
	private static boolean middle(ChunkTerrain t, int x, int z) {
		return t.tunnel[ChunkTerrain.index(x - 1, z)] == ChunkTerrain.TUNNEL_BORE && t.tunnel[ChunkTerrain.index(x + 1, z)] == ChunkTerrain.TUNNEL_BORE
			&& t.tunnel[ChunkTerrain.index(x, z - 1)] == ChunkTerrain.TUNNEL_BORE && t.tunnel[ChunkTerrain.index(x, z + 1)] == ChunkTerrain.TUNNEL_BORE;
	}

	/** Distance (samples) from a point to the run's line; near[0] the arc length there. */
	private static double distance(Run run, double x, double y, double[] near) {
		if (x < run.minX - 8 || x > run.maxX + 8 || y < run.minY - 8 || y > run.maxY + 8) {
			return Double.MAX_VALUE;
		}
		double best = Double.MAX_VALUE;
		double[] p = run.points;
		for (int i = 0; i + 3 < p.length; i += 2) {
			double dx = p[i + 2] - p[i];
			double dy = p[i + 3] - p[i + 1];
			double len2 = dx * dx + dy * dy;
			double u = len2 > 0 ? Math.max(0, Math.min(1, ((x - p[i]) * dx + (y - p[i + 1]) * dy) / len2)) : 0;
			double d = Math.hypot(x - (p[i] + u * dx), y - (p[i + 1] + u * dy));
			if (d < best) {
				best = d;
				near[0] = run.arc[i / 2] + u * Math.sqrt(len2);
			}
		}
		return best;
	}

	private static double perTile() {
		return Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}
}
