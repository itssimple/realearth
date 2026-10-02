package se.itssimple.realearth.geo;

/**
 * Web Mercator sample coordinates at the Terrarium zoom.
 *
 * Positions are expressed in the GLOBAL sample grid: one unbroken grid of 256 * 2^zoom samples per
 * axis spanning every tile, so sampling never has to care where tile edges are. Terrarium samples
 * sit at pixel centres, so sample i covers [i, i+1) and is measured at i + 0.5. Adjacent tiles do
 * NOT share edge samples; treating them as if they did is what cracks terrain at tile seams.
 */
public final class Mercator {
	public static final int ZOOM = 15;
	public static final int TILE_SIZE = 256;
	public static final int TILES_PER_AXIS = 1 << ZOOM;
	public static final long SAMPLES_PER_AXIS = (long) TILE_SIZE << ZOOM;
	public static final double MAX_LATITUDE = 85.0511287798066;
	private static final double EQUATOR_METERS = 2.0 * Math.PI * 6_378_137.0;

	private Mercator() {
	}

	/** Continuous sample coordinate of a longitude (sample centres at integers). */
	public static double sampleX(double longitude) {
		return (longitude + 180.0) / 360.0 * SAMPLES_PER_AXIS - 0.5;
	}

	/** Continuous sample coordinate of a latitude (sample centres at integers). */
	public static double sampleY(double latitude) {
		double lat = Math.toRadians(Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude)));
		return (1.0 - asinh(Math.tan(lat)) / Math.PI) / 2.0 * SAMPLES_PER_AXIS - 0.5;
	}

	/** Latitude of the centre of a sample row. */
	public static double latitudeOfSampleY(double sampleY) {
		double n = Math.PI * (1.0 - 2.0 * (sampleY + 0.5) / SAMPLES_PER_AXIS);
		return Math.toDegrees(Math.atan(Math.sinh(n)));
	}

	/** Ground distance between adjacent samples at a latitude, in metres. */
	public static double sampleSpacingMeters(double latitude) {
		return Math.cos(Math.toRadians(latitude)) * EQUATOR_METERS / SAMPLES_PER_AXIS;
	}

	private static double asinh(double v) {
		return Math.log(v + Math.sqrt(v * v + 1.0));
	}
}
