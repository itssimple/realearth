package se.itssimple.realearth.world;

/**
 * Whether the current thread is generating structures for a specific chunk.
 *
 * Biome and height questions are answered exactly (waiting for full-resolution tiles) only then.
 * Everything else that asks -- /locate, explorer maps, the stronghold ring search, which can probe
 * thousands of points across hundreds of kilometres -- gets a fast answer from coarse elevation.
 */
public final class GenContext {
	public static final ThreadLocal<Boolean> EXACT = new ThreadLocal<>();

	private GenContext() {
	}

	public static boolean exact() {
		return Boolean.TRUE.equals(EXACT.get());
	}
}
