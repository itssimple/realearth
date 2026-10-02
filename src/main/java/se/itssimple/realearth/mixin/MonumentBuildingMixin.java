package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import se.itssimple.realearth.world.EarthChunkGenerator;
import se.itssimple.realearth.world.MonumentHeight;

/**
 * The monument's hardcoded height, and its water. Every room is laid out from the building's own
 * box, so shifting that one constant moves the whole monument. Vanilla then fills water up to
 * max(sea level, 64), which on Earth would be a 1,288-block column; there it is the real sea level.
 */
@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public abstract class MonumentBuildingMixin {
	@ModifyArg(
		method = "<init>(Lnet/minecraft/util/RandomSource;IILnet/minecraft/core/Direction;)V",
		at = @At(value = "INVOKE", target = "makeBoundingBox(IIILnet/minecraft/core/Direction;III)Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"),
		index = 1
	)
	private static int realearth$monumentY(int y) {
		return y + MonumentHeight.OFFSET.get();
	}

	@WrapOperation(method = "postProcess", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0))
	private int realearth$earthWaterLine(int seaLevel, int vanillaSea, Operation<Integer> original, @Local(argsOnly = true) ChunkGenerator generator) {
		return generator instanceof EarthChunkGenerator ? seaLevel : original.call(seaLevel, vanillaSea);
	}
}
