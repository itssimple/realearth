package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import se.itssimple.realearth.terrain.BuildingPlan;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.Interior;

/**
 * Builds a chunk's share of its buildings: foundation, floors, outer walls with windows and corner
 * posts, a roof; and inside, as the building's {@link Interior} lays it out, the walls between flats and
 * rooms with their doors, street doors into stairwells and shops, turning stairs between storeys, and
 * furniture by room -- beds, stoves, pantries, tables, rugs, bookshelves, a villager's job block, shop
 * goods -- with lanterns under the ceilings.
 *
 * Chests, pantry barrels and shop stock get a loot table rather than items, so they fill when first
 * opened, the way village chests do; Lootr, when installed, turns them into a chest per player. Barrels
 * that are a villager's job block stay empty.
 *
 * Everything is decided from the building's plan and the block position alone, so neighbouring chunks
 * agree on the parts of a building they share: a bed or a flight of stairs across a chunk border is
 * built half by each.
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
	private static final Block[] RUGS = {Blocks.RED_CARPET, Blocks.BLUE_CARPET, Blocks.GREEN_CARPET, Blocks.BROWN_CARPET, Blocks.LIGHT_GRAY_CARPET,
		Blocks.CYAN_CARPET, Blocks.YELLOW_CARPET, Blocks.WHITE_CARPET};
	private static final Block[] WOOL = {Blocks.WHITE_WOOL, Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.BLACK_WOOL, Blocks.BROWN_WOOL};

	/** Household goods in the storerooms' barrels: food, fuel and odds and ends. A datapack can change it. */
	private static final ResourceKey<LootTable> STOREROOM = loot("chests/storeroom");
	/** Share of bookshelves that are chiseled ones, holding a few books. */
	private static final double CHISELED = 0.1;
	/** Share of shop stock that is a barrel of it, the rest set out for show; a block entity each. */
	private static final double STOCK_BARRELS = 0.25;
	/** Share of furnaces with some fuel left in them. */
	private static final double FUELLED = 0.4;

	private static ResourceKey<LootTable> loot(String path) {
		return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("realearth", path));
	}

	/** A house chest holds what a village house's chest of the same climate holds. */
	private static ResourceKey<LootTable> houseLoot(BuildingPlan.Style style) {
		return switch (style) {
			case PLAINS -> BuiltInLootTables.VILLAGE_PLAINS_HOUSE;
			case TAIGA -> BuiltInLootTables.VILLAGE_TAIGA_HOUSE;
			case SNOWY -> BuiltInLootTables.VILLAGE_SNOWY_HOUSE;
			case DESERT -> BuiltInLootTables.VILLAGE_DESERT_HOUSE;
			case SAVANNA -> BuiltInLootTables.VILLAGE_SAVANNA_HOUSE;
		};
	}

	/** What a shop's stock holds: its own table (realearth:chests/shop_*), a datapack can change them. */
	private static ResourceKey<LootTable> stockLoot(BuildingPlan.Trade trade) {
		if (trade == null) {
			return STOREROOM;
		}
		return switch (trade) {
			case CHURCH -> BuiltInLootTables.VILLAGE_TEMPLE;
			case MARKET -> loot("chests/shop_grocery");
			case LIBRARY, SCHOOL -> loot("chests/shop_books");
			case OFFICE -> STOREROOM;
			default -> loot("chests/shop_" + trade.name().toLowerCase(java.util.Locale.ROOT));
		};
	}

	/** The workstation of whoever works in a shop or hall: the trade a villager takes up there. */
	private static Block job(BuildingPlan.Trade trade) {
		if (trade == null) {
			return Blocks.BLAST_FURNACE;
		}
		return switch (trade) {
			case BAKERY, GROCERY, FLOWERS, MARKET -> Blocks.COMPOSTER;
			case BUTCHER, FOOD -> Blocks.SMOKER;
			case CLOTHES -> Blocks.LOOM;
			case BOOKS, LIBRARY, SCHOOL -> Blocks.LECTERN;
			case HARDWARE -> Blocks.SMITHING_TABLE;
			case PHARMACY, CHURCH -> Blocks.BREWING_STAND;
			case GENERAL, OFFICE -> Blocks.CARTOGRAPHY_TABLE;
		};
	}

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

	/** Walls inside: plaster-white in towns, timber in houses. */
	private static BlockState partition(BuildingPlan p) {
		boolean big = p.floors >= 3 || p.use != BuildingPlan.Use.HOUSE;
		return switch (p.style) {
			case DESERT -> state(Blocks.SMOOTH_SANDSTONE);
			case SAVANNA -> state(big ? Blocks.WHITE_TERRACOTTA : Blocks.ACACIA_PLANKS);
			case TAIGA, SNOWY -> state(big ? Blocks.WHITE_TERRACOTTA : Blocks.SPRUCE_PLANKS);
			case PLAINS -> state(big ? Blocks.WHITE_TERRACOTTA : Blocks.BIRCH_PLANKS);
		};
	}

	/** Signs in the floor's wood, birch in deserts. */
	static Block wallSign(BuildingPlan p) {
		return switch (p.style) {
			case DESERT -> Blocks.BIRCH_WALL_SIGN;
			case SAVANNA -> Blocks.ACACIA_WALL_SIGN;
			case TAIGA, SNOWY -> Blocks.SPRUCE_WALL_SIGN;
			case PLAINS -> Blocks.OAK_WALL_SIGN;
		};
	}

	/** The steps of stairs inside, pews too: the floor's wood. */
	private static Block steps(BuildingPlan p) {
		return switch (p.style) {
			case DESERT -> Blocks.SMOOTH_SANDSTONE_STAIRS;
			case SAVANNA -> Blocks.ACACIA_STAIRS;
			case TAIGA, SNOWY -> Blocks.SPRUCE_STAIRS;
			case PLAINS -> Blocks.OAK_STAIRS;
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

	private static void build(ChunkAccess chunk, ChunkTerrain t, BuildingPlan p, Palette pal, boolean[] in, int baseX, int baseZ, int[] top,
		List<int[]> beds) {
		Interior inside = p.interior();
		BlockState partition = partition(p);
		Block steps = steps(p);
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
				Interior.Stairs shaft = wall ? null : inside.shaftAt(wx, wz);
				int ladder = wall ? -1 : inside.ladderAt(wx, wz);
				int highest = floorY;

				// Foundation up from the ground (or the water), and the ground floor.
				for (int y = Math.min(ground, floorY) - 2; y < floorY; y++) {
					set(chunk, pos, x, y, z, pal.foundation);
				}
				set(chunk, pos, x, floorY, z, wall ? pal.foundation : pal.floor);

				for (int f = 0; f < p.floors; f++) {
					int base = floorY + f * storey;
					byte code = inside.code(wx, wz, f);
					Interior.Door door = inside.doorAt(wx, wz, f);
					for (int dy = 1; dy < storey; dy++) {
						int y = base + dy;
						BlockState s;
						if (wall) {
							if (door != null && door.entrance() && dy <= 2) {
								s = doorHalf(pal.door, door.facing(), dy == 1);
							} else if (!corner && door == null && (dy == 2 || dy == 3) && window(wx, wz, eastWest)) {
								s = pane(pal.window, eastWest);
							} else {
								s = corner ? pal.corner : pal.wall;
							}
						} else if (shaft != null) {
							s = step(inside.stepAt(shaft, wx, wz, y, floorY, storey, p.floors), steps, pal.floor);
						} else if (code == Interior.WALL) {
							s = door != null && dy <= 2 ? doorHalf(pal.door, door.facing(), dy == 1) : partition;
						} else if (ladder >= 0) {
							s = ladder(ladder);
						} else {
							s = AIR;
						}
						set(chunk, pos, x, y, z, s);
					}
					if (f < p.floors - 1) {
						int y = base + storey;
						BlockState s = wall ? pal.wall
							: shaft != null ? step(inside.stepAt(shaft, wx, wz, y, floorY, storey, p.floors), steps, pal.floor)
							: ladder >= 0 ? ladder(ladder) : pal.floor;
						set(chunk, pos, x, y, z, s);
					}
				}

				// Roof.
				if (p.roofRise == 0) {
					set(chunk, pos, x, roofBase, z, wall ? pal.trim : pal.flatRoof);
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
					// Where the next column's roof is two or more blocks lower (a wall at a slant), close
					// the step under this one, or the roof has a gap and its blocks hang alone.
					int lowest = level;
					for (int d = 0; d < 4; d++) {
						if (p.contains(wx + DX[d], wz + DZ[d])) {
							lowest = Math.min(lowest, (int) Math.min(p.roofRise, Math.floor(p.edgeDistance(wx + DX[d], wz + DZ[d]))));
						}
					}
					for (int y = roofBase + Math.max(1, lowest + 1); y < roofBase + Math.max(level, 1); y++) {
						set(chunk, pos, x, y, z, pal.roofFull);
					}
					highest = roofBase + Math.max(level, 1);
				}
				// Whatever ground stood above the building is dug away.
				for (int y = highest + 1; y <= ground; y++) {
					set(chunk, pos, x, y, z, AIR);
				}
				top[z * 16 + x] = Math.max(highest, top[z * 16 + x]);
			}
		}

		// Furniture, floor by floor, as the rooms want it; and lanterns under the ceilings.
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
					byte code = inside.code(wx, wz, f);
					if (code != Interior.ROOM && code != Interior.LANDING || inside.ladderAt(wx, wz) >= 0) {
						continue;
					}
					if (Math.floorMod(wx, 6) == 3 && Math.floorMod(wz, 6) == 3) {
						set(chunk, pos, x, ceiling - 1, z, with(state(Blocks.LANTERN), BlockStateProperties.HANGING, true));
					}
					Interior.Placed placed = inside.placedAt(wx, wz, f);
					if (placed != null) {
						furnish(chunk, pos, x, y, z, wx, wz, f, p, inside, placed, steps, beds);
					}
				}
			}
		}

		// A step up to each street door, or a cut down to it, from the street side; over a shop's or
		// hall's door, its name.
		for (Interior.Door d : inside.doors) {
			if (!d.entrance()) {
				continue;
			}
			int ox = d.x() + DX[d.facing()] - baseX;
			int oz = d.z() + DZ[d.facing()] - baseZ;
			if (ox >= 0 && ox < 16 && oz >= 0 && oz < 16 && !in(in, ox, oz) && in(in, d.x() - baseX, d.z() - baseZ)) {
				int k = ChunkTerrain.index(ox, oz);
				int ground = t.topSolidY[k];
				for (int y = ground + 1; y <= floorY; y++) {
					set(chunk, pos, ox, y, oz, pal.foundation);
				}
				for (int y = floorY + 1; y <= Math.max(ground, floorY + 2); y++) {
					set(chunk, pos, ox, y, oz, AIR);
				}
				top[oz * 16 + ox] = Math.max(top[oz * 16 + ox], floorY);
				Interior.Room room = inside.roomAt(d.x() - DX[d.facing()], d.z() - DZ[d.facing()], 0);
				int signY = floorY + 3;
				// Not where a building next door stands: it is built over the sign, block entity and all.
				if (room != null && room.name != null && (room.kind == Interior.Kind.SHOP || room.kind == Interior.Kind.HALLROOM)
					&& t.building[k] == 0 && chunk.getBlockState(pos.set(ox, signY, oz)).isAir()) {
					Signs.write(set(chunk, pos, ox, signY, oz, with(state(wallSign(p)), BlockStateProperties.HORIZONTAL_FACING, DIRS[d.facing()])), room.name);
				}
			}
		}
	}

	/** One thing in a room, standing on the floor at y. */
	private static void furnish(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, int wx, int wz, int f, BuildingPlan p,
		Interior inside, Interior.Placed placed, Block steps, List<int[]> beds) {
		Direction away = DIRS[placed.facing()];
		Interior.Room room = inside.roomAt(wx, wz, f);
		BuildingPlan.Trade trade = room == null ? null : room.trade;
		double roll = placed.roll();
		switch (placed.item()) {
			case BED -> {
				int colour = (int) (p.hash(room == null ? 0 : room.id, f, 0, 5) * BEDS.length);
				// The head stands against the wall (facing it), the foot one block out.
				boolean foot = roll < 0;
				double look = foot ? -1 - roll : roll;
				if (look >= Interior.REAL_BED) {
					// Made up of wool, under a blanket: a bed to look at, not a block entity.
					set(chunk, pos, x, y, z, state(foot ? WOOL[0] : Blocks.WHITE_WOOL));
					set(chunk, pos, x, y + 1, z, state(foot ? RUGS[colour % RUGS.length] : Blocks.WHITE_CARPET));
					break;
				}
				Direction head = foot ? DIRS[placed.facing()] : DIRS[(placed.facing() + 2) % 4];
				set(chunk, pos, x, y, z, with(with(state(BEDS[colour]), BlockStateProperties.HORIZONTAL_FACING, head), BlockStateProperties.BED_PART,
					foot ? BedPart.FOOT : BedPart.HEAD));
				if (foot) {
					beds.add(new int[] {wx, y, wz});
				}
			}
			case CHEST -> loot(set(chunk, pos, x, y, z, with(state(Blocks.CHEST), BlockStateProperties.HORIZONTAL_FACING, away)),
				trade == BuildingPlan.Trade.CHURCH ? BuiltInLootTables.VILLAGE_TEMPLE : houseLoot(p.style), p, wx, y, wz);
			case BARREL -> loot(set(chunk, pos, x, y, z, with(state(Blocks.BARREL), BlockStateProperties.FACING, Direction.UP)), STOREROOM, p, wx, y, wz);
			case STOCK -> stock(chunk, pos, x, y, z, wx, wz, p, trade, roll, away);
			case CRATE -> {
				// Stores two high: hay and timber mostly, a barrel of goods now and then.
				if (roll < STOCK_BARRELS / 2) {
					loot(set(chunk, pos, x, y, z, with(state(Blocks.BARREL), BlockStateProperties.FACING, Direction.UP)), STOREROOM, p, wx, y, wz);
				} else {
					set(chunk, pos, x, y, z, state(roll < 0.6 ? Blocks.HAY_BLOCK : Blocks.STRIPPED_SPRUCE_LOG));
				}
				set(chunk, pos, x, y + 1, z, state(roll < 0.4 ? Blocks.HAY_BLOCK : Blocks.STRIPPED_OAK_LOG));
			}
			case CRAFTING -> set(chunk, pos, x, y, z, state(Blocks.CRAFTING_TABLE));
			case FURNACE, SMOKER -> {
				BlockEntity furnace = set(chunk, pos, x, y, z, with(state(placed.item() == Interior.Item.FURNACE ? Blocks.FURNACE : Blocks.SMOKER),
					BlockStateProperties.HORIZONTAL_FACING, away));
				if (furnace instanceof AbstractFurnaceBlockEntity fe && roll < FUELLED) {
					// Slot 1 is the fuel.
					fe.setItem(1, new ItemStack(roll < FUELLED / 2 ? Items.COAL : Items.CHARCOAL, 1 + (int) (roll / FUELLED * 8) % 4));
				}
			}
			case BOOKSHELF -> {
				bookshelf(chunk, pos, x, y, z, away, p.hash(wx, f, wz, 8));
				if (p.storey > 3) {
					bookshelf(chunk, pos, x, y + 1, z, away, p.hash(wx, f + 64, wz, 8));
				}
			}
			case JOB -> {
				Block job = room != null && (room.kind == Interior.Kind.SHOP || room.kind == Interior.Kind.HALLROOM) ? job(trade)
					: JOBS[(int) (p.hash(wx, f, wz, 6) * JOBS.length)];
				set(chunk, pos, x, y, z, with(state(job), BlockStateProperties.HORIZONTAL_FACING, away));
			}
			case FLOWER -> set(chunk, pos, x, y, z, state(FLOWERS[(int) (p.hash(wx, f, wz, 7) * FLOWERS.length)]));
			case TABLE -> {
				set(chunk, pos, x, y, z, state(Blocks.OAK_FENCE));
				set(chunk, pos, x, y + 1, z, state(Blocks.OAK_PRESSURE_PLATE));
			}
			case RUG -> set(chunk, pos, x, y, z, state(RUGS[(int) (roll * RUGS.length)]));
			case CAULDRON -> set(chunk, pos, x, y, z, state(Blocks.CAULDRON));
			case PEW -> set(chunk, pos, x, y, z, with(state(steps), BlockStateProperties.HORIZONTAL_FACING, DIRS[placed.facing()]));
			case ALTAR -> set(chunk, pos, x, y, z, state(Blocks.BREWING_STAND));
		}
	}

	/** Shop goods: mostly barrels of its stock, with what the trade shows off between them. */
	private static void stock(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, int wx, int wz, BuildingPlan p,
		BuildingPlan.Trade trade, double roll, Direction away) {
		if (roll < STOCK_BARRELS || trade == null) {
			loot(set(chunk, pos, x, y, z, with(state(Blocks.BARREL), BlockStateProperties.FACING, Direction.UP)), stockLoot(trade), p, wx, y, wz);
			return;
		}
		// What the trade sets out, none of it a block entity.
		double t = (roll - STOCK_BARRELS) / (1 - STOCK_BARRELS);
		BlockState show = switch (trade) {
			case GROCERY, MARKET -> state(t < 0.4 ? Blocks.MELON : t < 0.7 ? Blocks.PUMPKIN : Blocks.HAY_BLOCK);
			case BAKERY -> state(t < 0.6 ? Blocks.HAY_BLOCK : Blocks.CAKE);
			case CLOTHES -> state(WOOL[(int) (t * WOOL.length) % WOOL.length]);
			case BOOKS, LIBRARY, SCHOOL -> state(Blocks.BOOKSHELF);
			case FLOWERS -> state(FLOWERS[(int) (t * FLOWERS.length) % FLOWERS.length]);
			case HARDWARE -> state(t < 0.5 ? Blocks.GRINDSTONE : t < 0.8 ? Blocks.STONECUTTER : Blocks.ANVIL);
			case PHARMACY -> state(t < 0.5 ? Blocks.CAULDRON : Blocks.FLOWER_POT);
			case BUTCHER -> state(t < 0.5 ? Blocks.HAY_BLOCK : Blocks.CAULDRON);
			case FOOD -> state(t < 0.5 ? Blocks.CRAFTING_TABLE : Blocks.CAULDRON);
			default -> state(t < 0.5 ? Blocks.BOOKSHELF : Blocks.CRAFTING_TABLE);
		};
		set(chunk, pos, x, y, z, with(show, BlockStateProperties.HORIZONTAL_FACING, away));
	}

	/** A block of a stair shaft. */
	private static BlockState step(Interior.Step step, Block steps, BlockState floor) {
		return switch (step.kind()) {
			case Interior.Step.STAIR -> with(state(steps), BlockStateProperties.HORIZONTAL_FACING, DIRS[step.facing()]);
			case Interior.Step.SOLID -> floor;
			default -> AIR;
		};
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

	/**
	 * Gives a container its loot table, with a seed from its place so the same chest always rolls the
	 * same loot. Lootr ignores that seed by default and rolls each player their own.
	 */
	private static void loot(BlockEntity container, ResourceKey<LootTable> table, BuildingPlan p, int wx, int y, int wz) {
		if (container instanceof RandomizableContainerBlockEntity c) {
			c.setLootTable(table, BlockPos.asLong(wx, y, wz) ^ p.id * 0x9E3779B97F4A7C15L | 1);
		}
	}

	/** A plain bookshelf, or now and then a chiseled one with one to five books on it. */
	private static void bookshelf(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, Direction facing, double h) {
		if (h >= CHISELED) {
			set(chunk, pos, x, y, z, state(Blocks.BOOKSHELF));
			return;
		}
		// Which of the six slots hold a book, from the rest of the hash; never none.
		int slots = (int) (h / CHISELED * 64) & 0b111111;
		if (slots == 0 || slots == 0b111111) {
			slots = 0b010110;
		}
		BlockState s = with(state(Blocks.CHISELED_BOOKSHELF), BlockStateProperties.HORIZONTAL_FACING, facing);
		for (int i = 0; i < 6; i++) {
			s = s.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), (slots >> i & 1) == 1);
		}
		// The block's state shows the books; the block entity holds them. Set straight into its list:
		// setItem would update the block in a level the chunk isn't in yet.
		if (set(chunk, pos, x, y, z, s) instanceof ChiseledBookShelfBlockEntity shelf) {
			for (int i = 0; i < 6; i++) {
				if ((slots >> i & 1) == 1) {
					shelf.getItems().set(i, new ItemStack(Items.BOOK));
				}
			}
		}
	}

	/** Sets a block, and its block entity when it has one; returns that block entity, or null. */
	private static BlockEntity set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
		if (y <= chunk.getMinY() || y >= chunk.getMaxY()) {
			return null;
		}
		chunk.setBlockState(pos.set(x, y, z), s, 0);
		// Blocks set straight into a chunk get no block entity of their own: a bed, chest or bell would
		// be invisible or dead. Make it here, at the block's place in the world.
		if (s.hasBlockEntity() && s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
			BlockPos at = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
			var entity = entityBlock.newBlockEntity(at, s);
			if (entity != null) {
				chunk.setBlockEntity(entity);
				return entity;
			}
		}
		return null;
	}
}
