package se.itssimple.realearth.world;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long Earth's chunks take through Minecraft's generation, from their first step (structure
 * starts) to their last (the first mobs), and then, in singleplayer where the client shares the
 * process, until they reach the client. For the running log (see PerfLog).
 */
public final class ChunkTimes {
	/** Chunks forgotten this long after starting: unloaded half done, or never sent. */
	private static final long FORGET_NANOS = 10L * 60 * 1_000_000_000L;

	/** First step, by chunk. */
	private static final Map<Long, Long> STARTED = new ConcurrentHashMap<>();
	/** First step and last, by chunk, until the client has it. */
	private static final Map<Long, long[]> GENERATED = new ConcurrentHashMap<>();

	/** Times over an interval: how many, their total and the longest. */
	public static final class Window {
		private long count;
		private long nanos;
		private long max;

		synchronized void add(long n) {
			count++;
			nanos += n;
			max = Math.max(max, n);
		}

		/** "132, 1.2 s avg, 6.1 s max", and starts over. */
		public synchronized String take() {
			String out = count == 0 ? "0" : String.format(Locale.ROOT, "%d, %s avg, %s max", count, time(nanos / count), time(max));
			count = 0;
			nanos = 0;
			max = 0;
			return out;
		}
	}

	/** First generation step to last. */
	public static final Window GENERATION = new Window();
	/** First generation step to arriving on the client. */
	public static final Window TO_CLIENT = new Window();
	/** Last generation step to arriving on the client: sending. */
	public static final Window SENDING = new Window();
	/** Chunks arriving on the client that were not generated in this session: read from disk. */
	private static long fromDisk;

	private ChunkTimes() {
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static void started(int x, int z) {
		STARTED.putIfAbsent(key(x, z), System.nanoTime());
	}

	public static void generated(int x, int z) {
		Long start = STARTED.remove(key(x, z));
		if (start == null) {
			return;
		}
		long now = System.nanoTime();
		GENERATION.add(now - start);
		GENERATED.put(key(x, z), new long[] {start, now});
	}

	/** The client has loaded a chunk. */
	public static void arrived(int x, int z) {
		long[] t = GENERATED.remove(key(x, z));
		if (t == null) {
			synchronized (ChunkTimes.class) {
				fromDisk++;
			}
			return;
		}
		long now = System.nanoTime();
		TO_CLIENT.add(now - t[0]);
		SENDING.add(now - t[1]);
	}

	/** Chunks arriving on the client since the last call that were read from disk rather than generated now. */
	public static synchronized long takeFromDisk() {
		long n = fromDisk;
		fromDisk = 0;
		return n;
	}

	/** Chunks started and not finished yet; and forgets the ones that never will. */
	public static int inProgress() {
		long now = System.nanoTime();
		STARTED.values().removeIf(t -> now - t > FORGET_NANOS);
		GENERATED.values().removeIf(t -> now - t[1] > FORGET_NANOS);
		return STARTED.size();
	}

	public static void clear() {
		STARTED.clear();
		GENERATED.clear();
	}

	static String time(long nanos) {
		return nanos >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f s", nanos / 1e9) : String.format(Locale.ROOT, "%d ms", nanos / 1_000_000);
	}
}
