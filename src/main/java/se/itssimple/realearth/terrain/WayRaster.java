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
 * Bridges are laid on their run's deck (see {@link Bridges}): a deck where it stands a few blocks clear
 * of what is under it, and nearer the ground a road at the deck's own height, on filled ground, so a
 * bridge has no step where it leaves the ground. Roads meeting a deck end held above the ground ramp
 * up to it. Tunnels are not drawn at all: they are inside the ground.
 */
final class WayRaster {
	/** Most a road levels the ground, up or down. Steeper than this it follows the slope. */
	private static final double MAX_LEVELLING_METERS = 3.0;
	/** A bridge column is a deck from this many blocks above the ground; lower, a road on filled ground. */
	private static final double DECK_MIN_BLOCKS = 3;
	/** Half the width of a railway's ballast bed. */
	private static final double RAIL_BED_HALF_METERS = 1.75;

	private WayRaster() {
	}

	static void apply(TerrainField field, ChunkTerrain t, Map<Long, ProcessedTile> tiles, double[] sx, double[] sy, boolean[] onGlobe,
		List<OsmWater.Way> ways, Bridges.Network bridges, int chunkX, int chunkZ, boolean roads, boolean rails) {
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
		// Where the target may fill the ground further than levelling would: under a bridge or a ramp.
		boolean[] raised = new boolean[n * n];
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
			Bridges.Run run = way.bridge() ? bridges.runOf.get(way) : null;
			double[] wp = way.points();
			double rampStart = way.bridge() ? Double.NaN : bridges.anchors.getOrDefault(Bridges.node(wp[0], wp[1]), Double.NaN);
			double rampEnd = way.bridge() ? Double.NaN : bridges.anchors.getOrDefault(Bridges.node(wp[wp.length - 2], wp[wp.length - 1]), Double.NaN);
			for (int k = 0; k < n * n; k++) {
				// A deck competes with decks only: the way on the ground beneath it is no reason to leave a hole.
				if (!onGlobe[k] || (way.bridge() ? kind < t.deckWay[k] : kind < t.way[k] && Double.isNaN(deck[k]))) {
					continue;
				}
				nearest(way.points(), sx[k], sy[k], near);
				if (near[0] * spacing > half) {
					continue;
				}
				double h = TerrainField.splineHeight(tiles, near[1], near[2]);
				boolean lifted = false;
				if (way.bridge()) {
					double d = run == null ? Double.NaN : run.heightOnWay(way, near[3], near[4]);
					// Past the run's own ends the bridge stops: what lies beyond is the road it joins,
					// ramping on from there, not a cap of deck sticking out at the deck's height.
					double s = run == null ? Double.NaN : run.positionOnWay(way, near[3], near[4]);
					if (!Double.isNaN(s) && (s <= 1e-6 || s >= run.length() - 1e-6) && Float.isNaN(t.waterMeters[k])) {
						continue;
					}
					if (!Double.isNaN(d)) {
						if (!Float.isNaN(t.waterMeters[k]) || (d - t.groundMeters[k]) * perMeter >= DECK_MIN_BLOCKS) {
							if (kind >= t.deckWay[k]) {
								deck[k] = d;
								t.deckWay[k] = kind;
							}
							continue;
						}
						h = d;
						lifted = true;
					}
				} else if (!Double.isNaN(rampStart) || !Double.isNaN(rampEnd)) {
					double fromStart = near[3] * spacing;
					double fromEnd = (near[4] - near[3]) * spacing;
					double cone = Math.max(Double.isNaN(rampStart) ? Double.NEGATIVE_INFINITY : rampStart - Bridges.GRADIENT * fromStart,
						Double.isNaN(rampEnd) ? Double.NEGATIVE_INFINITY : rampEnd - Bridges.GRADIENT * fromEnd);
					if (cone > h) {
						h = cone;
						lifted = true;
					}
				}
				// The higher kind wins; between two of a kind, the higher surface, so a ramp isn't buried
				// by the street it joins.
				if (kind > t.way[k] || kind == t.way[k] && !(h <= target[k])) {
					t.way[k] = kind;
					target[k] = h;
					raised[k] = lifted;
				}
			}
			if (rail) {
				// The track's heights as the way's: its deck on a bridge, the ground (and ramps) elsewhere.
				Bridges.Run railRun = run;
				double railRampStart = rampStart;
				double railRampEnd = rampEnd;
				double[] q = new double[5];
				RailTracks.trace(field, t, way, chunkX, chunkZ, deck, false, (px, py) -> {
					nearest(way.points(), px, py, q);
					double h = TerrainField.splineHeight(tiles, q[1], q[2]);
					if (way.bridge()) {
						double d = railRun == null ? Double.NaN : railRun.heightOnWay(way, q[3], q[4]);
						return Double.isNaN(d) ? h : d;
					}
					double fromStart = q[3] * spacing;
					double fromEnd = (q[4] - q[3]) * spacing;
					double cone = Math.max(Double.isNaN(railRampStart) ? Double.NEGATIVE_INFINITY : railRampStart - Bridges.GRADIENT * fromStart,
						Double.isNaN(railRampEnd) ? Double.NEGATIVE_INFINITY : railRampEnd - Bridges.GRADIENT * fromEnd);
					return Math.max(h, cone);
				});
			}
		}
		if (rails) {
			RailTracks.joinEnds(t);
		}

		for (int k = 0; k < n * n; k++) {
			// A deck and a way on the ground in one column, too close for one to pass under the other, are
			// the same surface -- a twin carriageway, or an approach overlapping its bridge's end: one
			// road, at the deck's height.
			if (!Double.isNaN(deck[k]) && t.way[k] != Ways.NONE && !Double.isNaN(target[k]) && Float.isNaN(t.waterMeters[k])
				&& (deck[k] - target[k]) * perMeter < DECK_MIN_BLOCKS) {
				target[k] = Math.max(deck[k], target[k]);
				raised[k] = true;
				t.way[k] = (byte) Math.max(t.way[k], t.deckWay[k]);
				t.deckWay[k] = Ways.NONE;
				deck[k] = Double.NaN;
			}
			if (!Double.isNaN(deck[k])) {
				t.deckMeters[k] = (float) deck[k];
			}
			if (t.way[k] == Ways.NONE) {
				continue;
			}
			if (!Float.isNaN(t.waterMeters[k]) && !Double.isNaN(deck[k])) {
				t.way[k] = Ways.NONE; // under a deck, over water: the deck is the road
				continue;
			}
			if (!Float.isNaN(t.waterMeters[k])) {
				// A way the map draws across the water's edge without calling it a bridge -- a quay, a
				// causeway, an abutment the water polygon overlaps -- is built up out of the water, so
				// the road doesn't break off at the shore.
				double causeway = Math.max(Double.isNaN(target[k]) ? t.waterMeters[k] : target[k], t.waterMeters[k] + 1 / perMeter);
				t.groundMeters[k] = (float) causeway;
				t.waterMeters[k] = Float.NaN;
				t.lake[k] = false;
				continue;
			}
			if (!Double.isNaN(target[k]) && (raised[k] && target[k] >= t.groundMeters[k] || Math.abs(target[k] - t.groundMeters[k]) <= MAX_LEVELLING_METERS)) {
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

}
