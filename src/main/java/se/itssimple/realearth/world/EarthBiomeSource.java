package se.itssimple.realearth.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Biomes from real geography (see {@link BiomePicker}), plus vanilla's cave biomes underground.
 *
 * During world generation the chunk's terrain is always known before biomes are filled in, because
 * {@link EarthChunkGenerator#createBiomes} waits for it; structure placement gets exact answers too
 * (see {@link GenContext}). Anything else -- /locate, explorer maps, the stronghold search, which
 * probe thousands of far-away points -- is answered from coarse elevation instead of downloading
 * full-resolution tiles for every probe.
 */
public final class EarthBiomeSource extends BiomeSource {
	public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(i, i.stable(EarthBiomeSource::new))
	);

	/** Cave biomes begin this far below the ground. */
	private static final int CAVE_BIOME_DEPTH = 24;
	/** The deep dark, and with it ancient cities, lies this far down or more (vanilla: about Y 0). */
	private static final int DEEP_DARK_DEPTH = 64;

	private final Map<ResourceKey<Biome>, Holder<Biome>> holders = new HashMap<>();

	public EarthBiomeSource(HolderGetter<Biome> biomes) {
		for (ResourceKey<Biome> key : BiomePicker.ALL) {
			holders.put(key, biomes.getOrThrow(key));
		}
		for (ResourceKey<Biome> key : new ResourceKey[] {Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK}) {
			holders.put(key, biomes.getOrThrow(key));
		}
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return holders.values().stream();
	}

	public Holder<Biome> holder(ResourceKey<Biome> key) {
		return holders.getOrDefault(key, holders.get(Biomes.PLAINS));
	}

	/** Surface biome and cave flavour for each 4x4 column of a chunk, computed once per chunk. */
	private record ChunkBiomes(ResourceKey<Biome>[] surface, double[] flavour) {
	}

	@SuppressWarnings("unchecked")
	private static ChunkBiomes biomesOf(ChunkTerrain t) {
		Object cached = t.biomeCache;
		if (cached instanceof ChunkBiomes b) {
			return b;
		}
		ResourceKey<Biome>[] surface = new ResourceKey[16];
		double[] flavour = new double[16];
		for (int qz = 0; qz < 4; qz++) {
			for (int qx = 0; qx < 4; qx++) {
				int k = ChunkTerrain.index(qx * 4 + 2, qz * 4 + 2);
				boolean water = !Float.isNaN(t.waterMeters[k]) && t.waterTopY[k] != Integer.MIN_VALUE;
				surface[qz * 4 + qx] = BiomePicker.pick(t.latitude[k], t.longitude[k], t.groundMeters[k], water, t.lake[k],
					water ? t.waterMeters[k] : 0, t.climate[k] & 0xFF, t.cover[k] & 0xFF);
				flavour[qz * 4 + qx] = GeoNoise.sample(t.latitude[k], t.longitude[k], 0.5, 21);
			}
		}
		ChunkBiomes b = new ChunkBiomes(surface, flavour);
		t.biomeCache = b;
		return b;
	}

	/** Lush caves, dripstone caves and the deep dark, in patches a few hundred metres across. */
	private static ResourceKey<Biome> underground(ResourceKey<Biome> surface, double flavour, int depth) {
		if (depth < CAVE_BIOME_DEPTH) {
			return surface;
		}
		if (depth >= DEEP_DARK_DEPTH && flavour > 0.45) {
			return Biomes.DEEP_DARK;
		}
		if (flavour < -0.45) {
			return Biomes.LUSH_CAVES;
		}
		if (flavour > 0.15 && flavour < 0.35) {
			return Biomes.DRIPSTONE_CAVES;
		}
		return surface;
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return holder(Biomes.PLAINS);
		}

		int blockX = QuartPos.toBlock(quartX) + 2;
		int blockY = QuartPos.toBlock(quartY) + 2;
		int blockZ = QuartPos.toBlock(quartZ) + 2;
		ChunkTerrain terrain = field.chunkIfReady(blockX >> 4, blockZ >> 4);
		if (terrain == null && GenContext.exact()) {
			try {
				terrain = field.chunkBlocking(blockX >> 4, blockZ >> 4, 60);
			} catch (Exception ignored) {
				// Fall through to the coarse answer.
			}
		}
		if (terrain != null) {
			ChunkBiomes b = biomesOf(terrain);
			int q = ((blockZ & 15) >> 2) * 4 + ((blockX & 15) >> 2);
			int depth = terrain.topSolidY[ChunkTerrain.index(blockX & 15, blockZ & 15)] - blockY;
			return holder(underground(b.surface[q], b.flavour[q], depth));
		}

		double lat = field.projection().latitudeAt(blockZ + 0.5);
		double lon = field.projection().longitudeAt(blockX + 0.5, lat);
		double elevation = field.coarseElevationMeters(lat, lon);
		boolean sea = elevation < 0;
		se.itssimple.realearth.climate.Koppen koppen = se.itssimple.realearth.climate.Koppen.get();
		int climate = koppen == null ? 0 : koppen.classAt(lat, lon);
		ResourceKey<Biome> surface = BiomePicker.pick(lat, lon, elevation, sea, false, 0, climate, 0);
		int depth = (int) Math.round(field.toY(elevation, lat)) - blockY;
		return holder(underground(surface, GeoNoise.sample(lat, lon, 0.5, 21), depth));
	}
}
