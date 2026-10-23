package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.Perf;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.Net;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * The running performance log: every {@code perfLogSeconds} (0 is off), a few lines in
 * logs/realearth-perf.log (and at debug level in the game log, not in latest.log) on what the server
 * did since the last ones, for working out why chunks come slowly. Per player on
 * the Earth: where, how fast, and how many chunks within their view are still not generated; then
 * the ticks, the chunks generated and how long they took, Real Earth's stages, downloads and waits.
 * The client writes its own lines (chunks it still lacks, frame rate, sections to build).
 */
public final class PerfLog {
	private static final Map<String, long[]> PERF_BEFORE = new HashMap<>();
	private static final Map<String, long[]> NET_BEFORE = new HashMap<>();
	private static final Map<UUID, double[]> LAST_POSITION = new HashMap<>();
	private static long lastNanos;
	private static java.io.PrintWriter file;
	private static final java.time.format.DateTimeFormatter CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
	/** Watches the server thread while the log is on: see {@link #watch}. */
	private static Thread watchdog;
	private static volatile int lastTick;
	private static volatile long lastTickNanos;

	private PerfLog() {
	}

	/** Seconds between the lines, from the config; changed by /earth perf log. */
	public static int seconds() {
		return Math.max(0, RealEarth.config().perfLogSeconds);
	}

	public static void tick(MinecraftServer server) {
		int every = seconds();
		lastTick = server.getTickCount();
		lastTickNanos = System.nanoTime();
		if (every > 0 && watchdog == null) {
			watch(server);
		}
		if (every <= 0 || server.getTickCount() % (every * 20) != 0) {
			return;
		}
		long now = System.nanoTime();
		double interval = lastNanos == 0 ? every : (now - lastNanos) / 1e9;
		lastNanos = now;
		List<String> lines = new ArrayList<>();

		long[] ticks = server.getTickTimesNanos();
		long sum = 0;
		long max = 0;
		for (long t : ticks) {
			sum += t;
			max = Math.max(max, t);
		}
		ServerLevel earth = RealEarth.earthLevel(server);
		lines.add(String.format(Locale.ROOT, "%.0f s: ticks %.1f ms avg, %.0f ms max (last %d); chunks generated %s (first step to last); %d started and not finished (the ring beyond view included)%s",
			interval, sum / 1e6 / Math.max(1, ticks.length), max / 1e6, ticks.length, ChunkTimes.GENERATION.take(), ChunkTimes.inProgress(),
			earth == null ? "" : String.format(Locale.ROOT, "; Earth has %d chunks loaded, %d chunk tasks pending",
				earth.getChunkSource().getLoadedChunksCount(), earth.getChunkSource().getPendingTasksCount())));

		TerrainField field = RealEarth.terrain();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (earth == null || player.level() != earth) {
				LAST_POSITION.remove(player.getUUID());
				continue;
			}
			double[] last = LAST_POSITION.put(player.getUUID(), new double[] {player.getX(), player.getZ(), now});
			double speed = last == null ? 0 : Math.hypot(player.getX() - last[0], player.getZ() - last[1]) / Math.max(0.001, (now - last[2]) / 1e9);
			String where = "";
			if (field != null) {
				double lat = field.projection().latitudeAt(player.getZ());
				double lon = field.projection().longitudeAt(player.getX(), lat);
				where = String.format(Locale.ROOT, " (%.5f, %.5f)", lat, lon);
			}
			// Chunks within the player's view the server has not finished: generation falling behind.
			int view = Math.min(player.requestedViewDistance(), server.getPlayerList().getViewDistance());
			int cx = player.chunkPosition().x();
			int cz = player.chunkPosition().z();
			int missing = 0;
			int nearest = Integer.MAX_VALUE;
			for (int dz = -view; dz <= view; dz++) {
				for (int dx = -view; dx <= view; dx++) {
					if (earth.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
						missing++;
						nearest = Math.min(nearest, Math.max(Math.abs(dx), Math.abs(dz)));
					}
				}
			}
			int all = (2 * view + 1) * (2 * view + 1);
			lines.add(String.format(Locale.ROOT, "  %s at %d, %d, %d%s, %.1f blocks/s%s, view %d: %d of %d chunks not generated yet%s",
				player.getGameProfile().name(), player.getBlockX(), player.getBlockY(), player.getBlockZ(), where, speed,
				player.isFallFlying() ? " gliding" : "", view, missing, all, missing == 0 ? "" : ", nearest " + nearest + " chunks away"));
		}

		// Real Earth's stages since the last lines (terrain runs include Distant Horizons' near patches).
		Map<String, long[]> perf = Perf.snapshot();
		List<String> stages = new ArrayList<>();
		perf.forEach((stage, v) -> {
			long[] was = PERF_BEFORE.getOrDefault(stage, new long[] {0, 0});
			long runs = v[1] - was[1];
			if (runs > 0 && v[0] >= 0) {
				stages.add(String.format(Locale.ROOT, "%s %.2f ms x%d", stage, (v[0] - was[0]) / 1e6 / runs, runs));
			} else if (runs > 0) {
				stages.add(stage + " " + runs);
			}
		});
		PERF_BEFORE.clear();
		PERF_BEFORE.putAll(perf);
		lines.add("  stages: " + (stages.isEmpty() ? "none" : String.join(", ", stages)));
		for (String line : Net.since(NET_BEFORE)) {
			lines.add("  " + line);
		}
		for (String line : lines) {
			write("[perf] " + line);
		}
	}

	/**
	 * A line of the performance log: to logs/realearth-perf.log, begun afresh each session, and at debug
	 * level to the game's own log. Client and server (in singleplayer one process) share the file.
	 */
	public static synchronized void write(String line) {
		RealEarth.LOGGER.debug(line);
		if (file == null) {
			java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs").resolve("realearth-perf.log");
			try {
				java.nio.file.Files.createDirectories(path.getParent());
				file = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8), true);
				RealEarth.LOGGER.info("Real Earth's performance log is in {}", path);
			} catch (java.io.IOException e) {
				RealEarth.LOGGER.warn("Could not open the performance log {}: {}", path, e.toString());
				return;
			}
		}
		String[] parts = line.split("\\R");
		String stamp = "[" + java.time.LocalTime.now().format(CLOCK) + "] [" + Thread.currentThread().getName() + "] ";
		for (String part : parts) {
			file.println(stamp + part);
		}
	}

	/**
	 * While the log is on, a thread that notices when the server has not finished a tick for five
	 * seconds, and logs what the server thread is doing then, and every thread inside Real Earth's own
	 * code: where a stall comes from. Again every ten seconds while it lasts.
	 */
	private static synchronized void watch(MinecraftServer server) {
		if (watchdog != null) {
			return;
		}
		Thread serverThread = server.getRunningThread();
		watchdog = new Thread(() -> {
			int stalledAt = -1;
			long reported = 0;
			while (!Thread.currentThread().isInterrupted() && server.isRunning()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
				if (seconds() <= 0) {
					continue;
				}
				long quiet = (System.nanoTime() - lastTickNanos) / 1_000_000_000L;
				if (quiet < 5) {
					if (stalledAt >= 0) {
						write("[perf] the server ticks again after tick " + stalledAt);
						stalledAt = -1;
					}
					continue;
				}
				if (stalledAt != lastTick || System.nanoTime() - reported > 10_000_000_000L) {
					stalledAt = lastTick;
					reported = System.nanoTime();
					StringBuilder b = new StringBuilder();
					b.append(String.format(Locale.ROOT, "[perf] the server has not finished a tick for %d s (after tick %d). Server thread:%n", quiet, lastTick));
					stack(b, serverThread);
					Thread.getAllStackTraces().forEach((t, frames) -> {
						if (t != serverThread && t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING || ours(frames) && t != serverThread) {
							if (ours(frames)) {
								b.append(String.format(Locale.ROOT, "Thread %s (%s):%n", t.getName(), t.getState()));
								stack(b, t);
							}
						}
					});
					write(b.toString());
					RealEarth.LOGGER.warn("Real Earth: the server has not finished a tick for {} s; what it is doing is in logs/realearth-perf.log", quiet);
				}
			}
		}, "RealEarth-StallWatch");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	private static boolean ours(StackTraceElement[] frames) {
		for (StackTraceElement f : frames) {
			if (f.getClassName().startsWith("se.itssimple.realearth")) {
				return true;
			}
		}
		return false;
	}

	private static void stack(StringBuilder b, Thread t) {
		StackTraceElement[] frames = t.getStackTrace();
		for (int i = 0; i < Math.min(frames.length, 40); i++) {
			b.append("    at ").append(frames[i]).append(System.lineSeparator());
		}
	}

	public static void clear() {
		synchronized (PerfLog.class) {
			if (file != null) {
				file.close();
				file = null;
			}
		}
		if (watchdog != null) {
			watchdog.interrupt();
			watchdog = null;
		}
		PERF_BEFORE.clear();
		NET_BEFORE.clear();
		LAST_POSITION.clear();
		lastNanos = 0;
		ChunkTimes.clear();
	}
}
