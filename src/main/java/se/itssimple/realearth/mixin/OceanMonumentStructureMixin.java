package se.itssimple.realearth.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.terrain.VerticalScale;
import se.itssimple.realearth.world.EarthChunkGenerator;
import se.itssimple.realearth.world.MonumentHeight;

/** Builds ocean monuments 24 blocks below Earth's sea level, and keeps them there when reloaded. */
@Mixin(OceanMonumentStructure.class)
public abstract class OceanMonumentStructureMixin {
	@Inject(method = "generatePieces", at = @At("HEAD"))
	private static void realearth$shiftForEarth(StructurePiecesBuilder builder, Structure.GenerationContext context, CallbackInfo ci) {
		MonumentHeight.OFFSET.set(context.chunkGenerator() instanceof EarthChunkGenerator ? VerticalScale.SEA_LEVEL_Y - 63 : 0);
	}

	@Inject(method = "generatePieces", at = @At("RETURN"))
	private static void realearth$clearShift(StructurePiecesBuilder builder, Structure.GenerationContext context, CallbackInfo ci) {
		MonumentHeight.OFFSET.remove();
	}

	@Inject(method = "regeneratePiecesAfterLoad", at = @At("HEAD"))
	private static void realearth$keepSavedHeight(ChunkPos chunkPos, long seed, PiecesContainer saved, CallbackInfoReturnable<PiecesContainer> cir) {
		if (!saved.isEmpty()) {
			MonumentHeight.OFFSET.set(saved.pieces().get(0).getBoundingBox().minY() - MonumentHeight.VANILLA_Y);
		}
	}

	@Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"))
	private static void realearth$clearSavedHeight(ChunkPos chunkPos, long seed, PiecesContainer saved, CallbackInfoReturnable<PiecesContainer> cir) {
		MonumentHeight.OFFSET.remove();
	}
}
