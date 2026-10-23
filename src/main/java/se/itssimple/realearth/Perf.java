package se.itssimple.realearth;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Time spent in each stage of generating Earth, summed over all threads, for finding what is slow.
 * Costs two clock reads per stage per chunk; always on.
 */
public final class Perf {
	private static final Map<String, LongAdder> NANOS = new ConcurrentHashMap<>();
	private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

	private Perf() {
	}

	public static long start() {
		return System.nanoTime();
	}

	public static void stop(String stage, long started) {
		NANOS.computeIfAbsent(stage, k -> new LongAdder()).add(System.nanoTime() - started);
		COUNTS.computeIfAbsent(stage, k -> new LongAdder()).increment();
	}

	public static void count(String what, long n) {
		COUNTS.computeIfAbsent(what, k -> new LongAdder()).add(n);
	}

	/** How often a stage ran, or the total of a count. */
	public static long total(String what) {
		LongAdder c = COUNTS.get(what);
		return c == null ? 0 : c.sum();
	}

	/** Every stage's total nanoseconds and runs now, for working out what happened since an earlier snapshot. */
	public static Map<String, long[]> snapshot() {
		Map<String, long[]> out = new TreeMap<>();
		COUNTS.forEach((stage, count) -> {
			LongAdder nanos = NANOS.get(stage);
			out.put(stage, new long[] {nanos == null ? -1 : nanos.sum(), count.sum()});
		});
		return out;
	}

	public static void reset() {
		NANOS.clear();
		COUNTS.clear();
	}

	/** One line: each stage's total milliseconds and how often it ran. */
	public static String report() {
		StringBuilder b = new StringBuilder();
		Map<String, LongAdder> counts = new TreeMap<>(COUNTS);
		counts.forEach((stage, count) -> {
			LongAdder nanos = NANOS.get(stage);
			if (!b.isEmpty()) {
				b.append(", ");
			}
			b.append(nanos == null
				? String.format(Locale.ROOT, "%s %d", stage, count.sum())
				: String.format(Locale.ROOT, "%s %.0f ms/%d", stage, nanos.sum() / 1e6, count.sum()));
		});
		return b.toString();
	}
}
