package se.itssimple.realearth.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Bridges as whole structures, with decks you can walk onto: the car game's (GeoWorld's) way of
 * building them, in blocks.
 *
 * <ul>
 * <li><b>Runs.</b> Mappers split a bridge wherever a tag changes, and each piece took the ground under
 * the split for an abutment, so a viaduct stepped up and down mid-span. Bridge ways that meet end to
 * end are joined into one run, carrying on straight where a ramp forks off; the fork then starts from
 * the run's height.</li>
 * <li><b>The deck</b> is the line between the run's two ends, lifted only where something below asks
 * for it: water (the deck clears it by a few blocks), a road or path passing under (headroom), and bare
 * ground above the line (not to be buried). Each demand holds the deck up in a rounded crest that eases
 * back down at {@link #GRADIENT}, so the lift is where it is needed and no further.</li>
 * <li><b>Approaches.</b> Where a crest still holds a run's end above the ground, the roads that meet it
 * ramp up to it at the same gradient, across as many ways as the climb needs.</li>
 * </ul>
 *
 * Everything here is worked out from the map and the elevation alone, so every chunk a bridge touches
 * gets the same deck.
 */
final class Bridges {
	/** Rise per run of a crest's flanks and of the ramps up to a deck: a block up every three or so. */
	static final double GRADIENT = 0.3;
	/** Half the length of a crest's level top, in blocks. */
	private static final double CREST_BLOCKS = 3;
	/** A deck's block stands at least this many blocks above the water's top block. */
	private static final double WATER_CLEARANCE_BLOCKS = 3;
	/** And this many above a way passing under it: three blocks of headroom. */
	private static final double WAY_CLEARANCE_BLOCKS = 4;
	/** Most a deck is lifted above the line between its ends. */
	private static final double MAX_LIFT_BLOCKS = 12;
	/** The ends of a span stand on the ground by definition: neither ground nor ways count there. */
	private static final double ABUTMENT_FRACTION = 0.15;
	private static final double MAX_ABUTMENT_BLOCKS = 8;
	/** Deck heights are worked out about this often along a run, in blocks. */
	private static final double STEP_BLOCKS = 1;
	/** Two way ends this close (in samples) are the same node of the map. */
	private static final double NODE = 1.0 / 32;

	private static final Map<String, double[][]> PROFILES = new ConcurrentHashMap<>();

	private Bridges() {
	}

	/** One bridge: its ways joined end to end, and once worked out, its deck. */
	static final class Run {
		/** Joined centreline in sample coordinates, interleaved x, y, and the arc length at each point. */
		final double[] points;
		final double[] arc;
		final boolean rail;
		/** For each way of the run: where along the run it starts (samples), and whether it runs backwards. */
		final Map<OsmWater.Way, double[]> members = new IdentityHashMap<>();
		/** Deck height (metres) against arc length (samples), every {@link #STEP_BLOCKS} or so. */
		double[] profileArc;
		double[] profileMeters;
		final double minX;
		final double minY;
		final double maxX;
		final double maxY;

		Run(double[] points, boolean rail) {
			this.points = points;
			this.rail = rail;
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

		/** The point at arc length s, into out[0..1]. */
		void pointAt(double s, double[] out) {
			int i = 1;
			while (i < arc.length - 1 && arc[i] < s) {
				i++;
			}
			double span = arc[i] - arc[i - 1];
			double t = span > 0 ? Math.max(0, Math.min(1, (s - arc[i - 1]) / span)) : 0;
			out[0] = points[2 * i - 2] + (points[2 * i] - points[2 * i - 2]) * t;
			out[1] = points[2 * i - 1] + (points[2 * i + 1] - points[2 * i - 1]) * t;
		}

		/** Deck height in metres at arc length s, or NaN before the deck is worked out. */
		double heightAt(double s) {
			if (profileArc == null) {
				return Double.NaN;
			}
			if (s <= 0) {
				return profileMeters[0];
			}
			int n = profileArc.length;
			if (s >= profileArc[n - 1]) {
				return profileMeters[n - 1];
			}
			int i = (int) Math.min(n - 2, Math.max(0, s / profileArc[n - 1] * (n - 1)));
			while (i > 0 && profileArc[i] > s) {
				i--;
			}
			while (i < n - 2 && profileArc[i + 1] < s) {
				i++;
			}
			double t = (s - profileArc[i]) / Math.max(1e-12, profileArc[i + 1] - profileArc[i]);
			return profileMeters[i] + (profileMeters[i + 1] - profileMeters[i]) * t;
		}

		/** Where along the run (samples) one of its ways is {@code along} samples from that way's start. */
		double positionOnWay(OsmWater.Way way, double along, double wayLength) {
			double[] m = members.get(way);
			return m == null ? Double.NaN : m[0] + (m[1] != 0 ? wayLength - along : along);
		}

		/** Deck height in metres where one of its ways is {@code along} samples from that way's start. */
		double heightOnWay(OsmWater.Way way, double along, double wayLength) {
			double s = positionOnWay(way, along, wayLength);
			return Double.isNaN(s) ? Double.NaN : heightAt(s);
		}

		private String key() {
			return points[0] + "," + points[1] + "," + points[points.length - 2] + "," + points[points.length - 1] + "," + members.size() + "," + length();
		}
	}

	/** The bridges and the ramps up to them, for one chunk's ways. */
	static final class Network {
		final Map<OsmWater.Way, Run> runOf = new IdentityHashMap<>();
		/** Height (metres) a ramp must reach at a node, by node key. */
		final Map<Long, Double> anchors = new HashMap<>();
	}

	static long node(double x, double y) {
		return (Math.round(x / NODE) << 32) ^ (Math.round(y / NODE) & 0xFFFFFFFFL);
	}

	private static boolean railFamily(byte kind) {
		return kind == Ways.RAIL || kind == Ways.TRAM;
	}

	/**
	 * Joins the bridge ways into runs. Ways are taken in a fixed order, so every chunk that sees the
	 * same ways makes the same runs.
	 */
	static List<Run> runs(List<OsmWater.Way> ways) {
		List<OsmWater.Way> bridges = new ArrayList<>();
		for (OsmWater.Way w : ways) {
			if (w.bridge() && w.points().length >= 4) {
				bridges.add(w);
			}
		}
		bridges.sort(Comparator.comparingDouble(OsmWater.Way::minX).thenComparingDouble(OsmWater.Way::minY).thenComparingDouble(OsmWater.Way::maxX)
			.thenComparingDouble(OsmWater.Way::maxY).thenComparingInt(w -> w.points().length));
		Map<Long, List<OsmWater.Way>> ends = new HashMap<>();
		for (OsmWater.Way w : bridges) {
			double[] p = w.points();
			ends.computeIfAbsent(node(p[0], p[1]), k -> new ArrayList<>()).add(w);
			ends.computeIfAbsent(node(p[p.length - 2], p[p.length - 1]), k -> new ArrayList<>()).add(w);
		}
		Map<OsmWater.Way, Boolean> used = new IdentityHashMap<>();
		List<Run> runs = new ArrayList<>();
		for (OsmWater.Way first : bridges) {
			if (used.containsKey(first)) {
				continue;
			}
			used.put(first, true);
			boolean rail = railFamily(first.kind());
			// Pieces in order along the run, each with whether it runs backwards.
			ArrayDeque<Object[]> chain = new ArrayDeque<>();
			chain.add(new Object[] {first, false});
			for (int side = 0; side < 2; side++) {
				while (true) {
					Object[] end = side == 0 ? chain.peekLast() : chain.peekFirst();
					OsmWater.Way w = (OsmWater.Way) end[0];
					boolean rev = (Boolean) end[1];
					double[] p = w.points();
					// The run's free end, and the direction it arrives in.
					boolean atStart = side == 0 == rev;
					int a = atStart ? 0 : p.length - 2;
					int b = atStart ? 2 : p.length - 4;
					double dx = p[a] - p[b];
					double dy = p[a + 1] - p[b + 1];
					OsmWater.Way next = null;
					boolean nextRev = false;
					double best = -2;
					for (OsmWater.Way c : ends.getOrDefault(node(p[a], p[a + 1]), List.of())) {
						if (used.containsKey(c) || railFamily(c.kind()) != rail) {
							continue;
						}
						double[] q = c.points();
						boolean fromStart = node(q[0], q[1]) == node(p[a], p[a + 1]);
						int i0 = fromStart ? 0 : q.length - 2;
						int i1 = fromStart ? 2 : q.length - 4;
						double ex = q[i1] - q[i0];
						double ey = q[i1 + 1] - q[i0 + 1];
						// The straightest carrying on wins; the others fork off.
						double straight = (dx * ex + dy * ey) / Math.max(1e-12, Math.hypot(dx, dy) * Math.hypot(ex, ey));
						if (straight > best) {
							best = straight;
							next = c;
							// Walking away from the run's end: forwards if it starts here (on the far side,
							// mirrored).
							nextRev = side == 0 ? !fromStart : fromStart;
						}
					}
					if (next == null) {
						break;
					}
					used.put(next, true);
					if (side == 0) {
						chain.addLast(new Object[] {next, nextRev});
					} else {
						chain.addFirst(new Object[] {next, nextRev});
					}
				}
			}
			// The joined centreline, each way's start along it.
			List<double[]> pts = new ArrayList<>();
			List<double[]> offsets = new ArrayList<>();
			for (Object[] piece : chain) {
				double[] p = ((OsmWater.Way) piece[0]).points();
				boolean rev = (Boolean) piece[1];
				offsets.add(new double[] {pts.size()});
				for (int i = 0; i < p.length / 2; i++) {
					int j = rev ? p.length / 2 - 1 - i : i;
					if (i == 0 && !pts.isEmpty()) {
						continue; // the node it shares with the piece before
					}
					pts.add(new double[] {p[2 * j], p[2 * j + 1]});
				}
			}
			double[] flat = new double[pts.size() * 2];
			for (int i = 0; i < pts.size(); i++) {
				flat[2 * i] = pts.get(i)[0];
				flat[2 * i + 1] = pts.get(i)[1];
			}
			Run run = new Run(flat, rail);
			int index = 0;
			for (Object[] piece : chain) {
				int startPoint = (int) offsets.get(index++)[0];
				// Each piece after the first starts at the point it shares with the one before.
				double start = run.arc[Math.max(0, startPoint == 0 ? 0 : startPoint - 1)];
				run.members.put((OsmWater.Way) piece[0], new double[] {start, (Boolean) piece[1] ? 1 : 0});
			}
			runs.add(run);
		}
		return runs;
	}

	/**
	 * Works out the decks of the runs near a chunk and the ramps up to them. {@code near} tells which
	 * runs matter (those within reach of the chunk); their decks need the elevation along them.
	 */
	static Network network(TerrainField field, Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, List<Run> runs,
		java.util.function.Predicate<Run> near) {
		Network net = new Network();
		// Longest first, so a fork can start from the run it leaves.
		List<Run> wanted = new ArrayList<>();
		for (Run r : runs) {
			if (near.test(r)) {
				wanted.add(r);
			}
		}
		wanted.sort(Comparator.comparingDouble(Run::length).reversed().thenComparingDouble(r -> r.minX).thenComparingDouble(r -> r.minY));
		// A run that ends on another run's span (a ramp forking off it) starts from that run's height,
		// so it waits for that run; otherwise longest first. A cycle of waits is broken longest first.
		Map<Long, List<Run>> through = new HashMap<>();
		for (Run r : wanted) {
			for (int i = 0; i < r.points.length / 2; i++) {
				List<Run> here = through.computeIfAbsent(node(r.points[2 * i], r.points[2 * i + 1]), k -> new ArrayList<>());
				if (!here.contains(r)) {
					here.add(r);
				}
			}
		}
		Map<Long, double[]> onDecks = new HashMap<>();
		List<Run> pending = new ArrayList<>(wanted);
		while (!pending.isEmpty()) {
			Run next = null;
			for (Run r : pending) {
				if (!waits(r, pending, through)) {
					next = r;
					break;
				}
			}
			if (next == null) {
				next = pending.get(0);
			}
			pending.remove(next);
			Run r = next;
			profile(field, tiles, ways, r, onDecks);
			for (int i = 0; i < r.points.length / 2; i++) {
				onDecks.putIfAbsent(node(r.points[2 * i], r.points[2 * i + 1]), new double[] {r.heightAt(r.arc[i])});
			}
			r.members.keySet().forEach(w -> net.runOf.put(w, r));
		}

		// Ramps: from each run end held above the ground, down the roads that meet it.
		Map<Long, List<OsmWater.Way>> ends = new HashMap<>();
		for (OsmWater.Way w : ways) {
			if (!w.bridge() && w.points().length >= 4) {
				double[] p = w.points();
				ends.computeIfAbsent(node(p[0], p[1]), k -> new ArrayList<>()).add(w);
				ends.computeIfAbsent(node(p[p.length - 2], p[p.length - 1]), k -> new ArrayList<>()).add(w);
			}
		}
		ArrayDeque<long[]> queue = new ArrayDeque<>();
		Map<Long, Boolean> railNode = new HashMap<>();
		for (Run r : wanted) {
			for (int end = 0; end < 2; end++) {
				double x = end == 0 ? r.points[0] : r.points[r.points.length - 2];
				double y = end == 0 ? r.points[1] : r.points[r.points.length - 1];
				double h = r.heightAt(end == 0 ? 0 : r.length());
				double g = TerrainField.splineHeight(tiles, x, y);
				if (!Double.isNaN(h) && !Double.isNaN(g) && h > g + 0.25 / field.projection().blocksPerMeter(Mercator.latitudeOfSampleY(y))) {
					long key = node(x, y);
					if (h > net.anchors.getOrDefault(key, Double.NEGATIVE_INFINITY)) {
						net.anchors.put(key, h);
						railNode.put(key, r.rail);
						queue.add(new long[] {key});
					}
				}
			}
		}
		for (int guard = 0; !queue.isEmpty() && guard < 4096; guard++) {
			long key = queue.poll()[0];
			double h = net.anchors.get(key);
			boolean rail = railNode.get(key);
			for (OsmWater.Way w : ends.getOrDefault(key, List.of())) {
				if (railFamily(w.kind()) != rail) {
					continue;
				}
				double[] p = w.points();
				boolean fromStart = node(p[0], p[1]) == key;
				double fx = fromStart ? p[p.length - 2] : p[0];
				double fy = fromStart ? p[p.length - 1] : p[1];
				double meters = 0;
				for (int i = 0; i + 3 < p.length; i += 2) {
					meters += Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
				}
				meters *= Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(fy));
				double far = h - GRADIENT * meters;
				double g = TerrainField.splineHeight(tiles, fx, fy);
				long farKey = node(fx, fy);
				if (!Double.isNaN(g) && far > g && far > net.anchors.getOrDefault(farKey, Double.NEGATIVE_INFINITY) && !onDecks.containsKey(farKey)) {
					net.anchors.put(farKey, far);
					railNode.put(farKey, rail);
					queue.add(new long[] {farKey});
				}
			}
		}
		return net;
	}

	/** Whether an end of {@code r} lies on the span (not an end) of a run still to be laid. */
	private static boolean waits(Run r, List<Run> pending, Map<Long, List<Run>> through) {
		for (int end = 0; end < 2; end++) {
			long key = end == 0 ? node(r.points[0], r.points[1]) : node(r.points[r.points.length - 2], r.points[r.points.length - 1]);
			for (Run o : through.getOrDefault(key, List.of())) {
				if (o != r && pending.contains(o) && key != node(o.points[0], o.points[1])
					&& key != node(o.points[o.points.length - 2], o.points[o.points.length - 1])) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A rounded crest: how much of a lift demanded at one point is still owed {@code distance} away.
	 * Level on top for {@code crest}, easing out to {@code gradient} beyond it and on down to nothing.
	 */
	static double crestLift(double need, double distance, double crest, double gradient) {
		double drop = distance <= crest ? gradient * distance * distance / (2 * crest) : gradient * (distance - crest * 0.5);
		return need - drop;
	}

	private static void profile(TerrainField field, Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, Run r, Map<Long, double[]> onDecks) {
		String key = r.key();
		double[][] cached = PROFILES.get(key);
		if (cached != null) {
			r.profileArc = cached[0];
			r.profileMeters = cached[1];
			return;
		}
		double lat = Mercator.latitudeOfSampleY((r.minY + r.maxY) / 2);
		double spacing = Mercator.sampleSpacingMeters(lat);
		double perMeter = field.projection().blocksPerMeter(lat);
		double blocks = r.length() * spacing * perMeter;
		int n = Math.max(2, (int) Math.ceil(blocks / STEP_BLOCKS) + 1);
		double[] at = new double[2];

		// The ends: where a run starts on another deck (a ramp forking off), that deck's height; else
		// the ground.
		double[] startOnDeck = onDecks.get(node(r.points[0], r.points[1]));
		double[] endOnDeck = onDecks.get(node(r.points[r.points.length - 2], r.points[r.points.length - 1]));
		double entry = startOnDeck != null ? startOnDeck[0] : TerrainField.splineHeight(tiles, r.points[0], r.points[1]);
		double exit = endOnDeck != null ? endOnDeck[0] : TerrainField.splineHeight(tiles, r.points[r.points.length - 2], r.points[r.points.length - 1]);
		if (Double.isNaN(entry) || Double.isNaN(exit)) {
			// No elevation under an end: lay it level with whichever end there is, or not at all.
			double either = Double.isNaN(entry) ? exit : entry;
			if (Double.isNaN(either)) {
				return;
			}
			entry = Double.isNaN(entry) ? either : entry;
			exit = Double.isNaN(exit) ? either : exit;
		}

		double[] arcAt = new double[n];
		double[] line = new double[n];
		double[] need = new double[n];
		double abutment = Math.min(ABUTMENT_FRACTION * r.length(), MAX_ABUTMENT_BLOCKS / perMeter / spacing);
		double waterClear = WATER_CLEARANCE_BLOCKS / perMeter;
		double wayClear = WAY_CLEARANCE_BLOCKS / perMeter;
		double maxLift = MAX_LIFT_BLOCKS / perMeter;
		for (int i = 0; i < n; i++) {
			double s = r.length() * i / (n - 1);
			arcAt[i] = s;
			line[i] = entry + (exit - entry) * i / (n - 1);
			if (i == 0 || i == n - 1) {
				continue;
			}
			r.pointAt(s, at);
			double rise = 0;
			double water = TerrainField.waterSurface(tiles, at[0], at[1]);
			if (!Double.isNaN(water)) {
				rise = Math.max(rise, water + waterClear - line[i]);
			}
			if (s >= abutment && s <= r.length() - abutment) {
				double ground = TerrainField.splineHeight(tiles, at[0], at[1]);
				if (!Double.isNaN(ground)) {
					rise = Math.max(rise, ground - line[i]);
					// A way beneath is laid at its own centreline's height, which can be well off the
					// ground right here: clear that.
					double beneath = wayBeneath(tiles, ways, at[0], at[1], spacing);
					if (!Double.isNaN(beneath)) {
						rise = Math.max(rise, Math.max(ground, beneath) + wayClear - line[i]);
					}
				}
			}
			need[i] = Math.min(maxLift, Math.max(0, rise));
		}

		// Lifted where it is needed and no further: each demand holds the deck up in a rounded crest.
		double crest = CREST_BLOCKS / perMeter;
		double[] meters = new double[n];
		for (int i = 0; i < n; i++) {
			double lift = 0;
			for (int j = 0; j < n; j++) {
				if (need[j] > lift) {
					lift = Math.max(lift, crestLift(need[j], Math.abs(arcAt[i] - arcAt[j]) * spacing, crest, GRADIENT));
				}
			}
			meters[i] = line[i] + lift;
			// An end on another deck is held there: the run leaves that deck at its height and climbs or
			// falls from it no faster than the gradient.
			double fromStart = arcAt[i] * spacing;
			double fromEnd = (r.length() - arcAt[i]) * spacing;
			if (startOnDeck != null) {
				meters[i] = Math.max(entry - GRADIENT * fromStart, Math.min(entry + GRADIENT * fromStart, meters[i]));
			}
			if (endOnDeck != null) {
				meters[i] = Math.max(exit - GRADIENT * fromEnd, Math.min(exit + GRADIENT * fromEnd, meters[i]));
			}
		}
		r.profileArc = arcAt;
		r.profileMeters = meters;
		if (PROFILES.size() > 8192) {
			PROFILES.clear();
		}
		PROFILES.put(key, new double[][] {arcAt, meters});
	}

	/**
	 * The height a way on the ground (not a bridge) passing under (x, y) is laid at -- its centreline's
	 * ground at the nearest point, as {@link WayRaster} levels it -- the highest of them, or NaN for none.
	 */
	private static double wayBeneath(Map<Long, ProcessedTile> tiles, List<OsmWater.Way> ways, double x, double y, double spacing) {
		double highest = Double.NaN;
		for (OsmWater.Way w : ways) {
			if (w.bridge()) {
				continue;
			}
			double half = (w.widthMeters() / 2 + 0.5) / spacing;
			if (x < w.minX() - half || x > w.maxX() + half || y < w.minY() - half || y > w.maxY() + half) {
				continue;
			}
			double[] p = w.points();
			for (int i = 0; i + 3 < p.length; i += 2) {
				double dx = p[i + 2] - p[i];
				double dy = p[i + 3] - p[i + 1];
				double len2 = dx * dx + dy * dy;
				double u = len2 > 0 ? Math.max(0, Math.min(1, ((x - p[i]) * dx + (y - p[i + 1]) * dy) / len2)) : 0;
				if (Math.hypot(x - p[i] - u * dx, y - p[i + 1] - u * dy) <= half) {
					double h = TerrainField.splineHeight(tiles, p[i] + u * dx, p[i + 1] + u * dy);
					if (!Double.isNaN(h) && !(h <= highest)) {
						highest = h;
					}
				}
			}
		}
		return highest;
	}
}
