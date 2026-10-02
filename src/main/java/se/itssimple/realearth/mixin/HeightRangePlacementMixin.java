package se.itssimple.realearth.mixin;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.world.EarthChunkGenerator;

/**
 * Vanilla features at their vanilla depths, on Earth.
 *
 * Ores, glow lichen, geodes, dungeons, springs, fossils, sculk and the rest are placed at absolute
 * heights: diamonds around Y -59 under ground at about Y 64. On Earth the ground can be anywhere from
 * Y -1200 to 2000, so absolute heights would put every diamond inside mountains and none under the
 * lowlands. Here the height is drawn exactly as vanilla draws it -- in a vanilla-sized world, -64 to
 * 320 -- and then laid out as depth below the local ground, as it relates to vanilla's surface.
 */
@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {
	private static final LevelHeightAccessor VANILLA_HEIGHT = LevelHeightAccessor.create(-64, 384);

	@Shadow
	@Final
	private HeightProvider height;

	@Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
	private void realearth$relativeToGround(PlacementContext context, RandomSource random, BlockPos origin, CallbackInfoReturnable<Stream<BlockPos>> cir) {
		if (context.generator() instanceof EarthChunkGenerator earth) {
			int vanillaY = this.height.sample(random, new WorldGenerationContext(earth, VANILLA_HEIGHT));
			cir.setReturnValue(Stream.of(origin.atY(earth.vanillaToLocalY(vanillaY, origin.getX(), origin.getZ()))));
		}
	}
}
