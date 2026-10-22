package se.itssimple.realearth.world;

import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import se.itssimple.realearth.osm.Ways;
import java.util.ArrayList;
import java.util.Arrays;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.climate.Koppen;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import se.itssimple.realearth.mixin.BeardifierAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.Level;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.terrain.VerticalScale;

/**
 * Builds the Earth dimension from real elevation instead of noise.
 *
 * Terrain shape comes entirely from {@link TerrainField}; this class turns heights into blocks:
 * soil and rock by biome and slope, beaches and lake beds, deepslate deep down, ores placed by depth
 * below the LOCAL surface (vanilla's ore heights are absolute, which would put every diamond inside
 * mountains and none under the lowlands), and vanilla's own trees and plants on top.
 *
 * Solid deepslate and open ocean far from the surface are stored as single-value sections, so the
 * world can use the game's full 4,064-block height at almost no memory cost.
 */
public final class EarthChunkGenerator extends ChunkGenerator {
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)).apply(i, i.stable(EarthChunkGenerator::new))
	);

	private static final Strategy<BlockState> BLOCK_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
	private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

	/** Depth below the local surface where stone gives way to deepslate (plus up to 8 of dither). */
	private static final int DEEPSLATE_DEPTH = 64;

	/**
	 * How fast the ground's own density grows with depth, when blending structures into it. Vanilla's
	 * blending shifts the ground a couple of blocks around buildings; a weaker gradient here lets the
	 * same blending carve whole villages several blocks down, leaving cliffs at the edges.
	 */
	private static final double BEARD_DENSITY_PER_BLOCK = 0.16;
	/** Never let structure blending move the ground further than this. */
	private static final int BEARD_MAX_SHIFT = 6;

	public EarthChunkGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	/**
	 * Structures need the real ground under them, so the chunk's terrain is fetched first, and while
	 * they are placed, biome and height questions get exact answers (see {@link GenContext}).
	 */
	@Override
	public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
		ChunkAccess chunk, StructureTemplateManager templates, ResourceKey<Level> level) {
		TerrainField field = RealEarth.terrain();
		if (field != null) {
			try {
				field.chunkBlocking(chunk.getPos().x(), chunk.getPos().z(), 120);
			} catch (Exception e) {
				RealEarth.LOGGER.warn("Terrain for structures at {} unavailable: {}", chunk.getPos(), e.toString());
			}
		}
		GenContext.EXACT.set(Boolean.TRUE);
		long started = se.itssimple.realearth.Perf.start();
		try {
			super.createStructures(registryAccess, state, structureManager, chunk, templates, level);
			removeOutpostsFromTowns(registryAccess, chunk);
		} finally {
			GenContext.EXACT.remove();
			se.itssimple.realearth.Perf.stop("structures", started);
		}
	}

	/**
	 * Pillager outposts belong in the wild, not in the middle of a town full of villagers; and where the
	 * town's real buildings are mapped, they are built instead of vanilla's villages.
	 */
	private static void removeOutpostsFromTowns(RegistryAccess registryAccess, ChunkAccess chunk) {
		var starts = chunk.getAllStarts();
		if (starts.isEmpty()) {
			return;
		}
		var structures = registryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
		var outpost = structures.getValue(net.minecraft.world.level.levelgen.structure.BuiltinStructures.PILLAGER_OUTPOST);
		ChunkPos pos = chunk.getPos();
		var kept = new java.util.HashMap<>(starts);
		boolean changed = false;
		// Built-up land cover, or mapped buildings: on a waterfront the land cover's centre is water.
		if (outpost != null && starts.containsKey(outpost)
			&& (BuiltUpPlacement.builtShare(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, 96) > 0.15
				|| BuiltUpPlacement.mappedBuildings(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, 96) >= BuiltUpPlacement.BUILDINGS_REPLACE_VILLAGE)) {
			kept.remove(outpost);
			changed = true;
		}
		boolean village = starts.keySet().stream().anyMatch(s -> structures.getKey(s) != null && structures.getKey(s).getPath().startsWith("village_"));
		if (village && BuiltUpPlacement.mappedBuildings(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, 64) >= BuiltUpPlacement.BUILDINGS_REPLACE_VILLAGE) {
			kept.keySet().removeIf(s -> structures.getKey(s) != null && structures.getKey(s).getPath().startsWith("village_"));
			changed = true;
		}
		if (changed) {
			chunk.setAllStarts(kept);
		}
	}

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return super.createBiomes(randomState, blender, structureManager, chunk);
		}
		// Biomes depend on the ground, so the terrain must be known first. Waiting here costs nothing
		// extra: fillFromNoise needs the same data and will find it ready.
		ChunkPos pos = chunk.getPos();
		return field.chunk(pos.x(), pos.z())
			.handle((t, error) -> null)
			.thenCompose(ignored -> super.createBiomes(randomState, blender, structureManager, chunk));
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return CompletableFuture.completedFuture(chunk);
		}
		ChunkPos pos = chunk.getPos();
		return field.chunk(pos.x(), pos.z()).handle((terrain, error) -> {
			if (error != null) {
				RealEarth.LOGGER.error("Terrain for chunk {} failed; leaving it empty", pos, error);
				recordDegraded(pos);
				return chunk;
			}
			long started = se.itssimple.realearth.Perf.start();
			fill(chunk, terrain, randomState, structureManager);
			se.itssimple.realearth.Perf.stop("fill", started);
			if (terrain.degraded) {
				recordDegraded(pos);
			}
			return chunk;
		});
	}

	/** Dimension id of the level this generator builds, found on first use. */
	private volatile String levelId;

	private void recordDegraded(ChunkPos pos) {
		String id = levelId;
		if (id == null) {
			net.minecraft.server.MinecraftServer server = RealEarth.server();
			if (server != null) {
				for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
					if (level.getChunkSource().getGenerator() == this) {
						id = levelId = level.dimension().identifier().toString();
						break;
					}
				}
			}
		}
		if (id == null) {
			RealEarth.LOGGER.warn("Chunk {} was generated from stand-in data but its level is unknown; it cannot be listed for repair", pos);
			return;
		}
		DegradedChunks.record(id, pos);
	}

	/**
	 * What one column is made of, top down. A sealed column is under or beside water: caves keep 8
	 * blocks away from its surface so they never drain a lake or flood from the sea.
	 */
	private record Column(int top, int waterTop, BlockState surface, BlockState under, int underDepth, BlockState filler, boolean sealed,
		@Nullable BlockState topper, int bandDepth) {
		Column(int top, int waterTop, BlockState surface, BlockState under, int underDepth, BlockState filler, boolean sealed,
			@Nullable BlockState topper) {
			this(top, waterTop, surface, under, underDepth, filler, sealed, topper, 0);
		}
	}

	private void fill(ChunkAccess chunk, ChunkTerrain t, RandomState randomState, StructureManager structureManager) {
		int minY = chunk.getMinY();
		ChunkPos pos = chunk.getPos();
		int baseX = pos.getMinBlockX();
		int baseZ = pos.getMinBlockZ();

		Column[] columns = new Column[256];
		int minTop = Integer.MAX_VALUE;
		int maxTop = Integer.MIN_VALUE;
		int maxSurface = Integer.MIN_VALUE;
		int minWaterTop = Integer.MAX_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				Column c = column(t, x, z, baseX + x, baseZ + z);
				columns[z * 16 + x] = c;
				minTop = Math.min(minTop, c.top);
				maxTop = Math.max(maxTop, c.top);
				maxSurface = Math.max(maxSurface, Math.max(c.top, c.waterTop));
				minWaterTop = Math.min(minWaterTop, c.waterTop);
			}
		}

		// Structures that want the ground shaped around them (villages, ancient cities, trial chambers).
		Beardifier beard = Beardifier.forStructuresInChunk(structureManager, pos);
		BoundingBox beardBox = ((BeardifierAccessor) beard).realearth$affectedBox();
		if (beardBox != null) {
			maxSurface = Math.max(maxSurface, Math.min(beardBox.maxY(), VerticalScale.MAX_Y));
		}

		// Caves and ravines, in a band from just above the highest ground to the deepest caves.
		CaveMask caves = CaveMask.compute(t, pos, minTop, maxTop, randomState, RealEarth.server().registryAccess());

		int bedrockTop = minY + 4;
		int uniformDeepTop = Math.min(minTop - DEEPSLATE_DEPTH - 9, caves.lowestY() - 1);
		if (beardBox != null) {
			uniformDeepTop = Math.min(uniformDeepTop, beardBox.minY() - 1);
		}

		LevelChunkSection[] sections = chunk.getSections();
		for (int s = 0; s < sections.length; s++) {
			int bottom = minY + s * 16;
			int topOfSection = bottom + 15;

			if (bottom > maxSurface) {
				continue;
			}
			if (bottom > bedrockTop && topOfSection <= uniformDeepTop) {
				sections[s] = new LevelChunkSection(new PalettedContainer<>(DEEPSLATE, BLOCK_STRATEGY), sections[s].getBiomes());
				continue;
			}
			if (bottom > maxTop && topOfSection <= minWaterTop && beardBox == null) {
				sections[s] = new LevelChunkSection(new PalettedContainer<>(WATER, BLOCK_STRATEGY), sections[s].getBiomes());
				continue;
			}

			LevelChunkSection section = sections[s];
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					Column c = columns[z * 16 + x];
					int worldX = baseX + x;
					int worldZ = baseZ + z;
					boolean shaped = beardBox != null && worldX >= beardBox.minX() && worldX <= beardBox.maxX()
						&& worldZ >= beardBox.minZ() && worldZ <= beardBox.maxZ();
					int highest = Math.min(topOfSection, shaped ? Math.max(maxSurface, c.top) : Math.max(c.top, c.waterTop));
					for (int y = bottom; y <= highest; y++) {
						boolean solid = y <= c.top;
						double structure = 0;
						if (shaped && y >= beardBox.minY() && y <= beardBox.maxY()) {
							structure = beard.compute(new DensityFunction.SinglePointContext(worldX, y, worldZ));
							if (Math.abs(y - c.top) <= BEARD_MAX_SHIFT) {
								solid = (c.top + 0.5 - y) * BEARD_DENSITY_PER_BLOCK + structure > 0;
							}
						}

						BlockState state;
						if (solid) {
							if (structure <= 0.05 && !(c.sealed && c.top - y < 8) && caves.isCave(x, y, z)) {
								int depth = c.top - y;
								state = Caves.VANILLA_SURFACE - depth < Caves.LAVA_BELOW_VIRTUAL_Y ? LAVA : AIR;
							} else {
								state = stateAt(c, y, worldX, worldZ, minY);
							}
						} else {
							state = y <= c.waterTop ? WATER : AIR;
						}
						if (state != AIR) {
							section.setBlockState(x, y - bottom, z, state, false);
						} else if (y <= c.top) {
							section.setBlockState(x, y - bottom, z, AIR, false);
						}
					}
				}
			}
		}

		// Crops, moss carpets and bushes on the surface, where nothing else stands.
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				Column c = columns[z * 16 + x];
				if (c.topper == null || c.waterTop > c.top || c.topper.is(Blocks.RAIL)) {
					continue;
				}
				BlockPos above = new BlockPos(x, c.top + 1, z);
				if (chunk.getBlockState(above).isAir() && chunk.getBlockState(above.below()).equals(c.surface)) {
					chunk.setBlockState(above, c.topper, 0);
				}
			}
		}

		// Ground raised under a structure gets a proper top: grass on dirt, not bare dirt.
		if (beardBox != null) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					Column c = columns[z * 16 + x];
					for (int y = Math.min(maxSurface, VerticalScale.MAX_Y); y > c.top; y--) {
						BlockState here = chunk.getBlockState(new BlockPos(x, y, z));
						if (!here.isAir() && here.getFluidState().isEmpty()) {
							chunk.setBlockState(new BlockPos(x, y, z), c.surface, 0);
							break;
						}
					}
				}
			}
		}

		// Rails, last: on the ground as it ended up, even where a structure reshaped it.
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				Column c = columns[z * 16 + x];
				if (c.topper == null || !c.topper.is(Blocks.RAIL) || c.waterTop > c.top) {
					continue;
				}
				for (int y = c.top + 1; y <= c.top + 4; y++) {
					BlockPos at = new BlockPos(x, y, z);
					if (chunk.getBlockState(at).isAir() && chunk.getBlockState(at.below()).isSolid()) {
						chunk.setBlockState(at, c.topper, 0);
						break;
					}
				}
			}
		}

		// Bridge decks, with rails on top where a railway crosses.
		int[] deckTop = new int[256];
		Arrays.fill(deckTop, Integer.MIN_VALUE);
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = ChunkTerrain.index(x, z);
				int y = t.deckY[k];
				if (y == Integer.MIN_VALUE || t.deckWay[k] == Ways.NONE || y >= VerticalScale.MAX_Y - 2) {
					continue;
				}
				boolean rail = Ways.isRail(t.deckWay[k]);
				chunk.setBlockState(new BlockPos(x, y, z), rail ? block(Blocks.STONE_BRICKS) : waySurface(t.deckWay[k], baseX + x, baseZ + z), 0);
				for (int up = 1; up <= 3; up++) {
					chunk.setBlockState(new BlockPos(x, y + up, z), AIR, 0);
				}
				deckTop[z * 16 + x] = y;
				if (t.deckWay[k] == Ways.RAIL) {
					chunk.setBlockState(new BlockPos(x, y + 1, z), railShape(t, x, z, true), 0);
					deckTop[z * 16 + x] = y + 1;
				}
			}
		}

		// Buildings, then the life between them: wells, farms, lamps, bells.
		long painting = se.itssimple.realearth.Perf.start();
		int[] buildingTop = BuildingPainter.paint(chunk, t, baseX, baseZ);
		TownFeatures.place(chunk, t, baseX, baseZ, buildingTop);
		se.itssimple.realearth.Perf.stop("fill.buildings", painting);

		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				Column c = columns[z * 16 + x];
				int from = beardBox != null ? Math.max(maxSurface, Math.max(c.top, c.waterTop)) : Math.max(c.top, c.waterTop);
				from = Math.max(from, Math.max(deckTop[z * 16 + x], buildingTop[z * 16 + x]));
				for (int y = from; y >= c.top - 64 && y > minY; y--) {
					BlockState state = chunk.getBlockState(probe.set(x, y, z));
					if (!state.isAir()) {
						worldSurface.update(x, y, z, state);
						break;
					}
				}
				for (int y = from; y >= c.top - 64 && y > minY; y--) {
					BlockState state = chunk.getBlockState(probe.set(x, y, z));
					if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
						oceanFloor.update(x, y, z, state);
						break;
					}
				}
			}
		}
	}

	private static BlockState stateAt(Column c, int y, int worldX, int worldZ, int minY) {
		if (y - minY < 5 && y - minY <= (int) (GeoNoise.hash(worldX, y, worldZ) * 5)) {
			return BEDROCK;
		}
		int depth = c.top - y;
		if (c.bandDepth > 0 && depth >= 0 && depth <= c.bandDepth) {
			return Strata.at(y, worldX, worldZ);
		}
		if (depth <= 0) {
			return depth == 0 ? c.surface : c.under;
		}
		if (depth <= c.underDepth) {
			return c.under;
		}
		if (depth >= DEEPSLATE_DEPTH + (int) (GeoNoise.hash(worldX, y, worldZ) * 8)) {
			return DEEPSLATE;
		}
		return c.filler;
	}

	private static BlockState block(Block b) {
		return b.defaultBlockState();
	}

	/** What a way is paved with: asphalt for roads, dirt and gravel for tracks, trodden paths, ballast. */
	private static BlockState waySurface(byte way, int worldX, int worldZ) {
		return switch (way) {
			case Ways.MOTORWAY, Ways.MAJOR, Ways.MINOR, Ways.SERVICE -> block(Blocks.GRAY_CONCRETE);
			case Ways.TRACK -> GeoNoise.hash(worldX, 3, worldZ) < 0.55 ? block(Blocks.COARSE_DIRT) : block(Blocks.GRAVEL);
			case Ways.PATH -> block(Blocks.DIRT_PATH);
			default -> block(Blocks.GRAVEL);
		};
	}

	/**
	 * The rail at a track column, turned to join its neighbours on the track: straight, curved, or
	 * sloping up where the next rail is a block higher.
	 */
	private static BlockState railShape(ChunkTerrain t, int x, int z, boolean onDeck) {
		boolean north = isTrack(t, x, z - 1, onDeck);
		boolean south = isTrack(t, x, z + 1, onDeck);
		boolean east = isTrack(t, x + 1, z, onDeck);
		boolean west = isTrack(t, x - 1, z, onDeck);
		int here = trackY(t, x, z, onDeck);
		RailShape shape;
		if (north && east) {
			shape = RailShape.NORTH_EAST;
		} else if (north && west) {
			shape = RailShape.NORTH_WEST;
		} else if (south && east) {
			shape = RailShape.SOUTH_EAST;
		} else if (south && west) {
			shape = RailShape.SOUTH_WEST;
		} else if (east || west) {
			shape = east && trackY(t, x + 1, z, onDeck) == here + 1 ? RailShape.ASCENDING_EAST
				: west && trackY(t, x - 1, z, onDeck) == here + 1 ? RailShape.ASCENDING_WEST : RailShape.EAST_WEST;
		} else {
			shape = north && trackY(t, x, z - 1, onDeck) == here + 1 ? RailShape.ASCENDING_NORTH
				: south && trackY(t, x, z + 1, onDeck) == here + 1 ? RailShape.ASCENDING_SOUTH : RailShape.NORTH_SOUTH;
		}
		return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
	}

	private static boolean isTrack(ChunkTerrain t, int x, int z, boolean onDeck) {
		int k = ChunkTerrain.index(x, z);
		return (onDeck ? t.deckWay[k] : t.way[k]) == Ways.RAIL;
	}

	private static int trackY(ChunkTerrain t, int x, int z, boolean onDeck) {
		int k = ChunkTerrain.index(x, z);
		return onDeck ? t.deckY[k] : t.topSolidY[k];
	}

	private Column column(ChunkTerrain t, int x, int z, int worldX, int worldZ) {
		int k = ChunkTerrain.index(x, z);
		int top = t.topSolidY[k];
		int waterTop = t.waterTopY[k];
		double lat = t.latitude[k];
		double lon = t.longitude[k];
		double ground = t.groundMeters[k];
		int climate = t.climate[k] & 0xFF;
		int cover = t.cover[k] & 0xFF;
		boolean wet = waterTop != Integer.MIN_VALUE;
		boolean lake = t.lake[k];
		// Mixed ground (moss and gravel, sand and silt) comes in patches a few metres across, not as
		// a per-block coin flip, which reads as noise. Scattered things (bushes) stay random.
		double scatter = GeoNoise.hash(worldX, 0, worldZ);
		double roll = Math.max(0, Math.min(0.999, 0.5 + 0.55 * GeoNoise.sample(lat, lon, 0.008, 17) + (scatter - 0.5) * 0.12));
		BlockState dirt = block(Blocks.DIRT);
		BlockState grass = block(Blocks.GRASS_BLOCK);

		if (wet) {
			double depth = waterTop - top;
			if (lake) {
				BlockState bed = cover == WorldCover.WETLAND || cover == WorldCover.MANGROVES ? block(Blocks.MUD)
					: roll < 0.35 ? block(Blocks.SAND) : roll < 0.65 ? block(Blocks.GRAVEL) : roll < 0.85 ? dirt : block(Blocks.CLAY);
				return new Column(top, waterTop, bed, dirt, 2, STONE, true, null);
			}
			BlockState bed = depth < 10 || (depth < 40 && roll < 0.6) ? block(Blocks.SAND) : roll < 0.2 ? block(Blocks.CLAY) : block(Blocks.GRAVEL);
			return new Column(top, waterTop, bed, bed.is(Blocks.SAND) ? block(Blocks.SANDSTONE) : block(Blocks.GRAVEL), 3, STONE, true, null);
		}

		// Caves must not open into the sea or a lake from the side: a dry column next to water is sealed.
		boolean shore = t.nextToWater(x, z);

		// Under a building: plain ground, kept clear of caves; the building itself comes later.
		if (t.building[k] != 0) {
			return new Column(top, waterTop, dirt, dirt, 3, STONE, true, null);
		}

		byte way = t.way[k];
		if (way != Ways.NONE) {
			BlockState surface = waySurface(way, worldX, worldZ);
			BlockState under = Ways.isPaved(way) ? STONE : Ways.isRail(way) ? block(Blocks.GRAVEL) : dirt;
			return new Column(top, waterTop, surface, under, 2, STONE, shore, way == Ways.RAIL ? railShape(t, x, z, false) : null);
		}
		ResourceKey<Biome> biome = BiomePicker.pick(lat, lon, ground, false, false, 0, climate, cover);
		int slope = t.slope(x, z);
		boolean cold = Koppen.polar(climate) || Koppen.subarctic(climate) || (climate == Koppen.SEA && Math.abs(lat) > 60);
		boolean hotDry = Koppen.hotArid(climate) || Koppen.tropical(climate);
		boolean desert = Koppen.desert(climate);

		// Cliffs and steep slopes in dry country: banded terracotta, deep enough to cover the exposed
		// face down to the neighbouring column. Sand seas keep their sandstone.
		boolean dunes = cover == WorldCover.BARE && Koppen.hotArid(climate);
		// Measured across the column (left neighbour to right), so a single terrace step on flat ground
		// doesn't count but a 45-degree canyon wall does.
		int across = Math.max(Math.abs(t.topSolidY[ChunkTerrain.index(x + 1, z)] - t.topSolidY[ChunkTerrain.index(x - 1, z)]),
			Math.abs(t.topSolidY[ChunkTerrain.index(x, z + 1)] - t.topSolidY[ChunkTerrain.index(x, z - 1)]));
		if ((Koppen.arid(climate) || biome == Biomes.BADLANDS) && !dunes && across >= (desert ? 2 : 3)) {
			return new Column(top, waterTop, Strata.at(top, worldX, worldZ), STONE, 0, STONE, shore, null, Math.min(48, slope + 4));
		}

		if (slope >= 4) {
			BlockState rock = biome == Biomes.DESERT && hotDry ? block(Blocks.SANDSTONE) : biome == Biomes.BADLANDS ? block(Blocks.TERRACOTTA) : STONE;
			return new Column(top, waterTop, rock, rock, 0, STONE, shore, null);
		}

		// Shores: sand where land meets warm sea, gravel where it meets cold sea or a lake.
		if (shore && ground < 2.5 + seaOrLakeLevel(t, x, z)) {
			BlockState beach = cold || nextToLake(t, x, z) ? (roll < 0.5 ? block(Blocks.GRAVEL) : block(Blocks.SAND)) : block(Blocks.SAND);
			return new Column(top, waterTop, beach, beach, 2, STONE, true, null);
		}

		// Glaciers and ice caps.
		if (cover == WorldCover.SNOW_ICE || biome == Biomes.FROZEN_PEAKS || biome == Biomes.JAGGED_PEAKS || biome == Biomes.SNOWY_SLOPES) {
			if (slope >= 3) {
				return new Column(top, waterTop, STONE, STONE, 0, STONE, shore, null);
			}
			boolean ice = (cover == WorldCover.SNOW_ICE || biome == Biomes.FROZEN_PEAKS) && GeoNoise.sample(lat, lon, 0.15, 11) > 0.2;
			BlockState cap = ice ? block(Blocks.PACKED_ICE) : block(Blocks.SNOW_BLOCK);
			return new Column(top, waterTop, cap, ice ? block(Blocks.PACKED_ICE) : block(Blocks.SNOW_BLOCK), ice ? 4 : 1, STONE, shore, null);
		}

		switch (cover) {
			case WorldCover.CROPS -> {
				return field(top, waterTop, worldX, worldZ, shore, climate);
			}
			case WorldCover.BUILT -> {
				// Between the town's villages: lawns and yards, with lots of paving, gravel and bare earth.
				// In dry country the open ground is sand and packed earth rather than lawn.
				double h = GeoNoise.hash(worldX >> 3, 7, worldZ >> 3);
				BlockState open = desert ? block(Blocks.SAND) : Koppen.arid(climate) ? block(Blocks.COARSE_DIRT) : grass;
				BlockState ground0 = h < 0.6 ? open : h < 0.78 ? block(Blocks.POLISHED_ANDESITE) : h < 0.9 ? block(Blocks.GRAVEL) : block(Blocks.COARSE_DIRT);
				return new Column(top, waterTop, ground0, dirt, 3, STONE, shore, null);
			}
			case WorldCover.WETLAND, WorldCover.MANGROVES -> {
				// Bogs and marshes: mud, moss and shallow pools.
				double pool = GeoNoise.sample(lat, lon, 0.02, 13);
				if (pool > 0.3 && !shore) {
					return new Column(top - 1, top, block(Blocks.MUD), block(Blocks.MUD), 2, STONE, true, null);
				}
				BlockState surface = cold ? (roll < 0.5 ? block(Blocks.MOSS_BLOCK) : grass) : roll < 0.4 ? block(Blocks.MUD) : grass;
				return new Column(top, waterTop, surface, dirt, 3, STONE, true, null);
			}
			case WorldCover.MOSS -> {
				BlockState surface = roll < 0.45 ? block(Blocks.MOSS_BLOCK) : roll < 0.6 ? block(Blocks.COARSE_DIRT) : grass;
				return new Column(top, waterTop, surface, dirt, 2, STONE, shore, surface.is(Blocks.MOSS_BLOCK) && scatter < 0.3 ? block(Blocks.MOSS_CARPET) : null);
			}
			case WorldCover.BARE -> {
				if (hotDry || biome == Biomes.DESERT && Koppen.hotArid(climate)) {
					return new Column(top, waterTop, block(Blocks.SAND), block(Blocks.SAND), 4, block(Blocks.SANDSTONE), shore, null);
				}
				if (Koppen.arid(climate)) {
					// Cold deserts: red sand and stony ground.
					BlockState s = roll < 0.3 ? block(Blocks.RED_SAND) : roll < 0.6 ? block(Blocks.COARSE_DIRT) : roll < 0.8 ? block(Blocks.GRAVEL) : STONE;
					return new Column(top, waterTop, s, block(Blocks.COARSE_DIRT), 2, STONE, shore, null);
				}
				BlockState rock = roll < 0.4 ? block(Blocks.GRAVEL) : roll < 0.75 ? STONE : block(Blocks.COARSE_DIRT);
				return new Column(top, waterTop, rock, block(Blocks.GRAVEL), 2, STONE, shore, null);
			}
			case WorldCover.GRASS -> {
				if (Koppen.arid(climate)) {
					BlockState surface = desert ? (roll < 0.3 ? block(Blocks.RED_SAND) : roll < 0.6 ? block(Blocks.COARSE_DIRT) : grass)
						: roll < 0.25 ? block(Blocks.COARSE_DIRT) : grass;
					return new Column(top, waterTop, surface, dirt, 3, STONE, shore, null);
				}
			}
			case WorldCover.SHRUBS -> {
				// Dry scrub is mostly bare red earth between the bushes; in steppe more of it is grass.
				BlockState surface = desert ? (roll < 0.45 ? block(Blocks.RED_SAND) : roll < 0.8 ? block(Blocks.COARSE_DIRT) : grass)
					: Koppen.arid(climate) ? (roll < 0.2 ? block(Blocks.RED_SAND) : roll < 0.5 ? block(Blocks.COARSE_DIRT) : grass)
					: hotDry ? (roll < 0.3 ? block(Blocks.COARSE_DIRT) : grass) : grass;
				BlockState bush = scatter < 0.06 ? (Koppen.arid(climate) || hotDry ? block(Blocks.DEAD_BUSH) : block(Blocks.AZALEA)) : null;
				return new Column(top, waterTop, surface, dirt, 3, STONE, shore, bush);
			}
			default -> {
			}
		}

		if (biome == Biomes.DESERT) {
			if (Koppen.hotArid(climate) || Koppen.tropical(climate)) {
				return new Column(top, waterTop, block(Blocks.SAND), block(Blocks.SAND), 4, block(Blocks.SANDSTONE), shore, null);
			}
			// Cold deserts (Gobi, Patagonia) are gravel and coarse ground more than dunes.
			BlockState s = roll < 0.4 ? block(Blocks.GRAVEL) : roll < 0.7 ? block(Blocks.COARSE_DIRT) : block(Blocks.SAND);
			return new Column(top, waterTop, s, block(Blocks.GRAVEL), 3, STONE, shore, null);
		}
		if (biome == Biomes.BADLANDS) {
			return new Column(top, waterTop, block(Blocks.RED_SAND), block(Blocks.TERRACOTTA), 6, STONE, shore, null);
		}
		if (biome == Biomes.STONY_PEAKS || biome == Biomes.WINDSWEPT_GRAVELLY_HILLS) {
			BlockState rock = roll < 0.25 ? block(Blocks.GRAVEL) : roll < 0.35 ? block(Blocks.ANDESITE) : STONE;
			return new Column(top, waterTop, rock, STONE, 0, STONE, shore, null);
		}
		if (slope >= 3) {
			BlockState rock = roll < 0.6 ? STONE : block(Blocks.COARSE_DIRT);
			return new Column(top, waterTop, rock, STONE, 0, STONE, shore, null);
		}
		if (biome == Biomes.TAIGA || biome == Biomes.OLD_GROWTH_SPRUCE_TAIGA || biome == Biomes.SNOWY_TAIGA) {
			double patch = GeoNoise.sample(lat, lon, 0.03, 7);
			BlockState topBlock = patch > 0.45 ? block(Blocks.PODZOL) : patch < -0.6 ? block(Blocks.COARSE_DIRT) : grass;
			return new Column(top, waterTop, topBlock, dirt, 3, STONE, shore, null);
		}
		if (biome == Biomes.SWAMP && roll < 0.2) {
			return new Column(top, waterTop, block(Blocks.MUD), dirt, 3, STONE, shore, null);
		}
		return new Column(top, waterTop, grass, dirt, 3, STONE, shore, null);
	}

	/**
	 * Farmland in plots of about 24 x 24 blocks, each its own crop at its own ripeness, in rows with a
	 * furrow of grass every few blocks; some plots lie fallow or are pasture.
	 */
	private static Column field(int top, int waterTop, int worldX, int worldZ, boolean shore, int climate) {
		int plotX = Math.floorDiv(worldX, 24);
		int plotZ = Math.floorDiv(worldZ, 24);
		double plot = GeoNoise.hash(plotX, 31, plotZ);
		boolean alongX = GeoNoise.hash(plotX, 37, plotZ) < 0.5;
		int along = Math.floorMod(alongX ? worldZ : worldX, 24);
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		boolean edge = Math.floorMod(worldX, 24) == 0 || Math.floorMod(worldZ, 24) == 0;
		if (edge || plot < 0.18 || along % 6 == 5) {
			return new Column(top, waterTop, grass, dirt, 3, STONE, shore, null);
		}
		if (plot < 0.28) {
			return new Column(top, waterTop, Blocks.COARSE_DIRT.defaultBlockState(), dirt, 3, STONE, shore, null);
		}
		Block crop = plot < 0.55 ? Blocks.WHEAT : plot < 0.7 ? Blocks.POTATOES : plot < 0.85 ? Blocks.CARROTS : Blocks.BEETROOTS;
		if (Koppen.tropical(climate) && plot > 0.7) {
			crop = Blocks.SUGAR_CANE;
		}
		int ripeness = (int) (GeoNoise.hash(plotX, 41, plotZ) * 8);
		BlockState plant;
		if (crop == Blocks.SUGAR_CANE) {
			return new Column(top, waterTop, grass, dirt, 3, STONE, shore, null);
		} else if (crop == Blocks.BEETROOTS) {
			plant = crop.defaultBlockState().setValue(BlockStateProperties.AGE_3, Math.min(3, ripeness / 2));
		} else {
			plant = crop.defaultBlockState().setValue(BlockStateProperties.AGE_7, Math.min(7, ripeness));
		}
		BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);
		return new Column(top, waterTop, farmland, dirt, 3, STONE, shore, plant);
	}

	private static double seaOrLakeLevel(ChunkTerrain t, int x, int z) {
		double level = 0;
		int[][] around = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
		for (int[] d : around) {
			int k = ChunkTerrain.index(x + d[0], z + d[1]);
			if (!Float.isNaN(t.waterMeters[k]) && t.waterTopY[k] != Integer.MIN_VALUE) {
				level = Math.max(level, t.waterMeters[k]);
			}
		}
		return level;
	}

	private static boolean nextToLake(ChunkTerrain t, int x, int z) {
		return t.lake[ChunkTerrain.index(x - 1, z)] || t.lake[ChunkTerrain.index(x + 1, z)]
			|| t.lake[ChunkTerrain.index(x, z - 1)] || t.lake[ChunkTerrain.index(x, z + 1)];
	}

	@Override
	public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
		// Surface materials are laid down in fillFromNoise, where the terrain is already in hand.
	}

	@Override
	public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		ChunkPos center = region.getCenter();
		Holder<Biome> biome = region.getBiome(center.getWorldPosition().atY(region.getMaxY()));
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
		long started = se.itssimple.realearth.Perf.start();
		NaturalSpawner.spawnMobsForChunkGeneration(region, biome, center, random);
		moveInVillagers(region, center);
		se.itssimple.realearth.Perf.stop("mobs", started);
	}

	/**
	 * A villager moves into one of this chunk's beds, in some of the chunks that have beds (see
	 * villagerChance), about one chunk of town in ten: enough for a town to feel lived in, few enough that a city of thousands of
	 * chunks doesn't bring the server to its knees. They find their trades themselves.
	 */
	private static void moveInVillagers(WorldGenRegion region, ChunkPos center) {
		TerrainField field = RealEarth.terrain();
		ChunkTerrain t = field == null ? null : field.chunkIfReady(center.x(), center.z());
		if (t == null || t.beds.isEmpty() || GeoNoise.hash(center.x(), 71, center.z()) >= RealEarth.config().villagerChance) {
			return;
		}
		int moved = 0;
		for (int[] bed : t.beds) {
			if (moved >= 1) {
				break;
			}
			var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(region.getLevel(), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
			if (villager == null) {
				continue;
			}
			villager.snapTo(bed[0] + 0.5, bed[1], bed[2] + 0.5, 0, 0);
			villager.finalizeSpawn(region, region.getCurrentDifficultyAt(villager.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE, null);
			villager.setPersistenceRequired();
			region.addFreshEntityWithPassengers(villager);
			moved++;
			se.itssimple.realearth.Perf.count("villagers", 1);
		}
	}

	@Override
	public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		long started = se.itssimple.realearth.Perf.start();
		super.applyBiomeDecoration(level, chunk, structureManager);
		coolTownLava(level, chunk);
		registerPointsOfInterest(level, chunk);
		se.itssimple.realearth.Perf.stop("features", started);
	}

	/**
	 * Vanilla's surface lava lakes and springs don't belong in a town's parks and yards. In a town (a
	 * chunk with mapped buildings or streets), lava at the ground becomes a pond: still lava turns to
	 * water and flowing lava goes. The chunks round this one are looked at too, as a lake started here
	 * reaches into them. Lava out in the wild, and deep down, is left as it is.
	 */
	private static void coolTownLava(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return;
		}
		ChunkPos centre = chunk.getPos();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int cx = centre.x() + dx;
				int cz = centre.z() + dz;
				ChunkTerrain t = level.hasChunk(cx, cz) ? field.chunkIfReady(cx, cz) : null;
				if (t == null || !town(t)) {
					continue;
				}
				ChunkAccess c = level.getChunk(cx, cz);
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						int ground = t.topSolidY[ChunkTerrain.index(x, z)];
						for (int y = ground - 6; y <= ground + 4; y++) {
							pos.set(cx * 16 + x, y, cz * 16 + z);
							var fluid = c.getFluidState(pos);
							if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) {
								level.setBlock(pos, fluid.isSource() ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
							}
						}
					}
				}
			}
		}
	}

	/** Whether a chunk is town: mapped buildings, or streets and paths. */
	private static boolean town(ChunkTerrain t) {
		if (!t.plans.isEmpty()) {
			return true;
		}
		for (byte w : t.way) {
			if (w != Ways.NONE) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Beds, job blocks and bells the buildings were painted with go into the level's record of points
	 * of interest, as blocks placed through the region do: without it villagers find no home or work
	 * in a chunk until it has been saved and loaded again. Recorded on the server thread, as vanilla does.
	 */
	private static void registerPointsOfInterest(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk) {
		var sections = chunk.getSections();
		List<BlockPos> at = new ArrayList<>();
		List<Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> types = new ArrayList<>();
		ChunkPos pos = chunk.getPos();
		for (int i = 0; i < sections.length; i++) {
			var section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(net.minecraft.world.entity.ai.village.poi.PoiTypes::hasPoi)) {
				continue;
			}
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						var type = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(section.getBlockState(x, y, z));
						if (type.isPresent()) {
							at.add(new BlockPos(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z));
							types.add(type.get());
						}
					}
				}
			}
		}
		if (at.isEmpty()) {
			return;
		}
		net.minecraft.server.level.ServerLevel server = level.getLevel();
		server.getServer().execute(() -> {
			var poi = server.getPoiManager();
			for (int i = 0; i < at.size(); i++) {
				if (poi.getType(at.get(i)).isEmpty()) {
					poi.add(at.get(i), types.get(i));
				}
			}
		});
	}

	@Override
	public int getGenDepth() {
		return VerticalScale.HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return VerticalScale.SEA_LEVEL_Y;
	}

	@Override
	public int getMinY() {
		return VerticalScale.MIN_Y;
	}

	/** Spawn at the real ground at the start point; the vanilla spawn search then refines it nearby. */
	@Override
	public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return VerticalScale.SEA_LEVEL_Y + 1;
		}
		try {
			ChunkTerrain t = field.chunkBlocking(field.startX() >> 4, field.startZ() >> 4, 120);
			int k = ChunkTerrain.index(8, 8);
			return Math.max(t.topSolidY[k], t.waterTopY[k]) + 1;
		} catch (Exception e) {
			return VerticalScale.SEA_LEVEL_Y + 1;
		}
	}

	/**
	 * The exact terrain of a column if it is known or we are generating structures; null otherwise,
	 * meaning "answer from coarse elevation".
	 */
	private ChunkTerrain terrainAt(int x, int z) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return null;
		}
		ChunkTerrain ready = field.chunkIfReady(x >> 4, z >> 4);
		if (ready != null || !GenContext.exact()) {
			return ready;
		}
		try {
			return field.chunkBlocking(x >> 4, z >> 4, 60);
		} catch (Exception e) {
			RealEarth.LOGGER.warn("Terrain at {}, {} unavailable: {}", x, z, e.toString());
			return null;
		}
	}

	/** Top solid Y and water top Y of a column, exact when possible, otherwise from coarse elevation. */
	private int[] columnHeights(int x, int z) {
		ChunkTerrain t = terrainAt(x, z);
		if (t != null) {
			int k = ChunkTerrain.index(x & 15, z & 15);
			return new int[] {t.topSolidY[k], t.waterTopY[k]};
		}
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return new int[] {VerticalScale.SEA_LEVEL_Y - 1, Integer.MIN_VALUE};
		}
		double lat = field.projection().latitudeAt(z + 0.5);
		double lon = field.projection().longitudeAt(x + 0.5, lat);
		double elevation = field.coarseElevationMeters(lat, lon);
		int top = (int) Math.round(field.toY(elevation, lat)) - 1;
		return new int[] {top, elevation < 0 ? VerticalScale.SEA_LEVEL_Y - 1 : Integer.MIN_VALUE};
	}

	/**
	 * Where a vanilla height lands on Earth: vanilla's ground sits at about Y 64, so a vanilla Y is
	 * read as that far above or below the local ground.
	 */
	public int vanillaToLocalY(int vanillaY, int x, int z) {
		return columnHeights(x, z)[0] + (vanillaY - Caves.VANILLA_SURFACE);
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int[] h = columnHeights(x, z);
		if (h[1] > h[0] && type.isOpaque().test(WATER)) {
			return h[1] + 1;
		}
		return h[0] + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		BlockState[] states = new BlockState[heightAccessor.getHeight()];
		int[] h = columnHeights(x, z);
		int top = h[0];
		int water = h[1];
		int minY = heightAccessor.getMinY();
		for (int i = 0; i < states.length; i++) {
			int y = minY + i;
			states[i] = y <= top ? (top - y >= DEEPSLATE_DEPTH ? DEEPSLATE : STONE) : y <= water ? WATER : AIR;
		}
		return new NoiseColumn(minY, states);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos pos) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return;
		}
		double lat = field.projection().latitudeAt(pos.getZ() + 0.5);
		double lon = field.projection().longitudeAt(pos.getX() + 0.5, lat);
		result.add(String.format(Locale.ROOT, "Real Earth: %.5f, %.5f  elevation %.0f m", lat, lon, field.toElevation(pos.getY(), lat)));
		result.add(se.itssimple.realearth.Credits.SHORT);
	}
}
