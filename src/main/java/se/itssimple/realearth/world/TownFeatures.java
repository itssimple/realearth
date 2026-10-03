package se.itssimple.realearth.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.osm.Ways;
import se.itssimple.realearth.terrain.ChunkTerrain;

/**
 * The village life between real buildings: a well, a farm plot, a bell on its post or a stack of hay
 * in some open patches of town, and street lamps along the streets.
 *
 * Each feature fits inside one chunk (it is placed in the chunk's middle), so no chunk ever depends on
 * its neighbours for one.
 */
final class TownFeatures {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	/** Chance that an open, flat patch of town gets a feature. */
	private static final double FEATURE_CHANCE = 0.22;
	private static final double LAMP_CHANCE = 0.035;

	private TownFeatures() {
	}

	static void place(ChunkAccess chunk, ChunkTerrain t, int baseX, int baseZ, int[] buildingTop) {
		if (!isTown(t)) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double h = GeoNoise.hash(baseX >> 4, 91, baseZ >> 4);
		if (h < FEATURE_CHANCE) {
			int ground = openFlat(t, buildingTop);
			if (ground != Integer.MIN_VALUE) {
				double kind = GeoNoise.hash(baseX >> 4, 92, baseZ >> 4);
				if (kind < 0.3) {
					well(chunk, pos, ground);
				} else if (kind < 0.65) {
					farm(chunk, pos, ground, GeoNoise.hash(baseX >> 4, 93, baseZ >> 4));
				} else if (kind < 0.85) {
					bell(chunk, pos, ground);
				} else {
					hay(chunk, pos, ground);
				}
			}
		}
		// Street lamps: on the kerb, beside paved streets.
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = ChunkTerrain.index(x, z);
				if (t.way[k] != Ways.NONE || t.building[k] != 0 || t.waterTopY[k] != Integer.MIN_VALUE || buildingTop[z * 16 + x] != Integer.MIN_VALUE) {
					continue;
				}
				boolean kerb = Ways.isPaved(t.way[ChunkTerrain.index(x - 1, z)]) || Ways.isPaved(t.way[ChunkTerrain.index(x + 1, z)])
					|| Ways.isPaved(t.way[ChunkTerrain.index(x, z - 1)]) || Ways.isPaved(t.way[ChunkTerrain.index(x, z + 1)]);
				if (kerb && GeoNoise.hash(baseX + x, 94, baseZ + z) < LAMP_CHANCE) {
					int y = t.topSolidY[k];
					set(chunk, pos, x, y + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
					set(chunk, pos, x, y + 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
					set(chunk, pos, x, y + 3, z, Blocks.LANTERN.defaultBlockState());
				}
			}
		}
	}

	/** Town: built-up land or mapped buildings in this chunk. */
	private static boolean isTown(ChunkTerrain t) {
		if (!t.plans.isEmpty()) {
			return true;
		}
		int built = 0;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				if (t.coverAt(x, z) == WorldCover.BUILT) {
					built++;
				}
			}
		}
		return built > 96;
	}

	/** Ground Y of the chunk's middle 9 x 9 when it is dry, empty and level within a block; else MIN_VALUE. */
	private static int openFlat(ChunkTerrain t, int[] buildingTop) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int z = 3; z <= 11; z++) {
			for (int x = 3; x <= 11; x++) {
				int k = ChunkTerrain.index(x, z);
				if (t.building[k] != 0 || t.way[k] != Ways.NONE || t.waterTopY[k] != Integer.MIN_VALUE || t.deckY[k] != Integer.MIN_VALUE
					|| buildingTop[z * 16 + x] != Integer.MIN_VALUE) {
					return Integer.MIN_VALUE;
				}
				min = Math.min(min, t.topSolidY[k]);
				max = Math.max(max, t.topSolidY[k]);
			}
		}
		return max - min <= 1 ? max : Integer.MIN_VALUE;
	}

	/** Levels a square of ground at {@code y}: fills below, clears above. */
	private static void level(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x0, int z0, int x1, int z1, int y, BlockState top) {
		for (int z = z0; z <= z1; z++) {
			for (int x = x0; x <= x1; x++) {
				for (int dy = -2; dy < 0; dy++) {
					set(chunk, pos, x, y + dy, z, Blocks.DIRT.defaultBlockState());
				}
				set(chunk, pos, x, y, z, top);
				for (int dy = 1; dy <= 4; dy++) {
					set(chunk, pos, x, y + dy, z, AIR);
				}
			}
		}
	}

	/** A village well: cobblestone ring round water, four posts and a roof. */
	private static void well(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int y) {
		level(chunk, pos, 5, 5, 10, 10, y, Blocks.GRAVEL.defaultBlockState());
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		for (int z = 6; z <= 9; z++) {
			for (int x = 6; x <= 9; x++) {
				boolean rim = x == 6 || x == 9 || z == 6 || z == 9;
				for (int dy = -4; dy <= 0; dy++) {
					set(chunk, pos, x, y + dy, z, rim ? cobble : Blocks.WATER.defaultBlockState());
				}
				if (rim) {
					set(chunk, pos, x, y + 1, z, cobble);
				}
			}
		}
		for (int[] c : new int[][] {{6, 6}, {9, 6}, {6, 9}, {9, 9}}) {
			set(chunk, pos, c[0], y + 2, c[1], Blocks.OAK_FENCE.defaultBlockState());
			set(chunk, pos, c[0], y + 3, c[1], Blocks.OAK_FENCE.defaultBlockState());
		}
		for (int z = 6; z <= 9; z++) {
			for (int x = 6; x <= 9; x++) {
				set(chunk, pos, x, y + 4, z, Blocks.COBBLESTONE_SLAB.defaultBlockState());
			}
		}
	}

	/** A farm plot: rows of one crop, watered from a channel down the middle, edged with logs. */
	private static void farm(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int y, double which) {
		Block crop = which < 0.4 ? Blocks.WHEAT : which < 0.6 ? Blocks.CARROTS : which < 0.8 ? Blocks.POTATOES : Blocks.BEETROOTS;
		BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);
		for (int z = 3; z <= 11; z++) {
			for (int x = 3; x <= 11; x++) {
				boolean edge = x == 3 || x == 11 || z == 3 || z == 11;
				for (int dy = 1; dy <= 3; dy++) {
					set(chunk, pos, x, y + dy, z, AIR);
				}
				if (edge) {
					set(chunk, pos, x, y, z, Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, x == 3 || x == 11 ? Direction.Axis.Z : Direction.Axis.X));
				} else if (x == 7) {
					set(chunk, pos, x, y, z, Blocks.WATER.defaultBlockState());
				} else {
					set(chunk, pos, x, y, z, farmland);
					BlockState plant = crop == Blocks.BEETROOTS ? crop.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3)
						: crop.defaultBlockState().setValue(BlockStateProperties.AGE_7, 4 + (int) (GeoNoise.hash(x, y, z) * 4));
					set(chunk, pos, x, y + 1, z, plant);
				}
			}
		}
		set(chunk, pos, 3, y + 1, 3, Blocks.COMPOSTER.defaultBlockState());
	}

	/** A small square: a bell on a post, where villagers gather. */
	private static void bell(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int y) {
		level(chunk, pos, 5, 5, 10, 10, y, Blocks.STONE_BRICKS.defaultBlockState());
		set(chunk, pos, 7, y + 1, 7, Blocks.COBBLESTONE_WALL.defaultBlockState());
		set(chunk, pos, 7, y + 2, 7, Blocks.OAK_FENCE.defaultBlockState());
		set(chunk, pos, 7, y + 3, 7, Blocks.OAK_PLANKS.defaultBlockState());
		set(chunk, pos, 7, y + 2, 8, Blocks.BELL.defaultBlockState().setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.CEILING));
		for (int[] c : new int[][] {{5, 5}, {10, 5}, {5, 10}, {10, 10}}) {
			set(chunk, pos, c[0], y + 1, c[1], Blocks.LANTERN.defaultBlockState());
		}
	}

	/** Hay bales stacked by a fence: farm stores. */
	private static void hay(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int y) {
		level(chunk, pos, 5, 5, 10, 10, y, Blocks.COARSE_DIRT.defaultBlockState());
		for (int z = 6; z <= 8; z++) {
			for (int x = 6; x <= 8; x++) {
				set(chunk, pos, x, y + 1, z, Blocks.HAY_BLOCK.defaultBlockState());
				if ((x + z) % 2 == 0) {
					set(chunk, pos, x, y + 2, z, Blocks.HAY_BLOCK.defaultBlockState());
				}
			}
		}
		for (int x = 5; x <= 10; x++) {
			set(chunk, pos, x, y + 1, 10, Blocks.OAK_FENCE.defaultBlockState());
		}
	}

	private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
		if (y <= chunk.getMinY() || y >= chunk.getMaxY()) {
			return;
		}
		chunk.setBlockState(pos.set(x, y, z), s, 0);
		// Blocks set straight into a chunk get no block entity of their own: a bed, chest or bell would
		// be invisible or dead. Make it here, at the block's place in the world.
		if (s.hasBlockEntity() && s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
			BlockPos at = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
			var entity = entityBlock.newBlockEntity(at, s);
			if (entity != null) {
				chunk.setBlockEntity(entity);
			}
		}
	}
}
