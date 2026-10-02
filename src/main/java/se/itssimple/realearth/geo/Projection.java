package se.itssimple.realearth.geo;

/**
 * Block coordinates to latitude/longitude and back. North is -Z, as in Minecraft.
 *
 * A sphere cannot go onto a plane without distortion, so each projection chooses which kind:
 *
 * - {@link WebMercator} (the default for new worlds) is conformal: north is up everywhere and shapes
 *   are true everywhere, but scale grows away from the equator, so one block is one metre only at
 *   one chosen latitude. The vertical scale follows the horizontal one, so mountains keep their
 *   proportions. It is the projection of web maps, and of the elevation tiles themselves.
 * - {@link Sinusoidal} (worlds made before Mercator existed) keeps one block per metre everywhere,
 *   but north-south lines lean further and further from the origin's meridian.
 */
public sealed interface Projection permits Projection.Sinusoidal, Projection.WebMercator {
	double EARTH_RADIUS_METERS = 6_371_008.8;
	double METERS_PER_DEGREE = Math.PI * EARTH_RADIUS_METERS / 180.0;

	double originLatitude();

	double originLongitude();

	/** Latitude at block Z. */
	double latitudeAt(double z);

	/** Longitude east of the origin's meridian at block X, for a known latitude. Unwrapped. */
	double longitudeOffsetAt(double x, double latitude);

	/** True when the point lies on the mapped globe rather than beyond its edges. */
	boolean onGlobe(double latitude, double longitudeOffset);

	double blockZ(double latitude);

	double blockX(double latitude, double longitude);

	/**
	 * Blocks per real metre at a latitude, horizontally and vertically alike. 1 everywhere for the
	 * sinusoidal projection.
	 */
	double blocksPerMeter(double latitude);

	/**
	 * How far north-south lines lean at a place, as the tangent of the angle: 0 means true north is
	 * straight up. Always 0 for Mercator.
	 */
	double shear(double latitude, double longitude);

	/** Short name for messages and the world settings file. */
	String id();

	default double longitudeAt(double x, double latitude) {
		return wrapLongitude(originLongitude() + longitudeOffsetAt(x, latitude));
	}

	static double wrapLongitude(double lon) {
		double wrapped = (lon + 180.0) % 360.0;
		if (wrapped < 0) {
			wrapped += 360.0;
		}
		return wrapped - 180.0;
	}

	/**
	 * Sinusoidal, centred on an origin, at one block per metre:
	 *
	 *   z = (originLat - lat) * metresPerDegree
	 *   x = (lon - originLon) * metresPerDegree * cos(lat)
	 *
	 * Every parallel is drawn at its true length, so east-west distances are exact everywhere, and the
	 * origin's meridian is exact north-south. Away from that meridian north-south lines lean: about 11
	 * degrees at 15 degrees of longitude away at 50N, over 50 degrees on the far side of the world.
	 */
	record Sinusoidal(double originLatitude, double originLongitude) implements Projection {
		@Override
		public double latitudeAt(double z) {
			return originLatitude - z / METERS_PER_DEGREE;
		}

		@Override
		public double longitudeOffsetAt(double x, double latitude) {
			double cos = Math.cos(Math.toRadians(latitude));
			return x / (METERS_PER_DEGREE * Math.max(cos, 1e-9));
		}

		@Override
		public boolean onGlobe(double latitude, double longitudeOffset) {
			return latitude > -90.0 && latitude < 90.0 && Math.abs(longitudeOffset) <= 180.0;
		}

		@Override
		public double blockZ(double latitude) {
			return (originLatitude - latitude) * METERS_PER_DEGREE;
		}

		@Override
		public double blockX(double latitude, double longitude) {
			double offset = wrapLongitude(longitude - originLongitude);
			return offset * METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude));
		}

		@Override
		public double blocksPerMeter(double latitude) {
			return 1.0;
		}

		@Override
		public double shear(double latitude, double longitude) {
			return Math.abs(Math.toRadians(wrapLongitude(longitude - originLongitude)) * Math.sin(Math.toRadians(latitude)));
		}

		@Override
		public String id() {
			return "sinusoidal";
		}
	}

	/**
	 * Spherical Mercator with 0, 0 at block 0, 0 -- the web-map projection -- scaled so that one block
	 * is one metre at {@code scaleLatitude}:
	 *
	 *   x = s * R * lon
	 *   z = -s * R * ln(tan(pi/4 + lat/2))          where s = cos(scaleLatitude)
	 *
	 * Elsewhere a block is cos(lat) / s metres: smaller towards the poles, larger towards the equator.
	 * The change is slow (about 3 % per 100 km at 60N), so it cannot be seen locally.
	 *
	 * East-west the map repeats: past 180 degrees the world carries on into the other side of the
	 * globe, so one can fly all the way round. North-south it ends where the elevation data ends,
	 * at about 85 degrees.
	 */
	record WebMercator(double scaleLatitude) implements Projection {
		private double s() {
			return Math.cos(Math.toRadians(scaleLatitude));
		}

		@Override
		public double originLatitude() {
			return 0.0;
		}

		@Override
		public double originLongitude() {
			return 0.0;
		}

		@Override
		public double latitudeAt(double z) {
			double psi = -z / (s() * EARTH_RADIUS_METERS);
			return Math.toDegrees(2.0 * Math.atan(Math.exp(psi)) - Math.PI / 2.0);
		}

		@Override
		public double longitudeOffsetAt(double x, double latitude) {
			return Math.toDegrees(x / (s() * EARTH_RADIUS_METERS));
		}

		@Override
		public boolean onGlobe(double latitude, double longitudeOffset) {
			return Math.abs(latitude) < Mercator.MAX_LATITUDE;
		}

		@Override
		public double blockZ(double latitude) {
			double phi = Math.toRadians(Math.max(-89.999, Math.min(89.999, latitude)));
			return -s() * EARTH_RADIUS_METERS * Math.log(Math.tan(Math.PI / 4.0 + phi / 2.0));
		}

		@Override
		public double blockX(double latitude, double longitude) {
			return s() * EARTH_RADIUS_METERS * Math.toRadians(wrapLongitude(longitude));
		}

		@Override
		public double blocksPerMeter(double latitude) {
			double lat = Math.max(-Mercator.MAX_LATITUDE, Math.min(Mercator.MAX_LATITUDE, latitude));
			return s() / Math.cos(Math.toRadians(lat));
		}

		@Override
		public double shear(double latitude, double longitude) {
			return 0.0;
		}

		@Override
		public String id() {
			return "mercator";
		}
	}
}
