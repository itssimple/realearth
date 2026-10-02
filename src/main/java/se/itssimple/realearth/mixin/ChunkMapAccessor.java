package se.itssimple.realearth.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Chunks on their way out of memory, which will still be written to disk: repair must not touch them yet. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("pendingUnloads")
	Long2ObjectLinkedOpenHashMap<ChunkHolder> realearth$pendingUnloads();
}
