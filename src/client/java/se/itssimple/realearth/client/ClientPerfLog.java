package se.itssimple.realearth.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.world.ChunkTimes;
import se.itssimple.realearth.world.PerfLog;

/**
 * The client's lines of the running performance log (see PerfLog), on the Earth: chunks within the
 * render distance it still has not got, how long chunks took to arrive (singleplayer: from their first
 * generation step), the frame rate, and the chunk sections waiting to be built for drawing.
 */
final class ClientPerfLog {
	private static int ticks;

	private ClientPerfLog() {
	}

	static void install() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (PerfLog.seconds() > 0 && level.dimension() == RealEarth.EARTH) {
				ChunkTimes.arrived(chunk.getPos().x(), chunk.getPos().z());
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(ClientPerfLog::tick);
	}

	private static void tick(Minecraft mc) {
		int every = PerfLog.seconds();
		if (every <= 0 || mc.level == null || mc.player == null || ++ticks % (every * 20) != 0 || mc.level.dimension() != RealEarth.EARTH) {
			return;
		}
		int view = mc.options.getEffectiveRenderDistance();
		int cx = mc.player.chunkPosition().x();
		int cz = mc.player.chunkPosition().z();
		int missing = 0;
		int nearest = Integer.MAX_VALUE;
		for (int dz = -view; dz <= view; dz++) {
			for (int dx = -view; dx <= view; dx++) {
				if (mc.level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) {
					missing++;
					nearest = Math.min(nearest, Math.max(Math.abs(dx), Math.abs(dz)));
				}
			}
		}
		int all = (2 * view + 1) * (2 * view + 1);
		PerfLog.write(String.format(java.util.Locale.ROOT, "[perf client] %d fps; render distance %d: %d of %d chunks not here yet%s; arrived %s (first generation"
				+ " step to here), sending %s (last step to here), %d from disk; %d sections waiting to be built; %s",
			mc.getFps(), view, missing, all, missing == 0 ? "" : ", nearest " + nearest + " chunks away", ChunkTimes.TO_CLIENT.take(),
			ChunkTimes.SENDING.take(), ChunkTimes.takeFromDisk(), mc.levelRenderer.getSectionRenderDispatcher().getCompileQueueSize(),
			mc.levelRenderer.getSectionStatistics()));
	}
}
