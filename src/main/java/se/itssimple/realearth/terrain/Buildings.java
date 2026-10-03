package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Turns the map's building footprints near a chunk into {@link BuildingPlan}s, and marks which of the
 * chunk's columns each one covers.
 *
 * A building stands on one floor level, set from the ground under its whole footprint (the median of
 * the terrain at its corners and centre), so it is level even on a slope: the low side gets a
 * foundation, the high side is dug out. Its front door faces the nearest street.
 */
final class Buildings {
	/** At most this many corners are sampled for a building's floor level. */
	private static final int HEIGHT_POINTS = 16;
	/** Footprints smaller than this, in square blocks, are sheds and kiosks not worth a building. */
	private static final double MIN_AREA = 12;
	/** A door looks this far for a street, in metres. */
	private static final double DOOR_REACH_METERS = 40;

	/**
	 * Plans already worked out, by building id. A building is planned once, however many chunks it
	 * touches; and the plan depends only on map data and terrain, so reusing it changes nothing.
	 */
	private static final Map<Long, BuildingPlan> PLANS = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(1024, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, BuildingPlan> eldest) {
			return size() > 8192;
		}
	});

	/** Forgets all plans; for a new world, whose projection may differ. */
	static void clearCache() {
		PLANS.clear();
	}

	private Buildings() {
	}

	record Candidate(OsmWater.Building building, int tileX, int tileY, List<double[]> blockRings, List<OsmWater.Way> ways) {
		/** True when this building was planned before, so neither its outline nor its ground need working out again. */
		boolean planned() {
			return PLANS.containsKey(BuildingPlan.idOf(tileX, tileY, building));
		}
	}

	/** Buildings of the given map tiles whose footprint reaches into the chunk (or its one-block border). */
	static List<Candidate> near(TerrainField field, List<int[]> tiles, List<OsmWater.Shapes> shapes, int chunkX, int chunkZ,
		long minSX, long minSY, long maxSX, long maxSY) {
		List<Candidate> out = new ArrayList<>();
		int baseX = chunkX * 16;
		int baseZ = chunkZ * 16;
		for (int t = 0; t < tiles.size(); t++) {
			OsmWater.Shapes s = shapes.get(t);
			for (OsmWater.Building b : s.buildings()) {
				if (b.maxX() < minSX - 2 || b.minX() > maxSX + 2 || b.maxY() < minSY - 2 || b.minY() > maxSY + 2) {
					continue;
				}
				BuildingPlan known = PLANS.get(BuildingPlan.idOf(tiles.get(t)[0], tiles.get(t)[1], b));
				if (known != null) {
					if (known.maxX >= baseX - 1 && known.minX <= baseX + 17 && known.maxZ >= baseZ - 1 && known.minZ <= baseZ + 17) {
						out.add(new Candidate(b, tiles.get(t)[0], tiles.get(t)[1], known.rings, s.ways()));
					}
					continue;
				}
				List<double[]> rings = new ArrayList<>(b.rings().size());
				double x0 = Double.MAX_VALUE;
				double x1 = -Double.MAX_VALUE;
				double z0 = Double.MAX_VALUE;
				double z1 = -Double.MAX_VALUE;
				for (double[] ring : b.rings()) {
					double[] r = new double[ring.length];
					for (int i = 0; i < ring.length; i += 2) {
						double[] p = toBlock(field.projection(), ring[i], ring[i + 1], baseX + 8);
						r[i] = p[0];
						r[i + 1] = p[1];
						x0 = Math.min(x0, p[0]);
						x1 = Math.max(x1, p[0]);
						z0 = Math.min(z0, p[1]);
						z1 = Math.max(z1, p[1]);
					}
					rings.add(r);
				}
				if (x1 < baseX - 1 || x0 > baseX + 17 || z1 < baseZ - 1 || z0 > baseZ + 17) {
					continue;
				}
				out.add(new Candidate(b, tiles.get(t)[0], tiles.get(t)[1], rings, s.ways()));
			}
		}
		return out;
	}

	/** Sample positions whose ground height sets a building's floor: corners, then the centre. */
	static List<double[]> heightPoints(Candidate c) {
		double[] ring = c.building().rings().get(0);
		int n = ring.length / 2;
		int step = Math.max(1, n / HEIGHT_POINTS);
		List<double[]> out = new ArrayList<>();
		double cx = 0;
		double cy = 0;
		for (int i = 0; i < n; i++) {
			cx += ring[i * 2];
			cy += ring[i * 2 + 1];
			if (i % step == 0) {
				out.add(new double[] {ring[i * 2], ring[i * 2 + 1]});
			}
		}
		out.add(new double[] {cx / n, cy / n});
		return out;
	}

	/** World block position of a sample position, in the copy of the world nearest {@code nearX}. */
	static double[] toBlock(Projection projection, double sx, double sy, double nearX) {
		double lat = Mercator.latitudeOfSampleY(sy);
		double lon = Projection.wrapLongitude((sx + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
		double bx = projection.blockX(lat, lon);
		double bz = projection.blockZ(lat);
		double period = projection.periodX();
		if (period > 0) {
			bx += Math.rint((nearX - bx) / period) * period;
		}
		return new double[] {bx, bz};
	}

	/**
	 * Plans the candidates and marks the chunk's columns: each column belongs to the building of its
	 * own map tile that contains it (tiles overlap a little at their edges, and so do their copies of a
	 * building).
	 */
	static void plan(TerrainField field, ChunkTerrain t, List<Candidate> candidates, Map<Long, ProcessedTile> tiles, double[] sx, double[] sy,
		boolean[] onGlobe, int chunkX, int chunkZ, double storeyMeters, int maxFloors) {
		List<BuildingPlan> plans = new ArrayList<>();
		List<Candidate> planned = new ArrayList<>();
		Koppen koppen = Koppen.get();
		long planning = se.itssimple.realearth.Perf.start();
		for (Candidate c : candidates) {
			long cachedId = BuildingPlan.idOf(c.tileX(), c.tileY(), c.building());
			BuildingPlan cached = PLANS.get(cachedId);
			if (cached != null) {
				plans.add(cached);
				planned.add(c);
				continue;
			}
			double area = BuildingPlan.area(c.blockRings());
			if (area < MIN_AREA) {
				continue;
			}
			List<double[]> points = heightPoints(c);
			double[] heights = new double[points.size()];
			int count = 0;
			for (double[] p : points) {
				double h = TerrainField.splineHeight(tiles, p[0], p[1]);
				if (!Double.isNaN(h)) {
					heights[count++] = h;
				}
			}
			if (count == 0) {
				continue;
			}
			Arrays.sort(heights, 0, count);
			double floor = heights[count / 2];
			if (floor < 0.2) {
				continue; // on the water's edge or in the sea: piers and boathouses are left out
			}
			double[] centre = points.get(points.size() - 1);
			double lat = Mercator.latitudeOfSampleY(centre[1]);
			double lon = Projection.wrapLongitude((centre[0] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
			int climate = koppen == null ? 0 : koppen.classAt(lat, lon);
			long id = BuildingPlan.idOf(c.tileX(), c.tileY(), c.building());
			BuildingPlan plan = BuildingPlan.of(id, c.blockRings(), floor, lat, c.building().heightMeters(), area, field, style(climate),
				doors(c, lat), storeyMeters, maxFloors);
			if (!t.degraded) {
				PLANS.put(id, plan);
			}
			plans.add(plan);
			planned.add(c);
		}
		se.itssimple.realearth.Perf.stop("terrain.buildings.plan", planning);
		if (plans.isEmpty()) {
			return;
		}
		long marking = se.itssimple.realearth.Perf.start();

		int n = ChunkTerrain.SPAN;
		int baseX = chunkX * 16;
		int baseZ = chunkZ * 16;
		double perTile = Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		// Each column's own map tile: only that tile's copy of a building may claim it.
		int[] tileX = new int[n * n];
		int[] tileY = new int[n * n];
		for (int k = 0; k < n * n; k++) {
			tileX[k] = Math.floorMod((int) Math.floor((sx[k] + 0.5) / perTile), 1 << OsmWater.ZOOM);
			tileY[k] = (int) Math.floor((sy[k] + 0.5) / perTile);
		}
		// Each footprint filled row by row (even-odd, from the crossings of its edges with the row),
		// over the chunk and its border only.
		double[] cross = new double[64];
		for (int p = 0; p < plans.size(); p++) {
			BuildingPlan plan = plans.get(p);
			Candidate c = planned.get(p);
			int j0 = Math.max(0, (int) Math.floor(plan.minZ - 0.5) - baseZ + 1);
			int j1 = Math.min(n - 1, (int) Math.ceil(plan.maxZ - 0.5) - baseZ + 1);
			for (int j = j0; j <= j1; j++) {
				double z = baseZ + j - 1 + 0.5;
				int count = 0;
				for (double[] r : plan.rings) {
					int m = r.length / 2;
					for (int e = 0, f = m - 1; e < m; f = e++) {
						double za = r[e * 2 + 1];
						double zb = r[f * 2 + 1];
						if ((za > z) != (zb > z)) {
							if (count == cross.length) {
								cross = java.util.Arrays.copyOf(cross, count * 2);
							}
							cross[count++] = r[e * 2] + (z - za) / (zb - za) * (r[f * 2] - r[e * 2]);
						}
					}
				}
				java.util.Arrays.sort(cross, 0, count);
				for (int q = 0; q + 1 < count; q += 2) {
					// Columns whose centre lies in [cross[q], cross[q + 1]).
					int i0 = Math.max(0, (int) Math.ceil(cross[q] - 0.5) - baseX + 1);
					int i1 = Math.min(n - 1, (int) Math.ceil(cross[q + 1] - 0.5) - 1 - baseX + 1);
					for (int i = i0; i <= i1; i++) {
						int k = j * n + i;
						if (!onGlobe[k] || t.building[k] != 0 || tileX[k] != c.tileX() || tileY[k] != c.tileY()) {
							continue;
						}
						t.building[k] = (short) (p + 1);
						t.way[k] = Ways.NONE;
						t.roofY[k] = plan.roofBaseY() + plan.roofRise;
					}
				}
			}
		}
		t.plans = plans;
		se.itssimple.realearth.Perf.stop("terrain.buildings.mark", marking);
	}

	/** The village style for a climate: the same choice as the biome of built-up land. */
	static BuildingPlan.Style style(int c) {
		if (Koppen.desert(c)) {
			return BuildingPlan.Style.DESERT;
		}
		if (Koppen.tropical(c) || (Koppen.arid(c) && Koppen.hotArid(c))) {
			return BuildingPlan.Style.SAVANNA;
		}
		if (Koppen.polar(c) || Koppen.veryCold(c)) {
			return BuildingPlan.Style.SNOWY;
		}
		if (Koppen.subarctic(c)) {
			return BuildingPlan.Style.TAIGA;
		}
		return BuildingPlan.Style.PLAINS;
	}

	/** A long wall gets a door of its own every this many blocks. */
	private static final double WALL_PER_DOOR = 14;
	private static final int MAX_DOORS = 8;

	/**
	 * The doors: the front door in the middle of the outer wall whose middle is nearest a street (or
	 * the longest wall when no street is near), then one in the middle of every other long wall.
	 */
	private static List<int[]> doors(Candidate c, double lat) {
		double[] ring = c.blockRings().get(0);
		double[] sring = c.building().rings().get(0);
		int n = ring.length / 2;
		double spacing = Mercator.sampleSpacingMeters(lat);
		double reach = DOOR_REACH_METERS / spacing;
		int best = -1;
		double bestScore = Double.MAX_VALUE;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double len = Math.hypot(ring[i * 2] - ring[j * 2], ring[i * 2 + 1] - ring[j * 2 + 1]);
			if (len < 3) {
				continue;
			}
			double mx = (sring[i * 2] + sring[j * 2]) / 2;
			double my = (sring[i * 2 + 1] + sring[j * 2 + 1]) / 2;
			double street = reach;
			for (OsmWater.Way w : c.ways()) {
				if (w.kind() == Ways.RAIL || w.kind() == Ways.TRAM || w.kind() == Ways.MOTORWAY || w.bridge()) {
					continue;
				}
				if (w.maxX() < mx - street || w.minX() > mx + street || w.maxY() < my - street || w.minY() > my + street) {
					continue;
				}
				double[] p = w.points();
				for (int q = 0; q + 3 < p.length; q += 2) {
					street = Math.min(street, BuildingPlan.segment(mx, my, p[q], p[q + 1], p[q + 2], p[q + 3]));
				}
			}
			// Nearest street first; without one, the longest wall.
			double score = street < reach ? street : reach + 1000 - len;
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		List<int[]> out = new ArrayList<>();
		if (best < 0) {
			return out;
		}
		int[] front = doorOnEdge(c, best);
		if (front != null) {
			out.add(front);
		}
		for (int i = 0, j = n - 1; i < n && out.size() < MAX_DOORS; j = i++) {
			double len = Math.hypot(ring[i * 2] - ring[j * 2], ring[i * 2 + 1] - ring[j * 2 + 1]);
			if (i != best && len >= WALL_PER_DOOR) {
				int[] d = doorOnEdge(c, i);
				if (d != null) {
					out.add(d);
				}
			}
		}
		return out;
	}

	/** A door in the middle of the wall ending at corner i: the first block in that is in the wall. */
	private static int[] doorOnEdge(Candidate c, int i) {
		double[] ring = c.blockRings().get(0);
		int n = ring.length / 2;
		int j = (i + n - 1) % n;
		double ax = ring[j * 2];
		double az = ring[j * 2 + 1];
		double bx = ring[i * 2];
		double bz = ring[i * 2 + 1];
		double mx = (ax + bx) / 2;
		double mz = (az + bz) / 2;
		double len = Math.hypot(bx - ax, bz - az);
		double nx = -(bz - az) / len;
		double nz = (bx - ax) / len;
		if (!BuildingPlan.contains(c.blockRings(), mx + nx * 0.6, mz + nz * 0.6)) {
			nx = -nx;
			nz = -nz;
		}
		// Opens away from the inside: the larger component of the outward normal.
		int facing = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? 3 : 1) : (nz > 0 ? 0 : 2);
		int[] ox = {0, 1, 0, -1};
		int[] oz = {-1, 0, 1, 0};
		for (double in = 0.1; in < 2.0; in += 0.2) {
			int doorX = (int) Math.floor(mx + nx * in);
			int doorZ = (int) Math.floor(mz + nz * in);
			if (BuildingPlan.contains(c.blockRings(), doorX + 0.5, doorZ + 0.5)
				&& !BuildingPlan.contains(c.blockRings(), doorX + ox[facing] + 0.5, doorZ + oz[facing] + 0.5)) {
				return new int[] {doorX, doorZ, facing};
			}
		}
		return null;
	}
}
