package se.itssimple.realearth.world;

/**
 * Vertical shift for an ocean monument being built on this thread. Vanilla hardcodes monuments at
 * Y 39 (24 below a sea at 63); on Earth the sea is at Y -1200, so the monument moves down with it.
 */
public final class MonumentHeight {
	public static final int VANILLA_Y = 39;
	public static final ThreadLocal<Integer> OFFSET = ThreadLocal.withInitial(() -> 0);

	private MonumentHeight() {
	}
}
