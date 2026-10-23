package se.itssimple.realearth.data;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import se.itssimple.realearth.RealEarth;

/**
 * Every download Real Earth makes, counted by where it comes from (elevation, map, building detail,
 * land cover), for {@code /earth perf}: how many, how long they take, retries, rate limits and
 * failures; and how long chunks wait for each kind of data. Also makes sure one resource (a URL and
 * byte range) is downloaded once at a time: whoever asks for it while it is on its way waits for that
 * download instead of starting another.
 */
public final class Net {
	/** One data source's downloads since start or reset. */
	static final class Source {
		final LongAdder requests = new LongAdder();
		final LongAdder shared = new LongAdder();
		final LongAdder attempts = new LongAdder();
		final LongAdder ok = new LongAdder();
		final LongAdder missing = new LongAdder();
		final LongAdder rateLimited = new LongAdder();
		final LongAdder serverErrors = new LongAdder();
		final LongAdder otherErrors = new LongAdder();
		final LongAdder networkErrors = new LongAdder();
		final LongAdder failed = new LongAdder();
		final LongAdder paused = new LongAdder();
		final LongAdder bytes = new LongAdder();
		final LongAdder okNanos = new LongAdder();
		final AtomicLong maxNanos = new AtomicLong();
		final AtomicInteger inFlight = new AtomicInteger();
	}

	/** How long chunks waited for one kind of data, from first being asked for. */
	static final class Wait {
		final LongAdder count = new LongAdder();
		final LongAdder nanos = new LongAdder();
		final AtomicLong maxNanos = new AtomicLong();
		final LongAdder without = new LongAdder();
	}

	/** A download, which may throw like Http does. */
	@FunctionalInterface
	public interface Download<T> {
		T run() throws IOException;
	}

	private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();
	private static final Map<String, Wait> WAITS = new ConcurrentHashMap<>();
	private static final Map<String, ExecutorService> POOLS = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<Object>> RUNNING = new ConcurrentHashMap<>();
	/** The counts at the last minute's summary, to log what happened since. */
	private static final Map<String, long[]> LAST = new ConcurrentHashMap<>();

	private Net() {
	}

	/** Which data source a URL belongs to, by its host. */
	public static String sourceOf(String url) {
		String host;
		try {
			host = URI.create(url).getHost();
		} catch (IllegalArgumentException e) {
			host = null;
		}
		if (host == null) {
			return "other";
		}
		// The elevation tiles are an S3 bucket, named in the path: s3.amazonaws.com/elevation-tiles-prod/...
		if (host.contains("elevation-tiles") || url.contains("/elevation-tiles")) {
			return "elevation";
		}
		if (host.contains("openfreemap")) {
			return "map";
		}
		if (host.startsWith("stac.overturemaps")) {
			return "building catalogue";
		}
		if (host.contains("overturemaps")) {
			return "building detail";
		}
		if (host.contains("worldcover")) {
			return "land cover";
		}
		return host;
	}

	private static Source source(String name) {
		return SOURCES.computeIfAbsent(name, k -> new Source());
	}

	/**
	 * Runs a download of {@code key} (a URL, with its byte range), unless the same one is already on
	 * its way: then waits for that one and gets its result, or its failure.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T once(String key, String sourceName, Download<T> download) throws IOException {
		Source s = source(sourceName);
		s.requests.increment();
		CompletableFuture<Object> mine = new CompletableFuture<>();
		CompletableFuture<Object> running = RUNNING.putIfAbsent(key, mine);
		if (running != null) {
			s.shared.increment();
			try {
				return (T) running.join();
			} catch (CompletionException e) {
				if (e.getCause() instanceof IOException io) {
					throw io;
				}
				throw new IOException(e.getCause());
			}
		}
		s.inFlight.incrementAndGet();
		try {
			T value = download.run();
			mine.complete(value);
			return value;
		} catch (IOException | RuntimeException e) {
			mine.completeExceptionally(e);
			throw e;
		} finally {
			RUNNING.remove(key, mine);
			s.inFlight.decrementAndGet();
		}
	}

	/**
	 * One try at a download: its HTTP status (or -1 for a network error: a timeout, a refused or broken
	 * connection), how long it took, and the bytes received.
	 */
	public static void attempt(String sourceName, int status, long nanos, long bytes) {
		Source s = source(sourceName);
		s.attempts.increment();
		if (status == 200 || status == 206) {
			s.ok.increment();
			s.bytes.add(bytes);
			s.okNanos.add(nanos);
			s.maxNanos.accumulateAndGet(nanos, Math::max);
		} else if (status == 404 || status == 403) {
			s.missing.increment();
		} else if (status == 429) {
			s.rateLimited.increment();
		} else if (status >= 500) {
			s.serverErrors.increment();
		} else if (status < 0) {
			s.networkErrors.increment();
		} else {
			s.otherErrors.increment();
		}
	}

	/** A download given up after all its tries. */
	public static void failed(String sourceName) {
		source(sourceName).failed.increment();
	}

	/** A download not tried because downloads are paused after recent failures. */
	public static void paused(String sourceName) {
		source(sourceName).paused.increment();
	}

	/** A chunk had this kind of data after waiting {@code nanos} since it was first asked for; without it if it timed out or failed. */
	public static void waited(String what, long nanos, boolean without) {
		Wait w = WAITS.computeIfAbsent(what, k -> new Wait());
		w.count.increment();
		w.nanos.add(nanos);
		w.maxNanos.accumulateAndGet(nanos, Math::max);
		if (without) {
			w.without.increment();
		}
	}

	/** A pool of download threads, for its queue in the report. */
	public static void pool(String name, ExecutorService pool) {
		POOLS.put(name, pool);
	}

	public static void reset() {
		SOURCES.clear();
		WAITS.clear();
		LAST.clear();
	}

	/** The report's lines: each source's downloads, the pools' queues, and the chunks' waits. */
	public static List<String> report() {
		List<String> lines = new ArrayList<>();
		new TreeMap<>(SOURCES).forEach((name, s) -> {
			long ok = s.ok.sum();
			StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%s: %d asked", name, s.requests.sum()));
			if (s.shared.sum() > 0) {
				b.append(String.format(Locale.ROOT, " (%d shared a download already on its way)", s.shared.sum()));
			}
			b.append(String.format(Locale.ROOT, ", %d downloaded, %.1f MB", ok, s.bytes.sum() / 1e6));
			if (ok > 0) {
				b.append(String.format(Locale.ROOT, ", %s average, %s slowest", time(s.okNanos.sum() / ok), time(s.maxNanos.get())));
			}
			append(b, s.attempts.sum() - s.requests.sum() + s.shared.sum(), "retries");
			append(b, s.rateLimited.sum(), "rate limited (HTTP 429)");
			append(b, s.serverErrors.sum(), "server errors (5xx)");
			append(b, s.otherErrors.sum(), "other HTTP errors");
			append(b, s.networkErrors.sum(), "timeouts or network errors");
			append(b, s.missing.sum(), "not there (404)");
			append(b, s.failed.sum(), "given up");
			append(b, s.paused.sum(), "skipped while paused after failures");
			append(b, s.inFlight.get(), "on their way now");
			lines.add(b.toString());
		});
		new TreeMap<>(POOLS).forEach((name, pool) -> {
			if (pool instanceof ThreadPoolExecutor t) {
				lines.add(String.format(Locale.ROOT, "%s threads: %d of %d busy, %d waiting for a thread", name, t.getActiveCount(), t.getMaximumPoolSize(),
					t.getQueue().size()));
			}
		});
		new TreeMap<>(WAITS).forEach((what, w) -> {
			long n = w.count.sum();
			if (n > 0) {
				String without = w.without.sum() > 0 ? String.format(Locale.ROOT, ", %d went without", w.without.sum()) : "";
				lines.add(String.format(Locale.ROOT, "chunks waited for %s: %s average, %s longest, over %d chunks%s", what, time(w.nanos.sum() / n),
					time(w.maxNanos.get()), n, without));
			}
		});
		return lines;
	}

	private static void append(StringBuilder b, long n, String what) {
		if (n > 0) {
			b.append(", ").append(n).append(' ').append(what);
		}
	}

	private static String time(long nanos) {
		return nanos >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f s", nanos / 1e9) : String.format(Locale.ROOT, "%d ms", nanos / 1_000_000);
	}

	/**
	 * For the server tick, once a minute: logs a line for each source whose downloads were retried,
	 * rate limited or failed in the last minute, so trouble shows in the log without asking.
	 */
	public static void logTrouble() {
		SOURCES.forEach((name, s) -> {
			long[] now = {s.rateLimited.sum(), s.serverErrors.sum() + s.otherErrors.sum(), s.networkErrors.sum(), s.failed.sum(), s.ok.sum(), s.okNanos.sum()};
			long[] before = LAST.put(name, now);
			if (before == null) {
				before = new long[now.length];
			}
			long limited = now[0] - before[0];
			long errors = now[1] - before[1];
			long network = now[2] - before[2];
			long failed = now[3] - before[3];
			if (limited + errors + network + failed > 0) {
				long ok = now[4] - before[4];
				String average = ok > 0 ? time((now[5] - before[5]) / ok) : "-";
				RealEarth.LOGGER.warn("Downloads of {} in the last minute: {} done ({} average), {} rate limited, {} HTTP errors, {} timeouts or network errors, {} given up",
					name, ok, average, limited, errors, network, failed);
			}
		});
	}
}
