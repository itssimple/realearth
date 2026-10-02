package se.itssimple.realearth.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.lod.LodSampler;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.terrain.VerticalScale;
import se.itssimple.realearth.world.Strata;

/**
 * Distant Horizons generator for Earth levels: fills each LOD straight from the data (see
 * {@link LodSampler}) instead of having DH generate vanilla chunks for it. Far terrain then costs a
 * few low-resolution tiles rather than full world generation, so the horizon can be tens of
 * kilometres away -- which at one block per metre is what makes mountains visible at all.
 */
final class EarthLodGenerator implements IDhApiWorldGenerator {
	private static final byte FINEST = 0;
	private static final byte COARSEST = 12;

	private final IDhApiLevelWrapper level;
	private final Map<String, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
	private final Map<String, IDhApiBiomeWrapper> biomes = new ConcurrentHashMap<>();

	EarthLodGenerator(IDhApiLevelWrapper level) {
		this.level = level;
	}

	@Override
	public byte getSmallestDataDetailLevel() {
		return FINEST;
	}

	@Override
	public byte getLargestDataDetailLevel() {
		return COARSEST;
	}

	@Override
	public boolean runApiValidation() {
		return Boolean.getBoolean("realearth.dhValidate");
	}

	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
	}

	@Override
	public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
		IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode, ExecutorService pool, Consumer<IDhApiFullDataSource> done) {
		return CompletableFuture.runAsync(() -> {
			TerrainField field = RealEarth.terrain();
			if (field == null) {
				return;
			}
			int width = data.getWidthInDataColumns();
			int columnSize = 1 << detailLevel;
			LodSampler.Column[] columns;
			try {
				columns = new LodSampler(field).patch(chunkPosMinX * 16, chunkPosMinZ * 16, width, columnSize);
			} catch (TerrainField.Unavailable e) {
				// Offline: fail the task so Distant Horizons asks again later instead of storing nothing.
				throw new java.util.concurrent.CompletionException(e);
			} catch (Exception e) {
				RealEarth.LOGGER.warn("Distant terrain at chunk {}, {} unavailable: {}", chunkPosMinX, chunkPosMinZ, e.toString());
				throw new java.util.concurrent.CompletionException(e);
			}
			List<DhApiTerrainDataPoint> points = new ArrayList<>(6);
			for (int z = 0; z < width; z++) {
				for (int x = 0; x < width; x++) {
					points.clear();
					fill(points, columns[z * width + x], chunkPosMinX * 16 + x * columnSize, chunkPosMinZ * 16 + z * columnSize);
					data.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.SURFACE, points);
				}
			}
			done.accept(data);
		}, pool);
	}

	/** Data points are always block-sized (detail 0); the LOD's detail level only sets how much ground a column covers. */
	private void fill(List<DhApiTerrainDataPoint> points, LodSampler.Column c, int worldX, int worldZ) {
		byte detail = 0;
		int min = VerticalScale.MIN_Y;
		int height = VerticalScale.HEIGHT;
		int ground = Math.max(1, c.groundTop() - min);
		int surfaceTop = ground + 1;
		int top = surfaceTop;

		String biomeId = c.biome();
		IDhApiBiomeWrapper biome = biome(biomeId);
		if (c.waterTop() != Integer.MIN_VALUE) {
			top = Math.max(top, c.waterTop() - min + 1);
		} else if (c.canopy() != null) {
			top = Math.max(top, c.canopyTop() - min + 1);
		}
		top = Math.min(top, height - 1);

		points.add(DhApiTerrainDataPoint.create(detail, 0, 15, top, height, block("minecraft:air"), biome));
		if (c.waterTop() != Integer.MIN_VALUE && top > surfaceTop) {
			points.add(DhApiTerrainDataPoint.create(detail, 0, 15, surfaceTop, top, block("minecraft:water"), biome));
		} else if (c.canopy() != null && top > surfaceTop) {
			points.add(DhApiTerrainDataPoint.create(detail, 0, 15, surfaceTop, top, block(c.canopy()), biome));
		}
		// Coarse columns step where the ground slopes, and Distant Horizons draws those step faces with
		// the blocks they cut through. Carrying the surface block 24 blocks down makes a grassy hillside
		// read as a grassy hillside from afar, not as grey walls.
		int skin = Math.max(0, ground - 24);
		if (Strata.BANDS.equals(c.surface())) {
			// Banded cliffs: one point per band, from the top down through the skin.
			int y = surfaceTop;
			while (y > skin) {
				String id = Strata.idAt(y - 1 + min, worldX, worldZ);
				int bottom = y - 1;
				while (bottom > skin && Strata.idAt(bottom - 1 + min, worldX, worldZ).equals(id)) {
					bottom--;
				}
				points.add(DhApiTerrainDataPoint.create(detail, 0, y == surfaceTop ? 15 : 0, bottom, y, block(id), biome));
				y = bottom;
			}
		} else {
			points.add(DhApiTerrainDataPoint.create(detail, 0, 15, skin, surfaceTop, block(c.surface()), biome));
		}
		int soil = Math.max(0, skin - 8);
		if (skin > soil) {
			points.add(DhApiTerrainDataPoint.create(detail, 0, 0, soil, skin, block(c.under()), biome));
		}
		if (soil > 0) {
			points.add(DhApiTerrainDataPoint.create(detail, 0, 0, 0, soil, block("minecraft:stone"), biome));
		}
	}

	private IDhApiBlockStateWrapper block(String id) {
		return blocks.computeIfAbsent(id, key -> {
			try {
				return key.equals("minecraft:air")
					? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
					: DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(key, level);
			} catch (Exception e) {
				RealEarth.LOGGER.warn("Distant Horizons does not know block {}: {}", key, e.toString());
				return DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
			}
		});
	}

	private IDhApiBiomeWrapper biome(String id) {
		return biomes.computeIfAbsent(id, key -> {
			try {
				return DhApi.Delayed.wrapperFactory.getBiomeWrapper(key, level);
			} catch (Exception e) {
				try {
					return DhApi.Delayed.wrapperFactory.getBiomeWrapper("minecraft:plains", level);
				} catch (Exception again) {
					throw new IllegalStateException(again);
				}
			}
		});
	}

	@Override
	public void preGeneratorTaskStart() {
	}

	@Override
	public void close() {
	}
}
