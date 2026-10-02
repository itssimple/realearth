package se.itssimple.realearth.data;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import se.itssimple.realearth.RealEarth;

/**
 * Keeps the download cache under a size limit. Everything in it can be downloaded again, so when it
 * grows past the limit the files used longest ago are deleted until it is back under 90 % of it.
 *
 * "Used" is the file's modification time: files are touched when they are read (at most every few
 * hours, so reading stays cheap), which turns it into a last-used time that works the same on every
 * file system -- unlike access times, which Windows and many Linux mounts do not keep.
 *
 * The total is counted once at start-up and then kept up to date from the writes, so the cache is
 * only walked again when it actually needs trimming.
 */
public final class DiskCache {
	private static final long TOUCH_AFTER_MS = 6 * 3600_000L;
	/** Files this recent are never trimmed: they are being used right now. */
	private static final long KEEP_RECENT_MS = 10 * 60_000L;

	private static volatile DiskCache current;

	private final Path root;
	private final long limitBytes;
	private final AtomicLong total = new AtomicLong(-1);
	private final AtomicBoolean busy = new AtomicBoolean();
	private final ExecutorService worker;

	private DiskCache(Path root, long limitBytes) {
		this.root = root;
		this.limitBytes = limitBytes;
		this.worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "RealEarth-Cache");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	/** Starts managing the cache under {@code root}. A limit of 0 or less means unlimited. */
	public static synchronized void install(Path root, long limitBytes) {
		uninstall();
		DiskCache cache = new DiskCache(root, limitBytes);
		current = cache;
		cache.worker.execute(() -> {
			cache.total.set(cache.measure().bytes());
			cache.trimIfNeeded();
		});
	}

	public static synchronized void uninstall() {
		DiskCache cache = current;
		current = null;
		if (cache != null) {
			cache.worker.shutdownNow();
		}
	}

	public static DiskCache current() {
		return current;
	}

	/** A cache that nothing writes to on its own, for checking trimming on a folder of one's choosing. */
	public static DiskCache unmanaged(Path root, long limitBytes) {
		return new DiskCache(root, limitBytes);
	}

	/** Trims now, on the calling thread, if over the limit. */
	public void trimNow() {
		trim();
		worker.shutdown();
	}

	/** Note a file just written to the cache. */
	public static void written(long bytes) {
		DiskCache cache = current;
		if (cache != null && cache.total.get() >= 0) {
			cache.total.addAndGet(bytes);
			cache.trimIfNeeded();
		}
	}

	/** Note a cache file just read, so it counts as recently used. */
	public static void used(Path file) {
		try {
			long now = System.currentTimeMillis();
			if (now - Files.getLastModifiedTime(file).toMillis() > TOUCH_AFTER_MS) {
				Files.setLastModifiedTime(file, FileTime.fromMillis(now));
			}
		} catch (IOException ignored) {
			// Gone or locked: nothing to remember.
		}
	}

	public Path root() {
		return root;
	}

	public long limitBytes() {
		return limitBytes;
	}

	/** Size of one part of the cache. */
	public record Part(String name, long bytes, long files) {
	}

	public record Usage(long bytes, long files, List<Part> parts) {
	}

	/** Walks the cache and adds it up, per top-level folder. Blocking; call it off the server thread. */
	public Usage measure() {
		Map<String, long[]> parts = new LinkedHashMap<>();
		long[] sum = new long[2];
		if (Files.isDirectory(root)) {
			try {
				Files.walkFileTree(root, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						Path rel = root.relativize(file);
						String part = rel.getNameCount() > 1 ? rel.getName(0).toString() : "(other)";
						long[] p = parts.computeIfAbsent(part, k -> new long[2]);
						p[0] += attrs.size();
						p[1]++;
						sum[0] += attrs.size();
						sum[1]++;
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException e) {
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				RealEarth.LOGGER.warn("Could not measure the cache in {}: {}", root, e.toString());
			}
		}
		List<Part> list = new ArrayList<>();
		parts.forEach((name, p) -> list.add(new Part(name, p[0], p[1])));
		list.sort(Comparator.comparingLong(Part::bytes).reversed());
		return new Usage(sum[0], sum[1], list);
	}

	private void trimIfNeeded() {
		if (limitBytes <= 0 || total.get() <= limitBytes || !busy.compareAndSet(false, true)) {
			return;
		}
		try {
			worker.execute(() -> {
				try {
					trim();
				} finally {
					busy.set(false);
				}
			});
		} catch (RuntimeException e) {
			busy.set(false);
		}
	}

	private record Item(Path path, long size, long modified) {
	}

	/** Deletes the least recently used files until the cache is under 90 % of its limit. */
	private void trim() {
		List<Item> items = new ArrayList<>();
		long[] sum = new long[1];
		long now = System.currentTimeMillis();
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					sum[0] += attrs.size();
					long modified = attrs.lastModifiedTime().toMillis();
					String name = file.getFileName().toString();
					// Leftovers of interrupted writes go first, whatever their age.
					if (name.endsWith(".tmp") && now - modified > KEEP_RECENT_MS) {
						items.add(new Item(file, attrs.size(), Long.MIN_VALUE));
					} else if (now - modified > KEEP_RECENT_MS && !name.equals("tilejson-template.txt")) {
						items.add(new Item(file, attrs.size(), modified));
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Could not trim the cache in {}: {}", root, e.toString());
			return;
		}
		total.set(sum[0]);
		long target = limitBytes / 10 * 9;
		if (sum[0] <= limitBytes) {
			return;
		}
		items.sort(Comparator.comparingLong(Item::modified));
		long size = sum[0];
		long freed = 0;
		int deleted = 0;
		for (Item item : items) {
			if (size <= target) {
				break;
			}
			try {
				if (Files.deleteIfExists(item.path)) {
					size -= item.size;
					freed += item.size;
					deleted++;
				}
			} catch (IOException ignored) {
				// In use; skip it.
			}
		}
		total.set(size);
		RealEarth.LOGGER.info("Cache over its {} limit: removed {} least recently used files ({})", megabytes(limitBytes), deleted, megabytes(freed));
	}

	/** Deletes the whole cache. Blocking; returns the bytes freed. Files in use are left behind. */
	public long clear() {
		long[] freed = new long[1];
		if (!Files.isDirectory(root)) {
			return 0;
		}
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					try {
						Files.delete(file);
						freed[0] += attrs.size();
					} catch (IOException ignored) {
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException e) {
					if (!dir.equals(root)) {
						try {
							Files.delete(dir);
						} catch (IOException ignored) {
							// Not empty: something was in use.
						}
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Could not clear the cache in {}: {}", root, e.toString());
		}
		total.set(measure().bytes());
		return freed[0];
	}

	public static String megabytes(long bytes) {
		if (bytes >= 1L << 30) {
			return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
		}
		return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
	}
}
