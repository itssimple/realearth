package se.itssimple.realearth.mixin;

import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import se.itssimple.realearth.terrain.VerticalScale;

/**
 * Mineshafts and strongholds go a random distance below sea level, anywhere down to the bottom of
 * the world. In vanilla that is at most 127 blocks; on Earth the bottom is 800 m further down, which
 * would scatter them through solid deepslate far below every cave. On Earth (recognised by its sea
 * level, which no other dimension uses) they keep vanilla's range.
 */
@Mixin(StructurePiecesBuilder.class)
public abstract class StructurePiecesBuilderMixin {
	private static final int VANILLA_RANGE = 63 - (-64);

	@ModifyVariable(method = "moveBelowSeaLevel", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int realearth$vanillaDepthRange(int minY, int seaLevel) {
		return seaLevel == VerticalScale.SEA_LEVEL_Y ? Math.max(minY, seaLevel - VANILLA_RANGE) : minY;
	}
}
