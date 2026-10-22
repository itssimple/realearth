package se.itssimple.realearth.terrain;

/**
 * Ground and water for one chunk, plus a one-block border so slopes can be measured at its edges.
 *
 * Indexed by local block coordinates -1..16 on both axes, through {@link #index}.
 */
public final class ChunkTerrain {
	public static final int SPAN = 18;

	/** Ground height in metres above sea level (lake and sea beds included). */
	public final float[] groundMeters = new float[SPAN * SPAN];
	/** Water surface in metres, or NaN where the column is dry. */
	public final float[] waterMeters = new float[SPAN * SPAN];
	/** Y of the topmost solid block. */
	public final int[] topSolidY = new int[SPAN * SPAN];
	/** Y of the topmost water block, or Integer.MIN_VALUE where dry. */
	public final int[] waterTopY = new int[SPAN * SPAN];
	/** True where the water is a lake rather than the sea. */
	public final boolean[] lake = new boolean[SPAN * SPAN];
	public final double[] latitude = new double[SPAN * SPAN];
	public final double[] longitude = new double[SPAN * SPAN];
	/** ESA WorldCover class of each column (see WorldCover), 0 where unknown. */
	public final byte[] cover = new byte[SPAN * SPAN];
	/** Köppen-Geiger climate class of each column (see Koppen), 0 at sea. */
	public final byte[] climate = new byte[SPAN * SPAN];

	public int coverAt(int localX, int localZ) {
		return cover[index(localX, localZ)] & 0xFF;
	}

	public int climateAt(int localX, int localZ) {
		return climate[index(localX, localZ)] & 0xFF;
	}

	/**
	 * Built partly from stand-ins because some data could not be downloaded: elevation, map water or
	 * land cover. Such a chunk is generated anyway, and recorded so it can be repaired later.
	 */
	public boolean degraded;

	/** Road, track, path or railway on the ground of each column (see Ways), NONE for none. */
	public final byte[] way = new byte[SPAN * SPAN];
	/** Way carried on a bridge deck above the column, NONE for none. */
	public final byte[] deckWay = new byte[SPAN * SPAN];
	/** Bridge deck surface in metres, NaN where there is none. */
	public final float[] deckMeters = new float[SPAN * SPAN];
	/** Y of the bridge deck block, or Integer.MIN_VALUE. */
	public final int[] deckY = new int[SPAN * SPAN];

	/** Buildings reaching into this chunk; {@link #building} indexes it from 1. */
	public volatile java.util.List<BuildingPlan> plans = java.util.List.of();
	/** Which building stands on each column, 1-based into {@link #plans}; 0 for none. */
	public final short[] building = new short[SPAN * SPAN];
	/** Top of the building's roof on each column, for distant terrain; meaningless where there is none. */
	public final int[] roofY = new int[SPAN * SPAN];
	/** Named streets near this chunk, for the signs at its kerbs. */
	public volatile java.util.List<se.itssimple.realearth.osm.OsmWater.Street> streets = java.util.List.of();
	/** Beds built in this chunk, as world x, y, z of the foot: where villagers move in. */
	public volatile java.util.List<int[]> beds = java.util.List.of();

	public BuildingPlan buildingAt(int localX, int localZ) {
		int b = building[index(localX, localZ)];
		return b == 0 ? null : plans.get(b - 1);
	}

	{
		java.util.Arrays.fill(deckMeters, Float.NaN);
		java.util.Arrays.fill(deckY, Integer.MIN_VALUE);
	}
	public final long createdAt = System.currentTimeMillis();

	/** Per-chunk biome answers, filled in lazily by the biome source. */
	public volatile Object biomeCache;

	public static int index(int localX, int localZ) {
		return (localZ + 1) * SPAN + (localX + 1);
	}

	public boolean hasWater(int localX, int localZ) {
		return waterTopY[index(localX, localZ)] != Integer.MIN_VALUE;
	}

	/** Steepest drop to a neighbour, in blocks. */
	public int slope(int localX, int localZ) {
		int here = topSolidY[index(localX, localZ)];
		int s = 0;
		s = Math.max(s, Math.abs(here - topSolidY[index(localX - 1, localZ)]));
		s = Math.max(s, Math.abs(here - topSolidY[index(localX + 1, localZ)]));
		s = Math.max(s, Math.abs(here - topSolidY[index(localX, localZ - 1)]));
		s = Math.max(s, Math.abs(here - topSolidY[index(localX, localZ + 1)]));
		return s;
	}

	/** True if any neighbour holds water: the column is on a shore. */
	public boolean nextToWater(int localX, int localZ) {
		return hasWater(localX - 1, localZ) || hasWater(localX + 1, localZ)
			|| hasWater(localX, localZ - 1) || hasWater(localX, localZ + 1);
	}
}
