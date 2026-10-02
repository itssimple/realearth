package se.itssimple.realearth.terrain;

import java.util.Arrays;
import java.util.List;
import se.itssimple.realearth.EarthConfig;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Rasterizer;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;

/**
 * One zoom-15 tile after cleaning: despiked, smoothed, with lakes found.
 *
 * Built from the tile AND its eight neighbours, so every filter sees real data across the seams and
 * two neighbouring tiles agree exactly about the samples near their shared edge. That is what keeps
 * terrain continuous: no filter here ever runs out of data at a tile edge.
 *
 * Steps, each on the source grid (2.4 m spacing at 60N, 4.8 m at the equator):
 *
 *  1. Despike. A sample further than {@code despikeMeters} from the median of its 3x3 neighbourhood
 *     is replaced by that median. Catches single-sample pits and towers (data voids, bridges,
 *     masts) that would otherwise become one-block pillars or shafts.
 *  2. Lakes. Lake surfaces are EXACTLY flat in the source data (Siljan reads 160.00 m across the
 *     whole tile; farmland never holds still to 2 cm over 40-80 m). Any window of that size that is
 *     flat marks all its samples as water at that level.
 *  3. Lake depth. Distance from shore, so beds slope down from the waterline instead of the lake
 *     being a one-block-deep puddle.
 *  4. Smoothing. A Gaussian with sigma in METRES (converted per tile, as sample spacing shrinks with
 *     latitude), rounding off creases from upsampled coarse source data and integer-metre terraces.
 *
 * When OpenStreetMap water is available for the whole tile, steps 2-3 work differently: the map
 * says WHERE water is (see {@link OsmWater}), and the level comes from the ground around it -- the
 * lowest shore within reach, which for a river is local, so it can step down its valley, and for a
 * lake spreads over the whole surface. The map is also drawn at four times the sample resolution so
 * shorelines and narrow streams land on the right blocks.
 *
 * The B-spline that turns samples into one-metre blocks is applied later, per chunk.
 */
public final class ProcessedTile {
	public static final int SIZE = TileStore.SIZE;
	/** Neighbour data kept around the tile for the filters. Enough for all four steps. */
	private static final int MARGIN = 48;
	private static final int SPAN = SIZE + 2 * MARGIN;

	/** Smoothed ground height, metres. */
	public final float[] height;
	/** Water surface, metres, or NaN where the sample is not lake. */
	public final float[] lakeLevel;
	/** How far the lake bed lies below {@link #lakeLevel}, metres. */
	public final float[] lakeDepth;
	/**
	 * Where OpenStreetMap puts water, at 4x4 cells per sample (0 dry, 1 inland, 2 sea), or null when
	 * the map was unavailable and water comes from elevation alone.
	 */
	public final byte[] mapWater;

	/**
	 * Built from stand-ins because some data could not be downloaded (see {@link TileStore#isDegraded}).
	 * Set once by {@link TerrainField} before the tile is shared.
	 */
	public boolean degraded;
	public final long createdAt = System.currentTimeMillis();

	public static final int FINE = 4;
	public static final int FINE_SIZE = SIZE * FINE;

	private ProcessedTile(float[] height, float[] lakeLevel, float[] lakeDepth, byte[] mapWater) {
		this.height = height;
		this.lakeLevel = lakeLevel;
		this.lakeDepth = lakeDepth;
		this.mapWater = mapWater;
	}

	/**
	 * @param neighbours raw tiles in row-major 3x3 order, [4] being the tile itself
	 * @param tileY      the tile's row, which sets the latitude and so the sample spacing
	 */
	public static ProcessedTile build(float[][] neighbours, int tileX, int tileY, EarthConfig config, List<OsmWater.Shapes> map) {
		float[] raw = new float[SPAN * SPAN];
		for (int j = 0; j < SPAN; j++) {
			int gy = j - MARGIN;
			int ty = Math.floorDiv(gy, SIZE) + 1;
			int sy = Math.floorMod(gy, SIZE);
			for (int i = 0; i < SPAN; i++) {
				int gx = i - MARGIN;
				int tx = Math.floorDiv(gx, SIZE) + 1;
				int sx = Math.floorMod(gx, SIZE);
				raw[j * SPAN + i] = neighbours[ty * 3 + tx][sy * SIZE + sx];
			}
		}

		double latitude = Mercator.latitudeOfSampleY((double) tileY * SIZE + SIZE / 2.0);
		double spacing = Math.max(0.05, Mercator.sampleSpacingMeters(latitude));

		boolean useMap = map != null && !map.isEmpty() && map.stream().allMatch(OsmWater.Shapes::known);
		boolean[] water;
		byte[] coarse = null;
		byte[] fine = null;
		if (useMap) {
			coarse = new byte[SPAN * SPAN];
			fine = new byte[FINE_SIZE * FINE_SIZE];
			double originX = (double) tileX * SIZE - MARGIN;
			double originY = (double) tileY * SIZE - MARGIN;
			for (OsmWater.Shapes shapes : map) {
				for (OsmWater.Area area : shapes.areas()) {
					byte v = area.sea() ? (byte) 2 : (byte) 1;
					Rasterizer.fill(area.rings(), originX, originY, SPAN, 1, coarse, v);
					Rasterizer.fill(area.rings(), (double) tileX * SIZE, (double) tileY * SIZE, FINE_SIZE, FINE, fine, v);
				}
				for (OsmWater.Line line : shapes.lines()) {
					double radius = line.widthMeters() / 2.0 / spacing;
					Rasterizer.stroke(line.points(), radius, originX, originY, SPAN, 1, coarse, (byte) 1);
					Rasterizer.stroke(line.points(), radius, (double) tileX * SIZE, (double) tileY * SIZE, FINE_SIZE, FINE, fine, (byte) 1);
				}
			}
			water = new boolean[SPAN * SPAN];
			for (int k = 0; k < water.length; k++) {
				water[k] = coarse[k] != 0;
			}
		} else {
			water = config.detectLakes ? findLakes(raw, config.lakeWindowSamples, config.lakeFlatnessMeters) : new boolean[SPAN * SPAN];
			if (config.detectLakes) {
				fixImpossibleWaterLevels(raw, water);
			}
		}

		float[] despiked = despike(raw, (float) config.despikeMeters);
		float[] shoreDistance = distanceToShore(water, Math.max((float) (config.maxLakeDepthMeters / 0.4 / spacing) + 2, useMap ? 40f : 0f));
		float[] mapLevel = useMap ? waterLevels(raw, despiked, coarse, shoreDistance) : null;

		double sigma = config.smoothingMeters / spacing;
		float[] smoothed = sigma > 0.25 ? gaussian(despiked, sigma) : despiked;

		float[] height = new float[SIZE * SIZE];
		float[] level = new float[SIZE * SIZE];
		float[] depth = new float[SIZE * SIZE];
		for (int j = 0; j < SIZE; j++) {
			for (int i = 0; i < SIZE; i++) {
				int src = (j + MARGIN) * SPAN + (i + MARGIN);
				int dst = j * SIZE + i;
				height[dst] = smoothed[src];
				if (water[src]) {
					level[dst] = useMap ? mapLevel[src] : raw[src];
					double meters = shoreDistance[src] * spacing;
					depth[dst] = (float) Math.min(config.maxLakeDepthMeters, 1.0 + 0.4 * meters);
				} else {
					level[dst] = Float.NaN;
				}
			}
		}
		return new ProcessedTile(height, level, depth, fine);
	}

	/**
	 * Water level for every mapped water sample.
	 *
	 *  - The sea is at 0.
	 *  - Where the elevation data is flat on the water (lakes, and river stretches the source
	 *    flattened), that flat value IS the measured surface. It is the same from every tile, so a lake
	 *    crossing tile edges gets one level, and it is spread to the rest of the same water body:
	 *    each sample takes the nearest flat value, so rivers step down where their data steps down.
	 *  - Water with no flat stretch at all (small streams, ditches) stands at the lowest shore it can
	 *    see, where a sample sees as far as it is from the shore.
	 *  - And no water stands more than half a metre above the lowest shore it can see.
	 */
	private static float[] waterLevels(float[] raw, float[] ground, byte[] water, float[] shoreDistance) {
		int n = SPAN * SPAN;
		float[] level = new float[n];
		Arrays.fill(level, Float.POSITIVE_INFINITY);

		int[] queue = new int[n];
		int head = 0;
		int tail = 0;
		for (int j = 2; j < SPAN - 2; j++) {
			for (int i = 2; i < SPAN - 2; i++) {
				int k = j * SPAN + i;
				if (water[k] != 1) {
					continue;
				}
				float lo = raw[k];
				float hi = raw[k];
				for (int dy = -2; dy <= 2; dy++) {
					for (int dx = -2; dx <= 2; dx++) {
						float v = raw[k + dy * SPAN + dx];
						lo = Math.min(lo, v);
						hi = Math.max(hi, v);
					}
				}
				if (hi - lo <= 0.05f) {
					level[k] = raw[k];
					queue[tail++] = k;
				}
			}
		}
		boolean anyFlat = tail > 0;
		while (head < tail) {
			int k = queue[head++];
			int i = k % SPAN;
			int[] around = {i > 0 ? k - 1 : -1, i < SPAN - 1 ? k + 1 : -1, k - SPAN, k + SPAN};
			for (int nb : around) {
				if (nb >= 0 && nb < n && water[nb] == 1 && Float.isInfinite(level[nb])) {
					level[nb] = level[k];
					queue[tail++] = nb;
				}
			}
		}
		if (anyFlat) {
			for (int k = 0; k < n; k++) {
				if (water[k] == 2) {
					level[k] = 0f;
				}
			}
		}

		// Whatever no flat stretch reached: the lowest visible shore.
		float[] shore = new float[n];
		Arrays.fill(shore, Float.POSITIVE_INFINITY);
		for (int j = 0; j < SPAN; j++) {
			for (int i = 0; i < SPAN; i++) {
				int k = j * SPAN + i;
				if (water[k] != 1) {
					continue;
				}
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int ii = i + dx;
						int jj = j + dy;
						if (ii >= 0 && jj >= 0 && ii < SPAN && jj < SPAN && water[jj * SPAN + ii] == 0) {
							shore[k] = Math.min(shore[k], ground[jj * SPAN + ii]);
						}
					}
				}
			}
		}
		float[] fromShore = propagateShore(shore, water, shoreDistance);
		for (int k = 0; k < n; k++) {
			if (water[k] == 2) {
				level[k] = 0f;
			} else if (water[k] == 1) {
				if (Float.isInfinite(level[k])) {
					level[k] = Float.isInfinite(fromShore[k]) ? ground[k] : Math.min(fromShore[k], ground[k] + 0.25f);
				} else if (!Float.isInfinite(fromShore[k])) {
					// Water cannot stand above its lowest shore: it would spill there. Catches surface
					// models that read a bridge deck or a roof beside the water as its level.
					level[k] = Math.min(level[k], fromShore[k] + 0.5f);
				}
			}
		}
		return level;
	}

	private static float[] propagateShore(float[] start, byte[] water, float[] shoreDistance) {
		int n = SPAN * SPAN;
		float[] level = start.clone();
		float[] next = level.clone();
		for (int pass = 1; pass <= 40; pass++) {
			boolean changed = false;
			for (int j = 1; j < SPAN - 1; j++) {
				for (int i = 1; i < SPAN - 1; i++) {
					int k = j * SPAN + i;
					if (water[k] != 1 || shoreDistance[k] + 1.5f < pass) {
						continue;
					}
					float m = level[k];
					m = Math.min(m, water[k - 1] == 1 ? level[k - 1] : m);
					m = Math.min(m, water[k + 1] == 1 ? level[k + 1] : m);
					m = Math.min(m, water[k - SPAN] == 1 ? level[k - SPAN] : m);
					m = Math.min(m, water[k + SPAN] == 1 ? level[k + SPAN] : m);
					if (m < next[k]) {
						next[k] = m;
						changed = true;
					}
				}
			}
			System.arraycopy(next, 0, level, 0, n);
			if (!changed) {
				break;
			}
		}
		return level;
	}

	private static float[] despike(float[] src, float threshold) {
		float[] out = src.clone();
		float[] window = new float[9];
		for (int j = 1; j < SPAN - 1; j++) {
			for (int i = 1; i < SPAN - 1; i++) {
				int n = 0;
				for (int dy = -1; dy <= 1; dy++) {
					int row = (j + dy) * SPAN;
					for (int dx = -1; dx <= 1; dx++) {
						window[n++] = src[row + i + dx];
					}
				}
				Arrays.sort(window);
				float median = window[4];
				float v = src[j * SPAN + i];
				if (Math.abs(v - median) > threshold) {
					out[j * SPAN + i] = median;
				}
			}
		}
		return out;
	}

	/**
	 * Marks every sample covered by a perfectly flat window. Separable: a running min/max along rows,
	 * then along columns, gives each window's range; a second separable pass spreads "flat" from
	 * window centres to everything the window covers, so lakes reach all the way to their shores.
	 */
	private static boolean[] findLakes(float[] raw, int window, double flatness) {
		int half = Math.max(1, window / 2);
		float[] rowMin = new float[SPAN * SPAN];
		float[] rowMax = new float[SPAN * SPAN];
		for (int j = 0; j < SPAN; j++) {
			for (int i = 0; i < SPAN; i++) {
				float lo = Float.POSITIVE_INFINITY;
				float hi = Float.NEGATIVE_INFINITY;
				for (int k = Math.max(0, i - half); k <= Math.min(SPAN - 1, i + half); k++) {
					float v = raw[j * SPAN + k];
					lo = Math.min(lo, v);
					hi = Math.max(hi, v);
				}
				rowMin[j * SPAN + i] = lo;
				rowMax[j * SPAN + i] = hi;
			}
		}

		boolean[] flatCentre = new boolean[SPAN * SPAN];
		for (int j = half; j < SPAN - half; j++) {
			for (int i = half; i < SPAN - half; i++) {
				float lo = Float.POSITIVE_INFINITY;
				float hi = Float.NEGATIVE_INFINITY;
				for (int k = j - half; k <= j + half; k++) {
					lo = Math.min(lo, rowMin[k * SPAN + i]);
					hi = Math.max(hi, rowMax[k * SPAN + i]);
				}
				flatCentre[j * SPAN + i] = hi - lo <= flatness;
			}
		}

		boolean[] rowAny = new boolean[SPAN * SPAN];
		for (int j = 0; j < SPAN; j++) {
			int count = 0;
			for (int k = 0; k <= Math.min(SPAN - 1, half); k++) {
				if (flatCentre[j * SPAN + k]) {
					count++;
				}
			}
			for (int i = 0; i < SPAN; i++) {
				rowAny[j * SPAN + i] = count > 0;
				int leaving = i - half;
				int entering = i + half + 1;
				if (leaving >= 0 && flatCentre[j * SPAN + leaving]) {
					count--;
				}
				if (entering < SPAN && flatCentre[j * SPAN + entering]) {
					count++;
				}
			}
		}

		boolean[] water = new boolean[SPAN * SPAN];
		for (int i = 0; i < SPAN; i++) {
			int count = 0;
			for (int k = 0; k <= Math.min(SPAN - 1, half); k++) {
				if (rowAny[k * SPAN + i]) {
					count++;
				}
			}
			for (int j = 0; j < SPAN; j++) {
				water[j * SPAN + i] = count > 0;
				int leaving = j - half;
				int entering = j + half + 1;
				if (leaving >= 0 && rowAny[leaving * SPAN + i]) {
					count--;
				}
				if (entering < SPAN && rowAny[entering * SPAN + i]) {
					count++;
				}
			}
		}
		return water;
	}

	/**
	 * Water cannot stand higher than its lowest shore -- it would spill out there. Real lakes obey
	 * that in the data (Tahoe, Geneva, Siljan, Mjosa, Hjalmaren: no shore sample more than a metre
	 * below the water). But some sources flattened the SEA to arbitrary heights: in Stockholm's
	 * archipelago the Baltic reads as patches at 4, 9, 15 and 22 m, with 90-98% of their shores
	 * lower than the water. Left alone those become terraces of sea with waterfalls between them.
	 *
	 * So each connected water body is checked: if over 30% of its shore lies more than a metre
	 * below its surface, the surface is lowered to the shore (10th percentile), and to sea level if
	 * that is within 3 m of it. Done on the raw heights, before smoothing, so the wrong level never
	 * bleeds into the coastline.
	 */
	private static void fixImpossibleWaterLevels(float[] raw, boolean[] water) {
		int[] label = new int[SPAN * SPAN];
		Arrays.fill(label, -1);
		int[] queue = new int[SPAN * SPAN];
		int[] members = new int[SPAN * SPAN];
		float[] shoreHeights = new float[SPAN * SPAN];
		int[] offsets = {1, -1, SPAN, -SPAN};
		int next = 0;

		for (int start = 0; start < water.length; start++) {
			if (!water[start] || label[start] >= 0) {
				continue;
			}
			int id = next++;
			int head = 0;
			int tail = 0;
			int count = 0;
			int shoreCount = 0;
			int below = 0;
			queue[tail++] = start;
			label[start] = id;
			while (head < tail) {
				int k = queue[head++];
				members[count++] = k;
				int i = k % SPAN;
				int j = k / SPAN;
				for (int d = 0; d < 4; d++) {
					if ((d == 0 && i == SPAN - 1) || (d == 1 && i == 0) || (d == 2 && j == SPAN - 1) || (d == 3 && j == 0)) {
						continue;
					}
					int n = k + offsets[d];
					if (water[n]) {
						if (label[n] < 0) {
							label[n] = id;
							queue[tail++] = n;
						}
					} else {
						shoreHeights[shoreCount++] = raw[n];
						if (raw[n] < raw[k] - 1.0f) {
							below++;
						}
					}
				}
			}

			if (shoreCount < 8 || below <= 0.3 * shoreCount) {
				continue;
			}
			Arrays.sort(shoreHeights, 0, shoreCount);
			float level = shoreHeights[(int) (0.1 * (shoreCount - 1))];
			if (level < 3.0f) {
				level = 0.0f;
			}
			for (int m = 0; m < count; m++) {
				int k = members[m];
				raw[k] = Math.min(raw[k], level);
			}
		}
	}

	/** Chamfer distance, in samples, from each water sample to the nearest dry one. Capped. */
	private static float[] distanceToShore(boolean[] water, float cap) {
		float[] d = new float[SPAN * SPAN];
		final float diagonal = 1.41421356f;
		for (int k = 0; k < d.length; k++) {
			d[k] = water[k] ? cap : 0f;
		}
		for (int j = 0; j < SPAN; j++) {
			for (int i = 0; i < SPAN; i++) {
				int k = j * SPAN + i;
				if (d[k] == 0f) {
					continue;
				}
				float best = d[k];
				if (i > 0) best = Math.min(best, d[k - 1] + 1f);
				if (j > 0) {
					best = Math.min(best, d[k - SPAN] + 1f);
					if (i > 0) best = Math.min(best, d[k - SPAN - 1] + diagonal);
					if (i < SPAN - 1) best = Math.min(best, d[k - SPAN + 1] + diagonal);
				}
				d[k] = best;
			}
		}
		for (int j = SPAN - 1; j >= 0; j--) {
			for (int i = SPAN - 1; i >= 0; i--) {
				int k = j * SPAN + i;
				if (d[k] == 0f) {
					continue;
				}
				float best = d[k];
				if (i < SPAN - 1) best = Math.min(best, d[k + 1] + 1f);
				if (j < SPAN - 1) {
					best = Math.min(best, d[k + SPAN] + 1f);
					if (i < SPAN - 1) best = Math.min(best, d[k + SPAN + 1] + diagonal);
					if (i > 0) best = Math.min(best, d[k + SPAN - 1] + diagonal);
				}
				d[k] = best;
			}
		}
		return d;
	}

	private static float[] gaussian(float[] src, double sigma) {
		int radius = Math.min(MARGIN - 4, (int) Math.ceil(3.0 * sigma));
		float[] kernel = new float[2 * radius + 1];
		double sum = 0;
		for (int k = -radius; k <= radius; k++) {
			double w = Math.exp(-(k * k) / (2.0 * sigma * sigma));
			kernel[k + radius] = (float) w;
			sum += w;
		}
		for (int k = 0; k < kernel.length; k++) {
			kernel[k] /= (float) sum;
		}

		float[] tmp = new float[SPAN * SPAN];
		for (int j = 0; j < SPAN; j++) {
			for (int i = 0; i < SPAN; i++) {
				double acc = 0;
				for (int k = -radius; k <= radius; k++) {
					int ii = Math.max(0, Math.min(SPAN - 1, i + k));
					acc += kernel[k + radius] * src[j * SPAN + ii];
				}
				tmp[j * SPAN + i] = (float) acc;
			}
		}
		float[] out = new float[SPAN * SPAN];
		for (int j = 0; j < SPAN; j++) {
			for (int i = 0; i < SPAN; i++) {
				double acc = 0;
				for (int k = -radius; k <= radius; k++) {
					int jj = Math.max(0, Math.min(SPAN - 1, j + k));
					acc += kernel[k + radius] * tmp[jj * SPAN + i];
				}
				out[j * SPAN + i] = (float) acc;
			}
		}
		return out;
	}
}
