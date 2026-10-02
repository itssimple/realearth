package se.itssimple.realearth.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Banded rock for the cliffs of dry country -- the Grand Canyon, Monument Valley, the outback: layers
 * of orange, red, brown, cream and grey terracotta a few blocks thick, by height.
 *
 * The bands run level across a whole region, as real sediment layers do, with a gentle wobble so they
 * don't look ruled. Chunks and distant terrain use the same pattern, so they agree at their seam.
 */
public final class Strata {
	/** Distant terrain's name for "banded rock here"; not a real block. */
	public static final String BANDS = "realearth:bands";

	private static final Block[] PALETTE = {
		Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BROWN_TERRACOTTA,
		Blocks.YELLOW_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.SMOOTH_RED_SANDSTONE
	};
	/** How often each colour comes up, matching the index in PALETTE. Mostly warm, a few pale bands. */
	private static final int[] WEIGHTS = {26, 22, 16, 11, 8, 6, 6, 5};
	private static final int PERIOD = 256;
	private static final int[] LAYERS = new int[PERIOD];
	private static final BlockState[] STATES = new BlockState[PALETTE.length];

	static {
		int total = 0;
		for (int w : WEIGHTS) {
			total += w;
		}
		long seed = 0x5EED_BA4D5L;
		int y = 0;
		int previous = -1;
		while (y < PERIOD) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			int pick = (int) ((seed >>> 33) % total);
			int colour = 0;
			while (pick >= WEIGHTS[colour]) {
				pick -= WEIGHTS[colour];
				colour++;
			}
			if (colour == previous) {
				continue;
			}
			previous = colour;
			int thickness = 1 + (int) ((seed >>> 20) & 3) + (colour < 3 ? 1 : 0);
			for (int i = 0; i < thickness && y < PERIOD; i++) {
				LAYERS[y++] = colour;
			}
		}
		for (int i = 0; i < PALETTE.length; i++) {
			STATES[i] = PALETTE[i].defaultBlockState();
		}
	}

	private Strata() {
	}

	private static int layer(int y, int worldX, int worldZ) {
		int wobble = (int) Math.round(1.5 * Math.sin(worldX / 41.0) + 1.5 * Math.sin(worldZ / 57.0));
		return LAYERS[Math.floorMod(y + wobble, PERIOD)];
	}

	public static BlockState at(int y, int worldX, int worldZ) {
		return STATES[layer(y, worldX, worldZ)];
	}

	/** Registry id of the band block at a height, for distant terrain. */
	public static String idAt(int y, int worldX, int worldZ) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(PALETTE[layer(y, worldX, worldZ)]).toString();
	}
}
