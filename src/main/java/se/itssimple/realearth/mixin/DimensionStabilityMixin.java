package se.itssimple.realearth.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.world.EarthChunkGenerator;

/**
 * Minecraft calls every dimension it doesn't recognise as vanilla "experimental", and warns about the
 * whole world each time it is opened. Dimensions built by the Earth generator -- the Earth dimension,
 * and the Overworld of a Real Earth world -- are a supported part of this mod, so they count as stable.
 */
@Mixin(WorldDimensions.class)
public abstract class DimensionStabilityMixin {
	@Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
	private static void realearth$earthIsStable(ResourceKey<LevelStem> key, LevelStem dimension, CallbackInfoReturnable<Lifecycle> cir) {
		if (dimension.generator() instanceof EarthChunkGenerator) {
			cir.setReturnValue(Lifecycle.stable());
		}
	}
}
