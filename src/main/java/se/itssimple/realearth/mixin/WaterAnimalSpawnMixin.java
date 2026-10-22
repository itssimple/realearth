package se.itssimple.realearth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.AgeableWaterCreature;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import se.itssimple.realearth.world.LakeSurface;

/**
 * Fish, squid and dolphins spawn in the top 13 blocks under the sea level. On Earth most lakes and
 * rivers lie above it -- Siljan is 161 m up -- so they had no fish at all. In water above the sea, the
 * water's own surface counts as its sea level.
 */
@Mixin({WaterAnimal.class, AgeableWaterCreature.class})
public abstract class WaterAnimalSpawnMixin {
	@Redirect(method = {"checkSurfaceWaterAnimalSpawnRules", "checkSurfaceAgeableWaterCreatureSpawnRules"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSeaLevel()I"), require = 1, allow = 1)
	private static int realearth$lakeSurface(LevelAccessor level, EntityType<?> type, LevelAccessor same, EntitySpawnReason reason, BlockPos pos,
		RandomSource random) {
		return LakeSurface.seaLevel(level, pos);
	}
}
