package se.itssimple.realearth.data;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import se.itssimple.realearth.RealEarth;

/**
 * Small blocking HTTP helper for the auxiliary data sources (land cover, map data): retries with
 * backoff, byte ranges, transparent gzip, and a pause after repeated failures so an offline machine
 * does not stall world generation retrying every request.
 *
 * Call it from a download thread, never from the server thread.
 */
public final class Http {
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final int ATTEMPTS = 3;
	private static final long OFFLINE_PAUSE_MS = 30_000;
	private static final AtomicLong OFFLINE_UNTIL = new AtomicLong();

	private Http() {
	}

	/** Thrown when a resource does not exist (404/403) -- a definite answer, not a failure. */
	public static final class Missing extends IOException {
		public Missing(String url) {
			super("not found: " + url);
		}
	}

	/** GETs a URL, or a byte range of it when {@code length > 0}. */
	public static byte[] get(String url, long offset, long length) throws IOException {
		if (System.currentTimeMillis() < OFFLINE_UNTIL.get()) {
			throw new IOException("network paused after recent failures");
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(30))
			.header("User-Agent", "RealEarth-Minecraft-Mod/" + RealEarth.VERSION)
			.header("Accept-Encoding", "gzip");
		if (length > 0) {
			builder.header("Range", "bytes=" + offset + "-" + (offset + length - 1));
		}
		HttpRequest request = builder.GET().build();

		IOException last = null;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(500L << attempt);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted");
				}
			}
			try {
				HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
				int code = response.statusCode();
				if (code == 200 || code == 206) {
					byte[] body = response.body();
					boolean gzip = response.headers().firstValue("Content-Encoding").map("gzip"::equalsIgnoreCase).orElse(false)
						|| (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B);
					return gzip ? gunzip(body) : body;
				}
				if (code == 404 || code == 403) {
					throw new Missing(url);
				}
				last = new IOException("HTTP " + code + " for " + url);
			} catch (Missing m) {
				throw m;
			} catch (IOException e) {
				last = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			}
		}
		OFFLINE_UNTIL.set(System.currentTimeMillis() + OFFLINE_PAUSE_MS);
		throw last;
	}

	public static byte[] gunzip(byte[] data) throws IOException {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
			return in.readAllBytes();
		}
	}

	/** Reads a cached file, or null. */
	public static byte[] readCache(Path file) {
		try {
			if (!Files.exists(file)) {
				return null;
			}
			byte[] data = Files.readAllBytes(file);
			DiskCache.used(file);
			return data;
		} catch (IOException e) {
			return null;
		}
	}

	/** Writes a cache file atomically, so a crash never leaves half a file behind. */
	public static void writeCache(Path file, byte[] data) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
			Files.write(tmp, data);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			DiskCache.written(data.length);
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Could not cache {}: {}", file, e.getMessage());
		}
	}
}
