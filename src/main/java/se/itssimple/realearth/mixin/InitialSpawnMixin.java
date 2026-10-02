package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.world.EarthChunkGenerator;

/**
 * A new Real Earth world spawns at the configured start point, not where the climate noise would put
 * it near block 0, 0 -- which on the Mercator map is the open Atlantic off West Africa. Vanilla's
 * search for a dry spot then works around the start point as usual.
 */
@Mixin(MinecraftServer.class)
public abstract class InitialSpawnMixin {
	@WrapOperation(method = "setInitialSpawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"))
	private static BlockPos realearth$spawnAtStart(Climate.Sampler sampler, Operation<BlockPos> original, @Local(argsOnly = true) ServerLevel level) {
		TerrainField field = RealEarth.terrain();
		if (field != null && level.getChunkSource().getGenerator() instanceof EarthChunkGenerator) {
			return new BlockPos(field.startX(), 0, field.startZ());
		}
		return original.call(sampler);
	}
}
