package se.itssimple.realearth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.terrain.VerticalScale;

/**
 * Realistic cooling with height on Earth.
 *
 * Vanilla cools 0.05 temperature units per 40 blocks above sea level, which suits its squashed
 * mountains; at one block per metre it would bring snow and frozen lakes down to a few hundred
 * metres in ordinary forest. On Earth that lapse rate is cut to a quarter, close to the real
 * 6.5 C/km. Recognised by sea level: only the Earth dimension has its sea at Y -1200, and the
 * value is part of the call on both client and server.
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
	private static final float LAPSE_FRACTION = 0.25F;

	@Inject(method = "getHeightAdjustedTemperature", at = @At("RETURN"), cancellable = true)
	private void realearth$realisticLapseRate(BlockPos pos, int seaLevel, CallbackInfoReturnable<Float> cir) {
		if (seaLevel != VerticalScale.SEA_LEVEL_Y) {
			return;
		}
		int snowLevel = seaLevel + 17;
		if (pos.getY() > snowLevel) {
			float excess = (pos.getY() - snowLevel) * (1.0F - LAPSE_FRACTION) * 0.05F / 40.0F;
			cir.setReturnValue(cir.getReturnValueF() + excess);
		}
	}
}
