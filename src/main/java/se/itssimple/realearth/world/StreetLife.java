package se.itssimple.realearth.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import se.itssimple.realearth.osm.Ways;
import se.itssimple.realearth.terrain.ChunkTerrain;

/**
 * Street furniture where the map has it, and parks with what parks have. The map's points of interest
 * stand where they are mapped: bins, drinking fountains, bus stops with their names, statues and
 * monuments, picnic tables, sandpits and market stalls. In parks: benches along the paths, lamps, flower
 * beds, and in the bigger ones a fountain. Boats and minecarts come later, with the chunk's mobs (see
 * {@link #entities}).
 */
final class StreetLife {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};
	private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	/** Chance a park column beside a path gets a bench, a lamp, and a park column a flower. */
	private static final double BENCH_CHANCE = 0.05;
	private static final double PARK_LAMP_CHANCE = 0.025;
	private static final double FLOWER_CHANCE = 0.03;
	/** Chance a chunk whose middle is open park gets a fountain there: a park has one or two. */
	private static final double FOUNTAIN_CHANCE = 0.1;
	private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.RED_TULIP, Blocks.PINK_TULIP,
		Blocks.ALLIUM, Blocks.AZURE_BLUET};
	private static final Block[] AWNINGS = {Blocks.RED_WOOL, Blocks.WHITE_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.ORANGE_WOOL};
	private static final Block[] PRODUCE = {Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.COMPOSTER, Blocks.CARVED_PUMPKIN};

	private StreetLife() {
	}

	static void place(ChunkAccess chunk, ChunkTerrain t, int baseX, int baseZ, int[] buildingTop) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (ChunkTerrain.Poi poi : t.pois) {
			int x = poi.x() - baseX;
			int z = poi.z() - baseZ;
			String what = poi.subclass() == null ? poi.cls() : poi.subclass();
			switch (what == null ? "" : what) {
				case "waste_basket" -> single(chunk, t, buildingTop, pos, x, z, Blocks.CAULDRON.defaultBlockState());
				case "drinking_water" -> single(chunk, t, buildingTop, pos, x, z,
					Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
				case "bus_stop", "tram_stop" -> stop(chunk, t, buildingTop, pos, x, z, poi.name());
				case "artwork" -> statue(chunk, t, buildingTop, pos, x, z);
				case "monument", "memorial" -> monument(chunk, t, buildingTop, pos, x, z);
				case "picnic_site", "picnic_table" -> picnic(chunk, t, buildingTop, pos, x, z);
				case "playground" -> sandpit(chunk, t, buildingTop, pos, x, z);
				case "marketplace" -> market(chunk, t, buildingTop, pos, x, z, baseX, baseZ);
				default -> {
				}
			}
		}
		park(chunk, t, buildingTop, pos, baseX, baseZ);
	}

	/** Open ground: in this chunk, dry, no building, no bridge deck, nothing built on it already. */
	private static boolean open(ChunkTerrain t, int[] buildingTop, int x, int z) {
		if (x < 0 || x > 15 || z < 0 || z > 15) {
			return false;
		}
		int k = ChunkTerrain.index(x, z);
		return t.building[k] == 0 && t.waterTopY[k] == Integer.MIN_VALUE && t.deckY[k] == Integer.MIN_VALUE && buildingTop[z * 16 + x] == Integer.MIN_VALUE;
	}

	/** Open ground off the road: the column itself, or the nearest beside it that is not a way. */
	private static int[] offRoad(ChunkTerrain t, int[] buildingTop, int x, int z) {
		for (int r = 0; r <= 2; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == r && open(t, buildingTop, x + dx, z + dz)
						&& t.way[ChunkTerrain.index(x + dx, z + dz)] == Ways.NONE) {
						return new int[] {x + dx, z + dz};
					}
				}
			}
		}
		return null;
	}

	private static int ground(ChunkTerrain t, int x, int z) {
		return t.topSolidY[ChunkTerrain.index(x, z)];
	}

	/** The side of a column a paved street or path is on, or -1. */
	private static int wayBeside(ChunkTerrain t, int x, int z) {
		for (int d = 0; d < 4; d++) {
			int nx = x + DX[d];
			int nz = z + DZ[d];
			if (nx >= -1 && nx <= 16 && nz >= -1 && nz <= 16 && t.way[ChunkTerrain.index(nx, nz)] != Ways.NONE && !Ways.isRail(t.way[ChunkTerrain.index(nx, nz)])) {
				return d;
			}
		}
		return -1;
	}

	/** The side of a park column the park ends on toward open ground (not a wall or water), or -1. */
	private static int edge(ChunkTerrain t, int x, int z) {
		for (int d = 0; d < 4; d++) {
			int n = ChunkTerrain.index(x + DX[d], z + DZ[d]);
			if (!t.park[n] && t.building[n] == 0 && t.waterTopY[n] == Integer.MIN_VALUE) {
				return d;
			}
		}
		return -1;
	}

	private static void single(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z, BlockState s) {
		int[] at = offRoad(t, buildingTop, x, z);
		if (at != null) {
			set(chunk, pos, at[0], ground(t, at[0], at[1]) + 1, at[1], s);
		}
	}

	/** A bus or tram stop: a post with the stop's name, and a bench beside it facing the street. */
	private static void stop(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z, String name) {
		int[] at = offRoad(t, buildingTop, x, z);
		if (at == null) {
			return;
		}
		int y = ground(t, at[0], at[1]);
		int street = wayBeside(t, at[0], at[1]);
		set(chunk, pos, at[0], y + 1, at[1], Blocks.SPRUCE_FENCE.defaultBlockState());
		if (name != null) {
			int facing = street < 0 ? 2 : street;
			Signs.write(set(chunk, pos, at[0], y + 2, at[1], Blocks.SPRUCE_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rotation(facing))),
				name);
		} else {
			set(chunk, pos, at[0], y + 2, at[1], Blocks.LANTERN.defaultBlockState());
		}
		if (street >= 0) {
			// Along the kerb, one step on: its back to the houses, its seat to the street.
			int side = (street + 1) % 4;
			int bx = at[0] + DX[side];
			int bz = at[1] + DZ[side];
			if (open(t, buildingTop, bx, bz) && t.way[ChunkTerrain.index(bx, bz)] == Ways.NONE) {
				bench(chunk, pos, bx, ground(t, bx, bz), bz, (street + 2) % 4);
			}
		}
	}

	/** A bench: a stair whose back is toward {@code back} (direction index). */
	private static void bench(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, int back) {
		set(chunk, pos, x, y + 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, DIRS[back]));
	}

	/** A sign's rotation (sixteenths of a turn from south) to face a direction index. */
	private static int rotation(int facing) {
		return switch (facing) {
			case 0 -> 8;
			case 1 -> 12;
			case 2 -> 0;
			default -> 4;
		};
	}

	/** A statue: a figure in green bronze on a stone plinth. */
	private static void statue(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z) {
		int[] at = offRoad(t, buildingTop, x, z);
		if (at == null) {
			return;
		}
		int y = ground(t, at[0], at[1]);
		set(chunk, pos, at[0], y + 1, at[1], Blocks.POLISHED_ANDESITE.defaultBlockState());
		set(chunk, pos, at[0], y + 2, at[1], Blocks.WAXED_OXIDIZED_COPPER.defaultBlockState());
		set(chunk, pos, at[0], y + 3, at[1], Blocks.WAXED_OXIDIZED_CHISELED_COPPER.defaultBlockState());
	}

	/** A monument: a stone obelisk. */
	private static void monument(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z) {
		int[] at = offRoad(t, buildingTop, x, z);
		if (at == null) {
			return;
		}
		int y = ground(t, at[0], at[1]);
		set(chunk, pos, at[0], y + 1, at[1], Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
		for (int dy = 2; dy <= 5; dy++) {
			set(chunk, pos, at[0], y + dy, at[1], Blocks.QUARTZ_PILLAR.defaultBlockState());
		}
		set(chunk, pos, at[0], y + 6, at[1], Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
	}

	/** A picnic table, with a bench either side. */
	private static void picnic(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z) {
		if (!open(t, buildingTop, x - 1, z) || !open(t, buildingTop, x, z) || !open(t, buildingTop, x + 1, z)) {
			return;
		}
		int y = ground(t, x, z);
		set(chunk, pos, x, y + 1, z, Blocks.OAK_FENCE.defaultBlockState());
		set(chunk, pos, x, y + 2, z, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
		bench(chunk, pos, x - 1, y, z, 3);
		bench(chunk, pos, x + 1, y, z, 1);
	}

	/** A sandpit in a playground, edged with logs. */
	private static void sandpit(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z) {
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				if (!open(t, buildingTop, x + dx, z + dz) || t.way[ChunkTerrain.index(x + dx, z + dz)] != Ways.NONE) {
					return;
				}
			}
		}
		int y = ground(t, x, z);
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				set(chunk, pos, x + dx, y, z + dz, rim ? Blocks.STRIPPED_OAK_LOG.defaultBlockState()
					.setValue(BlockStateProperties.AXIS, Math.abs(dx) == 2 ? Direction.Axis.Z : Direction.Axis.X) : Blocks.SAND.defaultBlockState());
				for (int dy = 1; dy <= 2; dy++) {
					set(chunk, pos, x + dx, y + dy, z + dz, AIR);
				}
			}
		}
	}

	/**
	 * A market: stalls round the marketplace's point, each two fence posts and an awning of wool over a
	 * counter of produce, where the ground is open.
	 */
	private static void market(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int x, int z, int baseX, int baseZ) {
		int[][] stalls = {{-4, -3}, {1, -3}, {-4, 2}, {1, 2}};
		for (int s = 0; s < stalls.length; s++) {
			int x0 = x + stalls[s][0];
			int z0 = z + stalls[s][1];
			boolean fits = true;
			for (int dz = 0; dz <= 1 && fits; dz++) {
				for (int dx = 0; dx <= 2 && fits; dx++) {
					fits = open(t, buildingTop, x0 + dx, z0 + dz);
				}
			}
			if (!fits) {
				continue;
			}
			int y = ground(t, x0 + 1, z0);
			Block awning = AWNINGS[(int) (GeoNoise.hash(baseX + x0, 97, baseZ + z0) * AWNINGS.length)];
			for (int dx = 0; dx <= 2; dx++) {
				for (int dz = 0; dz <= 1; dz++) {
					boolean post = (dx == 0 || dx == 2) && dz == 1;
					for (int dy = 1; dy <= 2; dy++) {
						set(chunk, pos, x0 + dx, y + dy, z0 + dz, post ? Blocks.OAK_FENCE.defaultBlockState() : AIR);
					}
					set(chunk, pos, x0 + dx, y + 3, z0 + dz, awning.defaultBlockState());
				}
				// The counter: produce along the front.
				Block goods = PRODUCE[(int) (GeoNoise.hash(baseX + x0 + dx, 98, baseZ + z0) * PRODUCE.length)];
				set(chunk, pos, x0 + dx, y + 1, z0, goods.defaultBlockState());
			}
		}
	}

	/** Parks: benches and lamps beside the paths, flowers in the grass, and a fountain in the middle of a big one. */
	private static void park(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos, int baseX, int baseZ) {
		boolean any = false;
		for (int z = 0; z < 16 && !any; z++) {
			for (int x = 0; x < 16 && !any; x++) {
				any = t.park[ChunkTerrain.index(x, z)];
			}
		}
		if (!any) {
			return;
		}
		if (GeoNoise.hash(baseX >> 4, 99, baseZ >> 4) < FOUNTAIN_CHANCE) {
			fountain(chunk, t, buildingTop, pos);
		}
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = ChunkTerrain.index(x, z);
				if (!t.park[k] || !open(t, buildingTop, x, z) || t.way[k] != Ways.NONE || (x >= 5 && x <= 10 && z >= 5 && z <= 10)) {
					continue;
				}
				int y = t.topSolidY[k];
				// Beside a path through the park, or at its edge: facing out, its back to the park.
				int path = wayBeside(t, x, z);
				if (path < 0) {
					path = edge(t, x, z);
				}
				double h = GeoNoise.hash(baseX + x, 100, baseZ + z);
				if (path >= 0 && h < BENCH_CHANCE) {
					bench(chunk, pos, x, y, z, (path + 2) % 4);
				} else if (path >= 0 && h < BENCH_CHANCE + PARK_LAMP_CHANCE) {
					set(chunk, pos, x, y + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
					set(chunk, pos, x, y + 2, z, Blocks.SPRUCE_FENCE.defaultBlockState());
					set(chunk, pos, x, y + 3, z, Blocks.LANTERN.defaultBlockState());
				} else if (path < 0 && h > 1 - FLOWER_CHANCE && chunk.getBlockState(pos.set(x, y, z)).is(Blocks.GRASS_BLOCK)
					&& chunk.getBlockState(pos.set(x, y + 1, z)).isAir()) {
					set(chunk, pos, x, y + 1, z, FLOWERS[(int) (GeoNoise.hash(baseX + x, 101, baseZ + z) * FLOWERS.length)].defaultBlockState());
				}
			}
		}
	}

	/**
	 * A fountain in the chunk's middle, where it is open park and level: a stone basin of water, and a
	 * column in it with a bowl of water on top. (Running water would find its way over the rim.)
	 */
	private static void fountain(ChunkAccess chunk, ChunkTerrain t, int[] buildingTop, BlockPos.MutableBlockPos pos) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int z = 5; z <= 10; z++) {
			for (int x = 5; x <= 10; x++) {
				int k = ChunkTerrain.index(x, z);
				if (!t.park[k] || !open(t, buildingTop, x, z) || t.way[k] != Ways.NONE) {
					return;
				}
				min = Math.min(min, t.topSolidY[k]);
				max = Math.max(max, t.topSolidY[k]);
			}
		}
		if (max - min > 1) {
			return;
		}
		int y = max;
		BlockState rim = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		for (int z = 5; z <= 10; z++) {
			for (int x = 5; x <= 10; x++) {
				boolean edge = x == 5 || x == 10 || z == 5 || z == 10;
				set(chunk, pos, x, y - 1, z, rim);
				set(chunk, pos, x, y, z, edge ? rim : water);
				set(chunk, pos, x, y + 1, z, edge ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : AIR);
				for (int dy = 2; dy <= 4; dy++) {
					set(chunk, pos, x, y + dy, z, AIR);
				}
			}
		}
		set(chunk, pos, 7, y, 7, rim);
		set(chunk, pos, 7, y + 1, 7, Blocks.STONE_BRICK_WALL.defaultBlockState());
		set(chunk, pos, 7, y + 2, 7, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
	}

	/**
	 * The things that are entities, with the chunk's first mobs: boats on the water by marinas, docks
	 * and ferry terminals; a minecart on the rails at a railway station.
	 */
	static void entities(net.minecraft.server.level.WorldGenRegion region, ChunkTerrain t, int baseX, int baseZ) {
		for (ChunkTerrain.Poi poi : t.pois) {
			String what = poi.subclass() == null ? "" : poi.subclass();
			int x = poi.x() - baseX;
			int z = poi.z() - baseZ;
			switch (what) {
				case "marina", "dock", "ferry_terminal" -> boats(region, t, baseX, baseZ, x, z, what.equals("marina") ? 3 : 1);
				case "station", "halt" -> {
					if ("railway".equals(poi.cls())) {
						minecart(region, t, baseX, baseZ, x, z);
					}
				}
				default -> {
				}
			}
		}
	}

	private static void boats(net.minecraft.server.level.WorldGenRegion region, ChunkTerrain t, int baseX, int baseZ, int x, int z, int count) {
		var types = java.util.List.of(net.minecraft.world.entity.EntityType.OAK_BOAT, net.minecraft.world.entity.EntityType.SPRUCE_BOAT,
			net.minecraft.world.entity.EntityType.BIRCH_BOAT);
		int placed = 0;
		for (int r = 1; r <= 10 && placed < count; r++) {
			for (int dz = -r; dz <= r && placed < count; dz++) {
				for (int dx = -r; dx <= r && placed < count; dx++) {
					int bx = x + dx;
					int bz = z + dz;
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r || bx < 0 || bx > 15 || bz < 0 || bz > 15 || (dx + dz) % 3 != 0) {
						continue;
					}
					int water = t.waterTopY[ChunkTerrain.index(bx, bz)];
					if (water == Integer.MIN_VALUE || t.deckY[ChunkTerrain.index(bx, bz)] != Integer.MIN_VALUE) {
						continue;
					}
					var boat = types.get((int) (GeoNoise.hash(baseX + bx, 102, baseZ + bz) * types.size())).create(region.getLevel(),
						net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
					if (boat == null) {
						return;
					}
					boat.snapTo(baseX + bx + 0.5, water + 1, baseZ + bz + 0.5, (float) (GeoNoise.hash(baseX + bx, 103, baseZ + bz) * 360), 0);
					region.addFreshEntity(boat);
					placed++;
				}
			}
		}
	}

	private static void minecart(net.minecraft.server.level.WorldGenRegion region, ChunkTerrain t, int baseX, int baseZ, int x, int z) {
		for (int r = 0; r <= 12; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					int bx = x + dx;
					int bz = z + dz;
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r || bx < 0 || bx > 15 || bz < 0 || bz > 15
						|| !Ways.isRail(t.way[ChunkTerrain.index(bx, bz)])) {
						continue;
					}
					int ground = t.topSolidY[ChunkTerrain.index(bx, bz)];
					for (int y = ground; y <= ground + 3; y++) {
						BlockPos at = new BlockPos(baseX + bx, y, baseZ + bz);
						if (region.getBlockState(at).is(net.minecraft.tags.BlockTags.RAILS)) {
							var cart = net.minecraft.world.entity.EntityType.MINECART.create(region.getLevel(), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
							if (cart != null) {
								cart.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
								region.addFreshEntity(cart);
							}
							return;
						}
					}
				}
			}
		}
	}

	private static net.minecraft.world.level.block.entity.BlockEntity set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
		if (y <= chunk.getMinY() || y >= chunk.getMaxY() || x < 0 || x > 15 || z < 0 || z > 15) {
			return null;
		}
		chunk.setBlockState(pos.set(x, y, z), s, 0);
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
