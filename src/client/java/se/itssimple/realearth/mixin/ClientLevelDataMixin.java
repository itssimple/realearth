package se.itssimple.realearth.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.VerticalScale;

/**
 * Vanilla puts the horizon at Y 63 and draws the lower half of the sky black whenever the camera is
 * below it. Earth's sea level is Y -1200, so without this the whole planet would sit under a black
 * horizon. On Earth the horizon is Earth's sea level.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataMixin {
	@Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
	private void realearth$earthHorizon(LevelHeightAccessor level, CallbackInfoReturnable<Double> cir) {
		if (level instanceof Level l && RealEarth.isEarth(l)) {
			cir.setReturnValue((double) VerticalScale.SEA_LEVEL_Y);
		}
	}
}
