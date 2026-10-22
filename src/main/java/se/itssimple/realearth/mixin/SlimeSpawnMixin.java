package se.itssimple.realearth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Slimes spawn in slime chunks below Y 40 and in swamps between Y 50 and 70: heights fixed for vanilla's
 * sea level at 63. On Earth the sea is at Y -1200, so all the land is "below 40" and slimes came out
 * on the surface of every slime chunk. Measured from the level's own sea level instead, the same as in
 * vanilla: slime chunks 23 blocks below the sea, swamps round it. Vanilla worlds are unchanged.
 */
@Mixin(Slime.class)
public abstract class SlimeSpawnMixin {
	private static final int VANILLA_SEA_LEVEL = 63;

	@Redirect(method = "checkSlimeSpawnRules", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;getY()I"))
	private static int realearth$fromSeaLevel(BlockPos pos, EntityType<Slime> type, LevelAccessor level, EntitySpawnReason reason, BlockPos at,
		RandomSource random) {
		return pos.getY() - level.getSeaLevel() + VANILLA_SEA_LEVEL;
	}
}
