package se.itssimple.realearth.terrain;

/**
 * Real elevation in metres to block Y, and back.
 *
 * The dimension is the tallest the game allows (Y -2032 to 2031) and sea level sits at Y -1200.
 * Between the two knees one block is exactly one metre: by default 600 m of ocean depth and 2,800 m
 * of land. Beyond the knees the scale eases off logarithmically -- with a slope of exactly 1 at the
 * knee, so there is no visible kink -- until Everest just fits under the build limit and the Mariana
 * Trench just fits above bedrock.
 */
public final class VerticalScale {
	public static final int MIN_Y = -2032;
	public static final int HEIGHT = 4064;
	public static final int MAX_Y = MIN_Y + HEIGHT - 1;
	public static final int SEA_LEVEL_Y = -1200;

	/** Highest and lowest points on Earth, rounded outwards. */
	private static final double EVEREST = 8900.0;
	private static final double MARIANA = -11000.0;

	private final double upperKnee;
	private final double upperScale;
	private final double lowerKnee;
	private final double lowerScale;

	public VerticalScale(double compressAboveMeters, double compressBelowMeters) {
		this.upperKnee = Math.min(compressAboveMeters, MAX_Y - 40 - SEA_LEVEL_Y);
		this.lowerKnee = Math.max(compressBelowMeters, MIN_Y + 40 - SEA_LEVEL_Y);
		this.upperScale = solveScale(EVEREST - upperKnee, (MAX_Y - 4) - (SEA_LEVEL_Y + upperKnee));
		this.lowerScale = solveScale(lowerKnee - MARIANA, (SEA_LEVEL_Y + lowerKnee) - (MIN_Y + 8));
	}

	/** Finds A such that A * ln(1 + span / A) == room: the curve that fits span metres into room blocks. */
	private static double solveScale(double span, double room) {
		if (room >= span) {
			return Double.POSITIVE_INFINITY;
		}
		double lo = 1e-3;
		double hi = 1e9;
		for (int i = 0; i < 200; i++) {
			double mid = Math.sqrt(lo * hi);
			if (mid * Math.log1p(span / mid) > room) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return lo;
	}

	private static double ease(double over, double scale) {
		return Double.isInfinite(scale) ? over : scale * Math.log1p(over / scale);
	}

	private static double unease(double eased, double scale) {
		return Double.isInfinite(scale) ? eased : scale * Math.expm1(eased / scale);
	}

	public double toY(double elevation) {
		double y;
		if (elevation > upperKnee) {
			y = SEA_LEVEL_Y + upperKnee + ease(elevation - upperKnee, upperScale);
		} else if (elevation < lowerKnee) {
			y = SEA_LEVEL_Y + lowerKnee - ease(lowerKnee - elevation, lowerScale);
		} else {
			y = SEA_LEVEL_Y + elevation;
		}
		return Math.max(MIN_Y + 6, Math.min(MAX_Y - 2, y));
	}

	public double toElevation(double y) {
		double rel = y - SEA_LEVEL_Y;
		if (rel > upperKnee) {
			return upperKnee + unease(rel - upperKnee, upperScale);
		} else if (rel < lowerKnee) {
			return lowerKnee - unease(lowerKnee - rel, lowerScale);
		}
		return rel;
	}

	public double upperKnee() {
		return upperKnee;
	}
}
