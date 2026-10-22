package se.itssimple.realearth.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import se.itssimple.realearth.terrain.VerticalScale;

/** The sea level water creatures go by: on Earth, a lake's or river's own surface where it lies above the sea. */
public final class LakeSurface {
	/** How far up the water above a spawn position is followed to its surface. */
	private static final int DEEPEST = 32;

	private LakeSurface() {
	}

	public static int seaLevel(LevelAccessor level, BlockPos pos) {
		int sea = level.getSeaLevel();
		// Only Earth's own sea level is changed; vanilla worlds keep theirs.
		if (sea != VerticalScale.SEA_LEVEL_Y || pos.getY() <= sea) {
			return sea;
		}
		BlockPos.MutableBlockPos p = pos.mutable();
		int top = Integer.MIN_VALUE;
		for (int i = 0; i < DEEPEST && level.getFluidState(p).is(FluidTags.WATER); i++) {
			top = p.getY();
			p.move(0, 1, 0);
		}
		return top == Integer.MIN_VALUE ? sea : top;
	}
}
