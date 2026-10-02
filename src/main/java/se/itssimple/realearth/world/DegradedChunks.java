package se.itssimple.realearth.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import se.itssimple.realearth.RealEarth;

/**
 * Chunks generated while some of their data could not be downloaded, per level, saved with the
 * world. Minecraft keeps a chunk once it is generated, so without this list a chunk made from a flat
 * stand-in while offline would stay flat forever. {@code /earth repair} regenerates them once the
 * data is back.
 *
 * Also remembers which of those chunks a player has built in or dug into, so repair leaves them alone
 * unless told otherwise.
 */
public final class DegradedChunks {
	private static final Gson GSON = new Gson();
	private static final long NOTIFY_EVERY_MS = 5 * 60_000;

	private record Entry(long[] degraded, long[] touched) {
	}

	private static final Map<String, LongSet> DEGRADED = new HashMap<>();
	private static final Map<String, LongSet> TOUCHED = new HashMap<>();
	private static Path storage;
	private static boolean dirty;
	private static long lastNotice;
	private static int sinceNotice;

	private DegradedChunks() {
	}

	public static synchronized void load(Path file) {
		storage = file;
		DEGRADED.clear();
		TOUCHED.clear();
		dirty = false;
		sinceNotice = 0;
		lastNotice = 0;
		try {
			if (Files.exists(file)) {
				Map<String, Entry> read = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Entry>>() {}.getType());
				if (read != null) {
					read.forEach((level, e) -> {
						if (e.degraded != null && e.degraded.length > 0) {
							DEGRADED.put(level, new LongLinkedOpenHashSet(e.degraded));
						}
						if (e.touched != null && e.touched.length > 0) {
							TOUCHED.put(level, new LongOpenHashSet(e.touched));
						}
					});
				}
			}
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Could not read the list of degraded chunks from {}", file, e);
		}
		int total = count();
		if (total > 0) {
			RealEarth.LOGGER.warn("{} chunks were generated without all their data (offline); run /earth repair when online", total);
		}
	}

	/** Writes the list if it changed. Cheap to call often. */
	public static void save() {
		Path file;
		String json;
		synchronized (DegradedChunks.class) {
			if (!dirty || storage == null) {
				return;
			}
			Map<String, Entry> out = new HashMap<>();
			DEGRADED.forEach((level, set) -> {
				LongSet touched = TOUCHED.getOrDefault(level, LongSet.of());
				long[] t = touched.longStream().filter(set::contains).toArray();
				out.put(level, new Entry(set.toLongArray(), t));
			});
			json = GSON.toJson(out);
			file = storage;
			dirty = false;
		}
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, json);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Could not save the list of degraded chunks to {}", file, e);
		}
	}

	/** Called by the generator for a chunk it had to build from stand-ins. */
	public static void record(String level, ChunkPos pos) {
		boolean notify = false;
		int count;
		synchronized (DegradedChunks.class) {
			if (storage == null || !DEGRADED.computeIfAbsent(level, k -> new LongLinkedOpenHashSet()).add(pos.pack())) {
				return;
			}
			LongSet touched = TOUCHED.get(level);
			if (touched != null) {
				touched.remove(pos.pack());
			}
			dirty = true;
			sinceNotice++;
			long now = System.currentTimeMillis();
			if (now - lastNotice > NOTIFY_EVERY_MS) {
				lastNotice = now;
				notify = true;
			}
			count = sinceNotice;
			if (notify) {
				sinceNotice = 0;
			}
		}
		if (notify) {
			notifyOperators(count);
		}
	}

	private static void notifyOperators(int count) {
		MinecraftServer server = RealEarth.server();
		if (server == null) {
			return;
		}
		RealEarth.LOGGER.warn("Map data could not be downloaded; new chunks are being generated from stand-ins (flat ground, no land cover). They are listed for /earth repair.");
		server.execute(() -> {
			Component message = Component.literal("[Real Earth] Map data could not be downloaded, so new terrain is being generated from placeholders. "
				+ "Once you are back online, run /earth repair to regenerate it (" + count + " chunk" + (count == 1 ? "" : "s") + " so far).")
				.withStyle(ChatFormatting.GOLD);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (server.getPlayerList().isOp(player.nameAndId())) {
					player.sendSystemMessage(message);
				}
			}
		});
	}

	/** A player changed blocks in this chunk: if it is degraded, repair should leave it alone by default. */
	public static void touch(String level, int chunkX, int chunkZ) {
		long key = ChunkPos.pack(chunkX, chunkZ);
		synchronized (DegradedChunks.class) {
			LongSet set = DEGRADED.get(level);
			if (set != null && set.contains(key) && TOUCHED.computeIfAbsent(level, k -> new LongOpenHashSet()).add(key)) {
				dirty = true;
			}
		}
	}

	public static synchronized long[] degraded(String level) {
		LongSet set = DEGRADED.get(level);
		return set == null ? new long[0] : set.toLongArray();
	}

	public static synchronized boolean isTouched(String level, long key) {
		LongSet set = TOUCHED.get(level);
		return set != null && set.contains(key);
	}

	public static synchronized void remove(String level, long key) {
		LongSet set = DEGRADED.get(level);
		if (set != null && set.remove(key)) {
			dirty = true;
			LongSet touched = TOUCHED.get(level);
			if (touched != null) {
				touched.remove(key);
			}
			if (set.isEmpty()) {
				DEGRADED.remove(level);
				TOUCHED.remove(level);
			}
		}
	}

	public static synchronized int count() {
		return DEGRADED.values().stream().mapToInt(LongSet::size).sum();
	}

	public static synchronized Iterable<String> levels() {
		return new java.util.ArrayList<>(DEGRADED.keySet());
	}

	public static synchronized void unload() {
		save();
		storage = null;
		DEGRADED.clear();
		TOUCHED.clear();
	}
}
