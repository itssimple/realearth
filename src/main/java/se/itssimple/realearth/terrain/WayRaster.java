package se.itssimple.realearth.terrain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Draws roads, tracks, paths and railways into a chunk's terrain.
 *
 * A column within half a way's width of its centreline becomes that way, the higher kind winning where
 * ways overlap. Its ground is levelled to the height of the nearest point on the centreline, so roads
 * lie flat across instead of tilting with the hillside -- unless that would cut or fill more than a
 * few metres, where the road simply follows the ground. Railway tracks are traced block by block, so
 * they form one continuous line of rails, on a bed of ballast.
 *
 * Bridges get a deck at the height interpolated between the bridge's two ends, wherever they cross
 * water or a valley. Tunnels are not drawn at all: they are inside the ground.
 */
final class WayRaster {
	/** Most a road levels the ground, up or down. Steeper than this it follows the slope. */
	private static final double MAX_LEVELLING_METERS = 3.0;
	/** Half the width of a railway's ballast bed. */
	private static final double RAIL_BED_HALF_METERS = 1.75;

	private WayRaster() {
	}

	static void apply(TerrainField field, ChunkTerrain t, Map<Long, ProcessedTile> tiles, double[] sx, double[] sy, boolean[] onGlobe,
		List<OsmWater.Way> ways, int chunkX, int chunkZ, boolean roads, boolean rails) {
		int n = ChunkTerrain.SPAN;
		int centre = ChunkTerrain.index(8, 8);
		double minSX = Double.MAX_VALUE;
		double minSY = Double.MAX_VALUE;
		double maxSX = -Double.MAX_VALUE;
		double maxSY = -Double.MAX_VALUE;
		for (int k = 0; k < n * n; k++) {
			if (onGlobe[k]) {
				minSX = Math.min(minSX, sx[k]);
				maxSX = Math.max(maxSX, sx[k]);
				minSY = Math.min(minSY, sy[k]);
				maxSY = Math.max(maxSY, sy[k]);
			}
		}
		if (minSX > maxSX || maxSX - minSX > 1000) {
			return; // off the globe, or straddling the antimeridian
		}
		double lat = t.latitude[centre];
		double spacing = Mercator.sampleSpacingMeters(lat);
		double perMeter = field.projection().blocksPerMeter(lat);

		double[] target = new double[n * n];
		double[] deck = new double[n * n];
		Arrays.fill(target, Double.NaN);
		Arrays.fill(deck, Double.NaN);
		double[] near = new double[5];

		for (OsmWater.Way way : ways) {
			boolean rail = way.kind() == Ways.RAIL || way.kind() == Ways.TRAM;
			if (rail ? !rails : !roads) {
				continue;
			}
			// At least a block wide, wherever the map is shrunk.
			double half = rail ? Math.max(RAIL_BED_HALF_METERS, 1.5 / perMeter) : Math.max(way.widthMeters() / 2, 0.55 / perMeter);
			double margin = half / spacing + 1;
			if (way.maxX() < minSX - margin || way.minX() > maxSX + margin || way.maxY() < minSY - margin || way.minY() > maxSY + margin) {
				continue;
			}
			byte kind = rail ? Ways.RAIL_BED : way.kind();
			double startHeight = Double.NaN;
			double endHeight = Double.NaN;
			if (way.bridge()) {
				double[] p = way.points();
				startHeight = TerrainField.splineHeight(tiles, p[0], p[1]);
				endHeight = TerrainField.splineHeight(tiles, p[p.length - 2], p[p.length - 1]);
			}
			for (int k = 0; k < n * n; k++) {
				if (!onGlobe[k] || kind < t.way[k] && Double.isNaN(deck[k])) {
					continue;
				}
				nearest(way.points(), sx[k], sy[k], near);
				if (near[0] * spacing > half) {
					continue;
				}
				double h = TerrainField.splineHeight(tiles, near[1], near[2]);
				if (way.bridge()) {
					double along = near[4] > 0 ? near[3] / near[4] : 0;
					double d = !Double.isNaN(startHeight) && !Double.isNaN(endHeight) ? startHeight + (endHeight - startHeight) * along : h;
					if (!Double.isNaN(d) && (!Float.isNaN(t.waterMeters[k]) || d > t.groundMeters[k] + 2)) {
						if (kind >= t.deckWay[k]) {
							deck[k] = d;
							t.deckWay[k] = kind;
						}
						continue;
					}
				}
				if (kind >= t.way[k]) {
					t.way[k] = kind;
					target[k] = h;
				}
			}
			if (rail) {
				traceRail(field, t, way, chunkX, chunkZ, deck);
			}
		}

		for (int k = 0; k < n * n; k++) {
			if (!Double.isNaN(deck[k])) {
				t.deckMeters[k] = (float) deck[k];
			}
			if (t.way[k] == Ways.NONE) {
				continue;
			}
			if (!Float.isNaN(t.waterMeters[k])) {
				t.way[k] = Ways.NONE; // no roads through water, only over it
				continue;
			}
			if (!Double.isNaN(target[k]) && Math.abs(target[k] - t.groundMeters[k]) <= MAX_LEVELLING_METERS) {
				t.groundMeters[k] = (float) target[k];
			}
		}
	}

	/**
	 * Nearest point of a polyline to (x, y): out[0] distance, out[1..2] the point, out[3] distance
	 * along the line to it, out[4] the line's length. All in sample units.
	 */
	private static void nearest(double[] p, double x, double y, double[] out) {
		double best = Double.MAX_VALUE;
		double length = 0;
		for (int i = 0; i + 3 < p.length; i += 2) {
			double ax = p[i];
			double ay = p[i + 1];
			double dx = p[i + 2] - ax;
			double dy = p[i + 3] - ay;
			double segment = Math.sqrt(dx * dx + dy * dy);
			double u = segment > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (segment * segment))) : 0;
			double qx = ax + u * dx;
			double qy = ay + u * dy;
			double d = Math.hypot(x - qx, y - qy);
			if (d < best) {
				best = d;
				out[1] = qx;
				out[2] = qy;
				out[3] = length + u * segment;
			}
			length += segment;
		}
		out[0] = best;
		out[4] = length;
	}

	/**
	 * The track itself, traced through the block grid so consecutive rails always share a side and
	 * connect. Done in block coordinates, which the projection gives for each point of the line.
	 */
	private static void traceRail(TerrainField field, ChunkTerrain t, OsmWater.Way way, int chunkX, int chunkZ, double[] deck) {
		Projection projection = field.projection();
		double[] p = way.points();
		int baseX = chunkX * 16;
		int baseZ = chunkZ * 16;
		double centreX = baseX + 8;
		double period = projection.periodX();
		double prevX = Double.NaN;
		double prevZ = Double.NaN;
		for (int i = 0; i + 1 < p.length; i += 2) {
			double lat = Mercator.latitudeOfSampleY(p[i + 1]);
			double lon = Projection.wrapLongitude((p[i] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
			double bx = projection.blockX(lat, lon);
			double bz = projection.blockZ(lat);
			if (period > 0) {
				bx += Math.rint((centreX - bx) / period) * period;
			}
			if (!Double.isNaN(prevX)) {
				traverse(t, prevX - baseX, prevZ - baseZ, bx - baseX, bz - baseZ, way.bridge(), deck);
			}
			prevX = bx;
			prevZ = bz;
		}
	}

	/** Grid traversal (Amanatides and Woo) of one segment in chunk-local block coordinates. */
	private static void traverse(ChunkTerrain t, double x0, double z0, double x1, double z1, boolean bridge, double[] deck) {
		if (Math.max(x0, x1) < -2 || Math.min(x0, x1) > 18 || Math.max(z0, z1) < -2 || Math.min(z0, z1) > 18) {
			return;
		}
		int cx = (int) Math.floor(x0);
		int cz = (int) Math.floor(z0);
		int ex = (int) Math.floor(x1);
		int ez = (int) Math.floor(z1);
		double dx = x1 - x0;
		double dz = z1 - z0;
		int stepX = dx > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = dx != 0 ? Math.abs(1 / dx) : Double.MAX_VALUE;
		double tDeltaZ = dz != 0 ? Math.abs(1 / dz) : Double.MAX_VALUE;
		double tMaxX = dx != 0 ? ((stepX > 0 ? cx + 1 : cx) - x0) / dx : Double.MAX_VALUE;
		double tMaxZ = dz != 0 ? ((stepZ > 0 ? cz + 1 : cz) - z0) / dz : Double.MAX_VALUE;
		for (int guard = 0; guard < 4096; guard++) {
			mark(t, cx, cz, bridge, deck);
			if (cx == ex && cz == ez) {
				break;
			}
			if (tMaxX < tMaxZ) {
				tMaxX += tDeltaX;
				cx += stepX;
			} else {
				tMaxZ += tDeltaZ;
				cz += stepZ;
			}
		}
	}

	private static void mark(ChunkTerrain t, int x, int z, boolean bridge, double[] deck) {
		if (x < -1 || x > 16 || z < -1 || z > 16) {
			return;
		}
		int k = ChunkTerrain.index(x, z);
		if (bridge && !Double.isNaN(deck[k])) {
			t.deckWay[k] = Ways.RAIL;
		} else if (!bridge && Float.isNaN(t.waterMeters[k])) {
			t.way[k] = Ways.RAIL;
		}
	}
}
