package se.itssimple.realearth.world;

/**
 * Smooth pseudo-random fields over the globe, for variety the elevation data cannot provide (how
 * wet a region is, which forest grows where).
 *
 * Sampled in 3D on the unit sphere rather than on latitude/longitude, so there is no seam at the
 * antimeridian and no pinching at the poles. Deterministic per place: the same spot on Earth gets
 * the same climate in every world.
 */
public final class GeoNoise {
	private static final int[] PERM = new int[512];

	static {
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = i;
		}
		long seed = 0x5EED_EA27L;
		for (int i = 255; i > 0; i--) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			int j = (int) ((seed >>> 33) % (i + 1));
			int tmp = p[i];
			p[i] = p[j];
			p[j] = tmp;
		}
		for (int i = 0; i < 512; i++) {
			PERM[i] = p[i & 255];
		}
	}

	private GeoNoise() {
	}

	/**
	 * Fractal noise in roughly [-1, 1].
	 *
	 * @param featureKm size of the largest features
	 * @param salt      decorrelates independent fields
	 */
	public static double sample(double latitude, double longitude, double featureKm, int salt) {
		double lat = Math.toRadians(latitude);
		double lon = Math.toRadians(longitude);
		double frequency = 6371.0 / featureKm;
		double x = Math.cos(lat) * Math.cos(lon) * frequency + salt * 31.7;
		double y = Math.cos(lat) * Math.sin(lon) * frequency - salt * 17.3;
		double z = Math.sin(lat) * frequency + salt * 11.1;
		double sum = 0;
		double amplitude = 1;
		double norm = 0;
		for (int octave = 0; octave < 3; octave++) {
			sum += amplitude * perlin(x, y, z);
			norm += amplitude;
			amplitude *= 0.5;
			x *= 2.03;
			y *= 2.03;
			z *= 2.03;
		}
		return Math.max(-1, Math.min(1, sum / norm * 1.6));
	}

	private static double perlin(double x, double y, double z) {
		int xi = (int) Math.floor(x) & 255;
		int yi = (int) Math.floor(y) & 255;
		int zi = (int) Math.floor(z) & 255;
		x -= Math.floor(x);
		y -= Math.floor(y);
		z -= Math.floor(z);
		double u = fade(x);
		double v = fade(y);
		double w = fade(z);
		int a = PERM[xi] + yi;
		int aa = PERM[a] + zi;
		int ab = PERM[a + 1] + zi;
		int b = PERM[xi + 1] + yi;
		int ba = PERM[b] + zi;
		int bb = PERM[b + 1] + zi;
		return lerp(w,
			lerp(v, lerp(u, grad(PERM[aa], x, y, z), grad(PERM[ba], x - 1, y, z)),
				lerp(u, grad(PERM[ab], x, y - 1, z), grad(PERM[bb], x - 1, y - 1, z))),
			lerp(v, lerp(u, grad(PERM[aa + 1], x, y, z - 1), grad(PERM[ba + 1], x - 1, y, z - 1)),
				lerp(u, grad(PERM[ab + 1], x, y - 1, z - 1), grad(PERM[bb + 1], x - 1, y - 1, z - 1))));
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	private static double grad(int hash, double x, double y, double z) {
		int h = hash & 15;
		double u = h < 8 ? x : y;
		double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
		return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
	}

	/** A cheap per-block hash in [0, 1), for dithering material boundaries. */
	public static double hash(int x, int y, int z) {
		long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}
}
