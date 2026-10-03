package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import se.itssimple.realearth.terrain.BuildingPlan;
import se.itssimple.realearth.terrain.ChunkTerrain;

/**
 * Builds a chunk's share of its buildings: foundation, floors, walls with windows and corner posts, a
 * front door to the street, ladders between storeys, a roof, and furniture -- beds, chests, crafting
 * tables, furnaces, a villager job block, bookshelves, lanterns.
 *
 * Everything is decided from the building's plan and the block position alone, so neighbouring chunks
 * agree on the parts of a building they share.
 */
final class BuildingPainter {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};
	/** Footprint knowledge reaches two blocks past the chunk, for walls and the rooms next to them. */
	private static final int M = 2;
	private static final int W = 16 + 2 * M;

	private static final Block[] BEDS = {Blocks.RED_BED, Blocks.WHITE_BED, Blocks.BLUE_BED, Blocks.GREEN_BED, Blocks.YELLOW_BED, Blocks.LIGHT_GRAY_BED,
		Blocks.BROWN_BED, Blocks.CYAN_BED};
	/** Villager workstations: whoever moves in takes up that trade. Mostly ones that don't tick. */
	private static final Block[] JOBS = {Blocks.COMPOSTER, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LECTERN, Blocks.LOOM,
		Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.CAULDRON, Blocks.BARREL, Blocks.SMOKER};
	private static final Block[] FLOWERS = {Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_BLUE_ORCHID, Blocks.POTTED_FERN,
		Blocks.POTTED_AZURE_BLUET, Blocks.POTTED_OXEYE_DAISY};

	/** What a building is made of. */
	private record Palette(BlockState wall, BlockState corner, BlockState floor, BlockState foundation, Block stairs, BlockState roofFull,
		BlockState flatRoof, BlockState trim, BlockState window, Block door) {
	}

	private static Palette palette(BuildingPlan p) {
		boolean big = p.floors >= 3;
		int v = p.palette;
		BlockState glass = Blocks.GLASS_PANE.defaultBlockState();
		return switch (p.style) {
			case DESERT -> new Palette(state(v % 3 == 0 ? Blocks.SMOOTH_SANDSTONE : Blocks.SANDSTONE), state(Blocks.CUT_SANDSTONE),
				state(Blocks.SMOOTH_SANDSTONE), state(Blocks.SANDSTONE), Blocks.SANDSTONE_STAIRS, state(Blocks.CUT_SANDSTONE), state(Blocks.SMOOTH_SANDSTONE),
				state(v % 2 == 0 ? Blocks.TERRACOTTA : Blocks.CUT_SANDSTONE), glass, Blocks.JUNGLE_DOOR);
			case SAVANNA -> new Palette(state(big ? (v % 2 == 0 ? Blocks.ORANGE_TERRACOTTA : Blocks.WHITE_TERRACOTTA) : Blocks.ACACIA_PLANKS),
				state(Blocks.ACACIA_LOG), state(Blocks.ACACIA_PLANKS), state(Blocks.COBBLESTONE), big ? Blocks.BRICK_STAIRS : Blocks.ACACIA_STAIRS,
				state(big ? Blocks.BRICKS : Blocks.ACACIA_PLANKS),
				state(Blocks.SMOOTH_STONE), state(Blocks.TERRACOTTA), glass, Blocks.ACACIA_DOOR);
			case TAIGA, SNOWY -> new Palette(state(big ? (v % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.SPRUCE_PLANKS) : Blocks.SPRUCE_PLANKS),
				state(big ? Blocks.STONE_BRICKS : Blocks.SPRUCE_LOG), state(Blocks.SPRUCE_PLANKS), state(Blocks.COBBLESTONE),
				big && v % 3 == 0 ? Blocks.DEEPSLATE_TILE_STAIRS : Blocks.SPRUCE_STAIRS,
				state(big && v % 3 == 0 ? Blocks.DEEPSLATE_TILES : Blocks.SPRUCE_PLANKS), state(p.style == BuildingPlan.Style.SNOWY ? Blocks.SNOW_BLOCK : Blocks.SMOOTH_STONE),
				state(Blocks.COBBLESTONE), glass, Blocks.SPRUCE_DOOR);
			case PLAINS -> {
				if (big) {
					Block[] walls = {Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.SMOOTH_SANDSTONE,
						Blocks.POLISHED_ANDESITE};
					Block wall = walls[v % walls.length];
					// Town roofs: red tile, slate, dark timber or copper-green; flat roofs in greys.
					Block[] roofs = {Blocks.BRICK_STAIRS, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.OXIDIZED_CUT_COPPER_STAIRS, Blocks.BRICK_STAIRS};
					Block[] roofFulls = {Blocks.BRICKS, Blocks.DEEPSLATE_TILES, Blocks.DARK_OAK_PLANKS, Blocks.OXIDIZED_CUT_COPPER, Blocks.BRICKS};
					Block[] flats = {Blocks.SMOOTH_STONE, Blocks.GRAY_CONCRETE, Blocks.POLISHED_ANDESITE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.POLISHED_DEEPSLATE};
					int r = (v >> 3) % roofs.length;
					yield new Palette(state(wall), state(wall == Blocks.STONE_BRICKS ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS), state(Blocks.OAK_PLANKS),
						state(Blocks.STONE_BRICKS), roofs[r], state(roofFulls[r]), state(flats[(v >> 5) % flats.length]), state(Blocks.STONE_BRICKS),
						glass, Blocks.OAK_DOOR);
				}
				Block[] walls = {Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.WHITE_TERRACOTTA, Blocks.BIRCH_PLANKS, Blocks.COBBLESTONE};
				Block[] roofs = {Blocks.OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BRICK_STAIRS};
				Block roof = roofs[(v >> 3) % roofs.length];
				Block roofFull = roof == Blocks.DARK_OAK_STAIRS ? Blocks.DARK_OAK_PLANKS : roof == Blocks.SPRUCE_STAIRS ? Blocks.SPRUCE_PLANKS
					: roof == Blocks.BRICK_STAIRS ? Blocks.BRICKS : Blocks.OAK_PLANKS;
				yield new Palette(state(walls[v % walls.length]), state(Blocks.OAK_LOG), state(Blocks.OAK_PLANKS), state(Blocks.COBBLESTONE), roof,
					state(roofFull), state(Blocks.SMOOTH_STONE), state(Blocks.COBBLESTONE), glass, Blocks.OAK_DOOR);
			}
		};
	}

	private static BlockState state(Block b) {
		return b.defaultBlockState();
	}

	private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, T v) {
		return s.hasProperty(p) ? s.setValue(p, v) : s;
	}

	private BuildingPainter() {
	}

	/**
	 * Builds the chunk's buildings. Returns, per column (z * 16 + x), the highest block placed, or
	 * Integer.MIN_VALUE where there is no building, for the heightmaps.
	 */
	static int[] paint(ChunkAccess chunk, ChunkTerrain t, int baseX, int baseZ) {
		int[] top = new int[256];
		Arrays.fill(top, Integer.MIN_VALUE);
		List<BuildingPlan> plans = t.plans;
		if (plans.isEmpty()) {
			return top;
		}
		List<int[]> beds = new ArrayList<>();
		for (int index = 0; index < plans.size(); index++) {
			BuildingPlan p = plans.get(index);
			// Which columns around the chunk this building covers (by the chunk's own say for the
			// chunk, by the footprint beyond it).
			boolean[] in = new boolean[W * W];
			boolean any = false;
			for (int z = -M; z < 16 + M; z++) {
				for (int x = -M; x < 16 + M; x++) {
					boolean inside;
					if (x >= -1 && x <= 16 && z >= -1 && z <= 16) {
						inside = t.building[ChunkTerrain.index(x, z)] == index + 1;
					} else {
						inside = p.contains(baseX + x, baseZ + z);
					}
					in[(z + M) * W + (x + M)] = inside;
					any |= inside && x >= 0 && x < 16 && z >= 0 && z < 16;
				}
			}
			if (any) {
				build(chunk, t, p, palette(p), in, baseX, baseZ, top, beds);
			}
		}
		t.beds = List.copyOf(beds);
		return top;
	}

	private static boolean in(boolean[] in, int x, int z) {
		return x >= -M && x < 16 + M && z >= -M && z < 16 + M && in[(z + M) * W + (x + M)];
	}

	private static boolean isWall(boolean[] in, int x, int z) {
		if (!in(in, x, z)) {
			return false;
		}
		for (int d = 0; d < 4; d++) {
			if (!in(in, x + DX[d], z + DZ[d])) {
				return true;
			}
		}
		return false;
	}

	/** For a room column next to a wall: the direction of that wall, or -1. */
	private static int wallSide(boolean[] in, int x, int z) {
		if (!in(in, x, z) || isWall(in, x, z)) {
			return -1;
		}
		for (int d = 0; d < 4; d++) {
			if (isWall(in, x + DX[d], z + DZ[d])) {
				return d;
			}
		}
		return -1;
	}

	private static final double LADDER = 0.025;

	private static boolean isLadder(BuildingPlan p, boolean[] in, int x, int z, int wx, int wz) {
		return p.floors > 1 && wallSide(in, x, z) >= 0 && p.hash(wx, 0, wz, 2) < LADDER && !nearDoor(p, wx, wz);
	}

	private static boolean nearDoor(BuildingPlan p, int wx, int wz) {
		return p.nearDoor(wx, wz);
	}

	private enum Item { NONE, BED, CHEST, CRAFTING, FURNACE, BARREL, BOOKSHELF, JOB, FLOWER }

	/** What stands against the wall at a room column on a floor. */
	private static Item item(BuildingPlan p, boolean[] in, int x, int z, int wx, int wz, int floor) {
		if (wallSide(in, x, z) < 0 || isLadder(p, in, x, z, wx, wz) || (floor == 0 && nearDoor(p, wx, wz))) {
			return Item.NONE;
		}
		// Beds and chests are drawn as animated models and furnaces tick, so they are kept few: a city has
		// thousands of rooms in view. Plain blocks (barrels, bookshelves, crafting tables) carry the rest.
		double h = p.hash(wx, floor, wz, 1);
		if (h < 0.012) {
			return Item.BED;
		}
		if (h < 0.018) {
			return Item.CHEST;
		}
		if (h < 0.05) {
			return Item.CRAFTING;
		}
		if (h < 0.058) {
			return Item.FURNACE;
		}
		if (h < 0.10) {
			return Item.BARREL;
		}
		if (h < 0.16) {
			return Item.BOOKSHELF;
		}
		if (h < 0.18) {
			return Item.JOB;
		}
		if (h < 0.22) {
			return Item.FLOWER;
		}
		return Item.NONE;
	}

	private static void build(ChunkAccess chunk, ChunkTerrain t, BuildingPlan p, Palette pal, boolean[] in, int baseX, int baseZ, int[] top,
		List<int[]> beds) {
		int floorY = p.floorY;
		int storey = p.storey;
		int roofBase = p.roofBaseY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				if (!in(in, x, z)) {
					continue;
				}
				int wx = baseX + x;
				int wz = baseZ + z;
				int k = ChunkTerrain.index(x, z);
				int ground = Math.max(t.topSolidY[k], t.waterTopY[k]);
				boolean wall = isWall(in, x, z);
				boolean eastWest = !in(in, x - 1, z) || !in(in, x + 1, z);
				boolean northSouth = !in(in, x, z - 1) || !in(in, x, z + 1);
				boolean corner = wall && eastWest && northSouth;
				int doorFacing = p.doorAt(wx, wz);
				boolean door = doorFacing >= 0;
				boolean ladder = isLadder(p, in, x, z, wx, wz);
				int highest = floorY;

				// Foundation up from the ground (or the water), and the ground floor.
				for (int y = Math.min(ground, floorY) - 2; y < floorY; y++) {
					set(chunk, pos, x, y, z, pal.foundation);
				}
				set(chunk, pos, x, floorY, z, wall ? pal.foundation : pal.floor);

				for (int f = 0; f < p.floors; f++) {
					int base = floorY + f * storey;
					for (int dy = 1; dy < storey; dy++) {
						int y = base + dy;
						BlockState s;
						if (wall) {
							if (door && f == 0 && dy <= 2) {
								s = doorHalf(pal.door, doorFacing, dy == 1);
							} else if (!corner && !door && (dy == 2 || dy == 3) && window(wx, wz, eastWest)) {
								s = pane(pal.window, eastWest);
							} else {
								s = corner ? pal.corner : pal.wall;
							}
						} else if (ladder) {
							s = ladder(wallSide(in, x, z));
						} else {
							s = AIR;
						}
						set(chunk, pos, x, y, z, s);
					}
					if (f < p.floors - 1) {
						set(chunk, pos, x, base + storey, z, ladder ? ladder(wallSide(in, x, z)) : wall ? pal.wall : pal.floor);
					}
				}

				// Roof.
				if (p.roofRise == 0) {
					set(chunk, pos, x, roofBase, z, wall ? pal.trim : ladder ? ladder(wallSide(in, x, z)) : pal.flatRoof);
					highest = roofBase;
					if (wall) {
						set(chunk, pos, x, roofBase + 1, z, pal.trim);
						highest = roofBase + 1;
					}
				} else {
					set(chunk, pos, x, roofBase, z, wall ? pal.wall : pal.floor);
					int level = (int) Math.min(p.roofRise, Math.floor(p.edgeDistance(wx, wz)));
					for (int y = roofBase + 1; y < roofBase + level; y++) {
						set(chunk, pos, x, y, z, AIR);
					}
					int dir = uphill(p, wx, wz);
					BlockState roof = level >= p.roofRise || dir < 0 ? pal.roofFull : with(state(pal.stairs), BlockStateProperties.HORIZONTAL_FACING, DIRS[dir]);
					set(chunk, pos, x, roofBase + Math.max(level, 1), z, roof);
					highest = roofBase + Math.max(level, 1);
				}
				// Whatever ground stood above the building is dug away.
				for (int y = highest + 1; y <= ground; y++) {
					set(chunk, pos, x, y, z, AIR);
				}
				top[z * 16 + x] = Math.max(highest, top[z * 16 + x]);
			}
		}

		// Furniture, floor by floor, against the walls; and lanterns under the ceilings.
		for (int f = 0; f < p.floors; f++) {
			int y = floorY + f * storey + 1;
			int ceiling = floorY + (f + 1) * storey;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (!in(in, x, z) || isWall(in, x, z)) {
						continue;
					}
					int wx = baseX + x;
					int wz = baseZ + z;
					if (Math.floorMod(wx, 6) == 3 && Math.floorMod(wz, 6) == 3 && !isLadder(p, in, x, z, wx, wz)) {
						set(chunk, pos, x, ceiling - 1, z, with(state(Blocks.LANTERN), BlockStateProperties.HANGING, true));
					}
					Item item = item(p, in, x, z, wx, wz, f);
					if (item == Item.NONE) {
						continue;
					}
					int side = wallSide(in, x, z);
					Direction away = DIRS[(side + 2) % 4];
					switch (item) {
						case BED -> {
							int fx = x - DX[side];
							int fz = z - DZ[side];
							if (fx < 0 || fx > 15 || fz < 0 || fz > 15 || !in(in, fx, fz) || isWall(in, fx, fz)
								|| item(p, in, fx, fz, baseX + fx, baseZ + fz, f) != Item.NONE || isLadder(p, in, fx, fz, baseX + fx, baseZ + fz)
								|| (f == 0 && nearDoor(p, baseX + fx, baseZ + fz))) {
								continue;
							}
							Block bed = BEDS[(int) (p.hash(0, f, 0, 5) * BEDS.length)];
							Direction head = DIRS[side];
							set(chunk, pos, x, y, z, with(with(state(bed), BlockStateProperties.HORIZONTAL_FACING, head), BlockStateProperties.BED_PART, BedPart.HEAD));
							set(chunk, pos, fx, y, fz, with(with(state(bed), BlockStateProperties.HORIZONTAL_FACING, head), BlockStateProperties.BED_PART, BedPart.FOOT));
							beds.add(new int[] {baseX + fx, y, baseZ + fz});
						}
						case CHEST -> set(chunk, pos, x, y, z, with(state(Blocks.CHEST), BlockStateProperties.HORIZONTAL_FACING, away));
						case CRAFTING -> set(chunk, pos, x, y, z, state(Blocks.CRAFTING_TABLE));
						case FURNACE -> set(chunk, pos, x, y, z, with(state(Blocks.FURNACE), BlockStateProperties.HORIZONTAL_FACING, away));
						case BARREL -> set(chunk, pos, x, y, z, with(state(Blocks.BARREL), BlockStateProperties.FACING, Direction.UP));
						case BOOKSHELF -> {
							set(chunk, pos, x, y, z, state(Blocks.BOOKSHELF));
							if (p.storey > 3) {
								set(chunk, pos, x, y + 1, z, state(Blocks.BOOKSHELF));
							}
						}
						case JOB -> {
							Block job = JOBS[(int) (p.hash(wx, f, wz, 6) * JOBS.length)];
							set(chunk, pos, x, y, z, with(state(job), BlockStateProperties.HORIZONTAL_FACING, away));
						}
						case FLOWER -> set(chunk, pos, x, y, z, state(FLOWERS[(int) (p.hash(wx, f, wz, 7) * FLOWERS.length)]));
						default -> {
						}
					}
				}
			}
		}

		// A step up to the front door, or a cut down to it, from the street side.
		for (int[] d : p.doors) {
			int ox = d[0] + DX[d[2]] - baseX;
			int oz = d[1] + DZ[d[2]] - baseZ;
			if (ox >= 0 && ox < 16 && oz >= 0 && oz < 16 && !in(in, ox, oz)) {
				int k = ChunkTerrain.index(ox, oz);
				int ground = t.topSolidY[k];
				for (int y = ground + 1; y <= floorY; y++) {
					set(chunk, pos, ox, y, oz, pal.foundation);
				}
				for (int y = floorY + 1; y <= Math.max(ground, floorY + 2); y++) {
					set(chunk, pos, ox, y, oz, AIR);
				}
				top[oz * 16 + ox] = Math.max(top[oz * 16 + ox], floorY);
			}
		}
	}

	/** Windows two of every three blocks along a wall, two blocks tall. */
	private static boolean window(int wx, int wz, boolean eastWest) {
		return Math.floorMod(eastWest ? wz : wx, 3) != 0;
	}

	private static BlockState pane(BlockState pane, boolean eastWest) {
		// The wall runs north-south where its outside is east or west: panes join north and south.
		return eastWest
			? pane.setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true)
			: pane.setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true);
	}

	private static BlockState doorHalf(Block door, int facing, boolean lower) {
		// The door faces into the building, as it does for someone walking in.
		BlockState s = with(state(door), BlockStateProperties.HORIZONTAL_FACING, DIRS[(facing + 2) % 4]);
		return with(s, BlockStateProperties.DOUBLE_BLOCK_HALF, lower ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
	}

	private static BlockState ladder(int wallSide) {
		// A ladder hangs on the wall behind it and faces away from it.
		return with(state(Blocks.LADDER), BlockStateProperties.HORIZONTAL_FACING, DIRS[(Math.max(0, wallSide) + 2) % 4]);
	}

	/** Direction in which the roof rises at a column (towards the ridge), or -1 on the ridge. */
	private static int uphill(BuildingPlan p, int wx, int wz) {
		double here = p.edgeDistance(wx, wz);
		int best = -1;
		double bestRise = 0.25;
		for (int d = 0; d < 4; d++) {
			double rise = p.edgeDistance(wx + DX[d], wz + DZ[d]) - here;
			if (rise > bestRise) {
				bestRise = rise;
				best = d;
			}
		}
		return best;
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
