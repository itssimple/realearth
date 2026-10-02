package se.itssimple.realearth.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.mixin.ChunkMapAccessor;
import se.itssimple.realearth.mixin.EntityStorageAccessor;
import se.itssimple.realearth.mixin.PersistentEntitySectionManagerAccessor;
import se.itssimple.realearth.mixin.ServerLevelAccessor;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.world.DegradedChunks;

/**
 * Regenerates chunks that were generated from stand-ins while offline (see {@link DegradedChunks}).
 *
 * First the data is fetched again for each of them. Those whose data is now complete are deleted from
 * the save, block and entity data both, so Minecraft generates them afresh the next time they load.
 * Chunks that are loaded right now are left for a later run, since the game would write them back;
 * chunks a player has changed blocks in are left alone unless {@code includeChanged} is set.
 */
public final class ChunkRepair {
	private static final int BATCH = 256;
	private static final AtomicBoolean RUNNING = new AtomicBoolean();

	private ChunkRepair() {
	}

	/** Outcome of one run. */
	public record Result(int repaired, int stillMissingData, int loaded, int changed, int remaining) {
	}

	public static boolean isRunning() {
		return RUNNING.get();
	}

	public static boolean start(CommandSourceStack source, boolean includeChanged) {
		MinecraftServer server = source.getServer();
		TerrainField field = RealEarth.terrain();
		if (field == null || !RUNNING.compareAndSet(false, true)) {
			return false;
		}
		Thread thread = new Thread(() -> {
			try {
				List<Target> ready = fetchAgain(field);
				CompletableFuture<Result> result = new CompletableFuture<>();
				server.execute(() -> {
					try {
						result.complete(delete(server, ready, includeChanged));
					} catch (Throwable e) {
						result.completeExceptionally(e);
					}
				});
				Result r = result.get(60, TimeUnit.SECONDS);
				int missing = r.stillMissingData + countNotReady(ready);
				server.execute(() -> report(source, new Result(r.repaired, missing, r.loaded, r.changed, DegradedChunks.count())));
			} catch (Exception e) {
				RealEarth.LOGGER.warn("Chunk repair failed", e);
				server.execute(() -> source.sendFailure(Component.literal("Repair failed: " + e.getMessage())));
			} finally {
				RUNNING.set(false);
			}
		}, "RealEarth-Repair");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	/** A degraded chunk, and whether its data is complete now. */
	private record Target(String level, long key, boolean ready) {
	}

	private static int countNotReady(List<Target> targets) {
		return (int) targets.stream().filter(t -> !t.ready).count();
	}

	/** Asks for every degraded chunk's data again, in batches. Blocking. */
	private static List<Target> fetchAgain(TerrainField field) throws Exception {
		List<Target> out = new ArrayList<>();
		for (String level : DegradedChunks.levels()) {
			long[] keys = DegradedChunks.degraded(level);
			for (int from = 0; from < keys.length; from += BATCH) {
				int to = Math.min(keys.length, from + BATCH);
				List<CompletableFuture<ChunkTerrain>> batch = new ArrayList<>();
				for (int i = from; i < to; i++) {
					int cx = ChunkPos.getX(keys[i]);
					int cz = ChunkPos.getZ(keys[i]);
					field.forgetChunk(cx, cz);
					batch.add(field.chunk(cx, cz));
				}
				for (int i = from; i < to; i++) {
					boolean ready;
					try {
						ready = !batch.get(i - from).get(180, TimeUnit.SECONDS).degraded;
					} catch (Exception e) {
						ready = false;
					}
					out.add(new Target(level, keys[i], ready));
				}
				// Offline: every later batch would fail the same way.
				if (from == 0 && out.stream().noneMatch(Target::ready) && field.store().offline()) {
					for (int i = to; i < keys.length; i++) {
						out.add(new Target(level, keys[i], false));
					}
					break;
				}
			}
		}
		return out;
	}

	/** On the server thread: deletes the saved chunks that can be regenerated now. */
	private static Result delete(MinecraftServer server, List<Target> targets, boolean includeChanged) {
		int repaired = 0;
		int loaded = 0;
		int changed = 0;
		for (Target t : targets) {
			if (!t.ready) {
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(t.level)));
			if (level == null) {
				continue;
			}
			if (!includeChanged && DegradedChunks.isTouched(t.level, t.key)) {
				changed++;
				continue;
			}
			ChunkMap chunkMap = level.getChunkSource().chunkMap;
			if (chunkMap.getUpdatingChunkIfPresent(t.key) != null
				|| ((ChunkMapAccessor) chunkMap).realearth$pendingUnloads().containsKey(t.key)
				|| level.areEntitiesLoaded(t.key)) {
				loaded++;
				continue;
			}
			ChunkPos pos = ChunkPos.unpack(t.key);
			chunkMap.write(pos, IOWorker.STORE_EMPTY);
			var storage = ((PersistentEntitySectionManagerAccessor) ((ServerLevelAccessor) level).realearth$entityManager()).realearth$permanentStorage();
			if (storage instanceof EntityStorage entities) {
				((EntityStorageAccessor) entities).realearth$simpleRegionStorage().write(pos, IOWorker.STORE_EMPTY);
			}
			DegradedChunks.remove(t.level, t.key);
			repaired++;
		}
		DegradedChunks.save();
		return new Result(repaired, 0, loaded, changed, DegradedChunks.count());
	}

	private static void report(CommandSourceStack source, Result r) {
		StringBuilder b = new StringBuilder();
		if (r.repaired > 0) {
			b.append(chunks(r.repaired)).append(" will be generated again from the real data the next time ")
				.append(r.repaired == 1 ? "it loads." : "they load.");
		} else {
			b.append("No chunks could be repaired right now.");
		}
		if (r.stillMissingData > 0) {
			b.append(' ').append(chunks(r.stillMissingData)).append(r.stillMissingData == 1 ? " still lacks" : " still lack")
				.append(" data (are you online?).");
		}
		if (r.loaded > 0) {
			b.append(' ').append(chunks(r.loaded)).append(r.loaded == 1 ? " is" : " are")
				.append(" loaded at the moment; move away and run /earth repair again.");
		}
		if (r.changed > 0) {
			b.append(' ').append(chunks(r.changed)).append(r.changed == 1 ? " has" : " have")
				.append(" blocks placed or broken by players and stayed as ").append(r.changed == 1 ? "it is" : "they are")
				.append("; /earth repair all regenerates those too, losing what was built there.");
		}
		boolean clean = r.remaining == 0;
		source.sendSuccess(() -> Component.literal(b.toString()).withStyle(clean ? ChatFormatting.GREEN : ChatFormatting.GOLD), true);
	}

	private static String chunks(int n) {
		return n + (n == 1 ? " chunk" : " chunks");
	}
}
