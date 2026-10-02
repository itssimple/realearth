package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import se.itssimple.realearth.world.EarthChunkGenerator;

/**
 * Jigsaw structures that start at a fixed height rather than at the ground -- ancient cities at
 * Y -27, trial chambers at Y -40 to -20 -- start that far below the LOCAL ground on Earth, as they
 * would below vanilla's ground at 64. Otherwise they would hang 1,200 m up over the lowlands.
 * Structures already projected to a heightmap (villages, outposts, trail ruins) are left alone.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin {
	private static final LevelHeightAccessor VANILLA_HEIGHT = LevelHeightAccessor.create(-64, 384);

	@Shadow
	@Final
	private Optional<Heightmap.Types> projectStartToHeightmap;

	@WrapOperation(method = "findGenerationPoint", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/heightproviders/HeightProvider;sample(Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)I"))
	private int realearth$groundRelativeStart(HeightProvider provider, RandomSource random, WorldGenerationContext heights, Operation<Integer> original,
		@Local(argsOnly = true) Structure.GenerationContext context) {
		if (context.chunkGenerator() instanceof EarthChunkGenerator earth && this.projectStartToHeightmap.isEmpty()) {
			int vanillaY = original.call(provider, random, new WorldGenerationContext(earth, VANILLA_HEIGHT));
			return earth.vanillaToLocalY(vanillaY, context.chunkPos().getMinBlockX(), context.chunkPos().getMinBlockZ());
		}
		return original.call(provider, random, heights);
	}
}
