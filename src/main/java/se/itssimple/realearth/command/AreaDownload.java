package se.itssimple.realearth.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Downloads everything an area needs into the cache ahead of time, so it can be played offline or a
 * server can serve it without waiting: full-resolution elevation and the neighbours processing needs,
 * map water, land cover, and the coarser elevation and land cover distant terrain is drawn from.
 *
 * One download runs at a time; it reports progress to whoever started it.
 */
public final class AreaDownload {
	/** About 2 GB of elevation; bigger areas are better done in pieces. */
	public static final int MAX_TILES = 20_000;
	/** Coarsest elevation zoom fetched for distant terrain. */
	private static final int COARSEST_ZOOM = 6;
	private static final int COARSEST_COVER_LEVEL = 6;
	private static final long REPORT_EVERY_MS = 5_000;
	/** Failures in a row after which the network is taken to be down. */
	private static final int GIVE_UP_AFTER = 40;

	private static volatile AreaDownload running;

	private final CommandSourceStack source;
	private final MinecraftServer server;
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final AtomicInteger done = new AtomicInteger();
	private final AtomicInteger downloaded = new AtomicInteger();
	private final AtomicInteger failed = new AtomicInteger();
	private final AtomicInteger failedInRow = new AtomicInteger();
	private ExecutorService pool;
	private int total;

	private AreaDownload(CommandSourceStack source) {
		this.source = source;
		this.server = source.getServer();
	}

	/** One unit of work: fetch something, say what happened. */
	private interface Job extends Supplier<TileStore.Prefetch> {
	}

	public static boolean isRunning() {
		return running != null;
	}

	public static boolean cancel() {
		AreaDownload d = running;
		if (d == null) {
			return false;
		}
		d.cancelled.set(true);
		return true;
	}

	/** Tile counts for an area, for the estimate shown before starting. */
	public record Plan(List<Job> jobs, int elevationTiles) {
	}

	/**
	 * Lists the work for a square of {@code radius} blocks around a block position. The projection is
	 * sampled densely enough that no tile is skipped, even at high latitudes where tiles are small.
	 */
	public static Plan plan(TerrainField field, double centreX, double centreZ, int radius) {
		Projection p = field.projection();
		double north = Math.max(-Mercator.MAX_LATITUDE, Math.min(Mercator.MAX_LATITUDE, p.latitudeAt(centreZ - radius)));
		double south = Math.max(-Mercator.MAX_LATITUDE, Math.min(Mercator.MAX_LATITUDE, p.latitudeAt(centreZ + radius)));
		double worstLat = Math.max(Math.abs(north), Math.abs(south));
		if (Math.signum(north) != Math.signum(south)) {
			worstLat = Math.max(worstLat, 0);
		}
		double tileBlocks = Mercator.sampleSpacingMeters(worstLat) * Mercator.TILE_SIZE * p.blocksPerMeter(worstLat);
		int step = (int) Math.max(8, Math.min(256, tileBlocks / 3));

		Set<Long> z15 = new LinkedHashSet<>();
		List<double[]> coverPoints = new ArrayList<>();
		int coverEvery = Math.max(1, 1000 / step);
		int row = 0;
		for (double z = centreZ - radius; z <= centreZ + radius + step - 1; z += step, row++) {
			double zz = Math.min(z, centreZ + radius);
			double lat = Math.max(-Mercator.MAX_LATITUDE, Math.min(Mercator.MAX_LATITUDE, p.latitudeAt(zz)));
			int col = 0;
			for (double x = centreX - radius; x <= centreX + radius + step - 1; x += step, col++) {
				double xx = Math.min(x, centreX + radius);
				double offset = p.longitudeOffsetAt(xx, lat);
				if (!p.onGlobe(lat, offset)) {
					continue;
				}
				double lon = Projection.wrapLongitude(p.originLongitude() + offset);
				int tx = (int) Math.floor(Mercator.sampleX(lon) / Mercator.TILE_SIZE);
				int ty = (int) Math.floor(Mercator.sampleY(lat) / Mercator.TILE_SIZE);
				// Each tile is processed together with its eight neighbours.
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						z15.add(key(Math.floorMod(tx + dx, Mercator.TILES_PER_AXIS), Math.max(0, Math.min(Mercator.TILES_PER_AXIS - 1, ty + dy))));
					}
				}
				if (row % coverEvery == 0 && col % coverEvery == 0) {
					coverPoints.add(new double[] {lat, lon});
				}
			}
		}
		// The corners of the square, so land cover reaches all the way out.
		for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
			double lat = Math.max(-Mercator.MAX_LATITUDE, Math.min(Mercator.MAX_LATITUDE, p.latitudeAt(centreZ + c[1] * radius)));
			double offset = p.longitudeOffsetAt(centreX + c[0] * radius, lat);
			if (p.onGlobe(lat, offset)) {
				coverPoints.add(new double[] {lat, Projection.wrapLongitude(p.originLongitude() + offset)});
			}
		}

		TileStore store = field.store();
		OsmWater map = field.map();
		WorldCover cover = field.landCover();
		List<Job> jobs = new ArrayList<>();
		for (long k : z15) {
			jobs.add(() -> store.prefetch(Mercator.ZOOM, x(k), y(k)));
		}
		// Coarser elevation: the fallback for missing tiles, and what distant terrain is drawn from.
		for (int zoom = Mercator.ZOOM - 1; zoom >= COARSEST_ZOOM; zoom--) {
			int shift = Mercator.ZOOM - zoom;
			Set<Long> parents = new LinkedHashSet<>();
			for (long k : z15) {
				parents.add(key(x(k) >> shift, y(k) >> shift));
			}
			int z = zoom;
			for (long k : parents) {
				jobs.add(() -> store.prefetch(z, x(k), y(k)));
			}
		}
		if (map != null) {
			Set<Long> mapTiles = new LinkedHashSet<>();
			int shift = Mercator.ZOOM - OsmWater.ZOOM;
			for (long k : z15) {
				mapTiles.add(key(x(k) >> shift, y(k) >> shift));
			}
			for (long k : mapTiles) {
				jobs.add(() -> map.prefetch(x(k), y(k)));
			}
		}
		if (cover != null) {
			for (int level = 0; level <= COARSEST_COVER_LEVEL; level++) {
				int l = level;
				for (double[] point : coverPoints) {
					jobs.add(() -> cover.prefetch(point[0], point[1], l));
				}
			}
		}
		return new Plan(jobs, z15.size());
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int x(long key) {
		return (int) (key >> 32);
	}

	private static int y(long key) {
		return (int) key;
	}

	/**
	 * Plans and downloads an area on a thread of its own (planning a large area takes a moment).
	 * False if another download is already running.
	 */
	public static synchronized boolean start(CommandSourceStack source, Supplier<Plan> planner, String where) {
		if (running != null) {
			return false;
		}
		AreaDownload d = new AreaDownload(source);
		running = d;
		Thread thread = new Thread(() -> {
			Plan plan;
			try {
				plan = planner.get();
			} catch (RuntimeException e) {
				running = null;
				d.tell(Component.literal("Could not plan the download: " + e.getMessage()).withStyle(ChatFormatting.RED));
				return;
			}
			if (plan.elevationTiles > MAX_TILES) {
				running = null;
				d.tell(Component.literal(String.format(Locale.ROOT,
					"That area needs %d elevation tiles (about %d MB); the most one download takes is %d. Choose a smaller radius, or download it in pieces.",
					plan.elevationTiles, estimateMegabytes(plan), MAX_TILES)).withStyle(ChatFormatting.RED));
				return;
			}
			d.total = plan.jobs.size();
			d.tell(Component.literal(String.format(Locale.ROOT, "Downloading %s: %d files, up to about %d MB. /earth download cancel stops it.",
				where, d.total, estimateMegabytes(plan))).withStyle(ChatFormatting.GRAY));
			d.run(plan, where);
		}, "RealEarth-AreaDownload");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	/** Rough size: a zoom-15 elevation tile is up to ~100 kB, and the rest adds about half again. */
	private static long estimateMegabytes(Plan plan) {
		return Math.max(1, Math.round(plan.elevationTiles * 0.1 * 1.5));
	}

	private void run(Plan plan, String where) {
		TerrainField field = RealEarth.terrain();
		int threads = Math.max(1, RealEarth.config().downloadThreads);
		pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "RealEarth-AreaDownload-Worker");
			t.setDaemon(true);
			return t;
		});
		long started = System.currentTimeMillis();
		long lastReport = started;
		String outcome;
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Job job : plan.jobs) {
				futures.add(pool.submit(() -> {
					if (cancelled.get() || RealEarth.terrain() != field) {
						return;
					}
					TileStore.Prefetch result;
					try {
						result = job.get();
					} catch (RuntimeException e) {
						result = TileStore.Prefetch.FAILED;
					}
					switch (result) {
						case DOWNLOADED -> {
							downloaded.incrementAndGet();
							failedInRow.set(0);
						}
						case FAILED -> {
							failed.incrementAndGet();
							if (failedInRow.incrementAndGet() >= GIVE_UP_AFTER) {
								cancelled.set(true);
							}
						}
						default -> failedInRow.set(0);
					}
					done.incrementAndGet();
				}));
			}
			for (Future<?> f : futures) {
				while (!f.isDone()) {
					long now = System.currentTimeMillis();
					if (now - lastReport >= REPORT_EVERY_MS) {
						lastReport = now;
						int percent = (int) (100L * done.get() / Math.max(1, total));
						tell(Component.literal(String.format(Locale.ROOT, "Downloading %s: %d%% (%d of %d files, %d new)",
							where, percent, done.get(), total, downloaded.get())).withStyle(ChatFormatting.GRAY));
					}
					try {
						f.get(250, java.util.concurrent.TimeUnit.MILLISECONDS);
					} catch (java.util.concurrent.TimeoutException ignored) {
						// Keep reporting.
					}
				}
			}
			long seconds = (System.currentTimeMillis() - started) / 1000;
			if (failedInRow.get() >= GIVE_UP_AFTER) {
				outcome = "Download of " + where + " stopped: the data servers cannot be reached. " + downloaded.get()
					+ " files were downloaded first. Run the command again once you are online; finished files are kept.";
			} else if (cancelled.get()) {
				outcome = "Download of " + where + " cancelled after " + done.get() + " of " + total + " files (" + downloaded.get() + " new). Finished files are kept.";
			} else {
				outcome = String.format(Locale.ROOT, "Downloaded %s in %d s: %d new files, %d already cached%s. It can now be generated without a connection.",
					where, seconds, downloaded.get(), done.get() - downloaded.get() - failed.get(),
					failed.get() > 0 ? ", " + failed.get() + " failed (run the command again to retry them)" : "");
			}
		} catch (Exception e) {
			RealEarth.LOGGER.warn("Area download failed", e);
			outcome = "Download of " + where + " failed: " + e.getMessage();
		} finally {
			pool.shutdownNow();
			running = null;
		}
		ChatFormatting colour = failed.get() > 0 || cancelled.get() ? ChatFormatting.GOLD : ChatFormatting.GREEN;
		tell(Component.literal(outcome).withStyle(colour));
	}

	private void tell(Component message) {
		server.execute(() -> source.sendSuccess(() -> message, false));
	}
}
