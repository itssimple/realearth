package se.itssimple.realearth.world;

import java.util.BitSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.ChunkTerrain;

/**
 * Which blocks of one chunk are cave: vanilla's noise caves (see {@link Caves}) plus ravines.
 *
 * The cave density is sampled where vanilla samples it -- on cells 4 blocks wide and 8 tall -- and
 * interpolated in between, which is both what gives vanilla caves their smooth walls and what keeps
 * this affordable: a few hundred evaluations per chunk instead of tens of thousands.
 */
final class CaveMask {
	private static final int CELL_XZ = 4;
	private static final int CELL_Y = 8;

	private static final Identifier RAVINE_RANDOM = RealEarth.id("ravines");
	/** Chance that a chunk starts a ravine, as vanilla's canyon carver. */
	private static final float RAVINE_CHANCE = 0.01f;
	/** Ravines are carved from starts up to this many chunks away. */
	private static final int RAVINE_REACH = 8;

	private final int lowestY;
	private final int height;
	private final double[] density;
	private final int cellsY;
	private final BitSet ravine;
	private final int[] top;

	private CaveMask(int lowestY, int height, double[] density, int cellsY, BitSet ravine, int[] top) {
		this.lowestY = lowestY;
		this.height = height;
		this.density = density;
		this.cellsY = cellsY;
		this.ravine = ravine;
		this.top = top;
	}

	int lowestY() {
		return lowestY;
	}

	/** True when the block at local (x, z) and world y is cave or ravine. */
	boolean isCave(int x, int y, int z) {
		if (y < lowestY || y >= lowestY + height) {
			return false;
		}
		int depth = top[z * 16 + x] - y;
		if (depth < 1 || depth > Caves.MAX_DEPTH) {
			return false;
		}
		int ry = y - lowestY;
		if (ravine.get((ry * 16 + z) * 16 + x)) {
			return true;
		}
		int cy = ry / CELL_Y;
		double fy = (ry % CELL_Y) / (double) CELL_Y;
		int cx = x / CELL_XZ;
		double fx = (x % CELL_XZ) / (double) CELL_XZ;
		int cz = z / CELL_XZ;
		double fz = (z % CELL_XZ) / (double) CELL_XZ;
		double v = Mth.lerp3(fx, fy, fz,
			d(cx, cy, cz), d(cx + 1, cy, cz), d(cx, cy + 1, cz), d(cx + 1, cy + 1, cz),
			d(cx, cy, cz + 1), d(cx + 1, cy, cz + 1), d(cx, cy + 1, cz + 1), d(cx + 1, cy + 1, cz + 1));
		return v < 0;
	}

	private double d(int cx, int cy, int cz) {
		return density[(cy * 5 + cz) * 5 + cx];
	}

	static CaveMask compute(ChunkTerrain t, ChunkPos pos, int minTop, int maxTop, RandomState randomState, RegistryAccess registries) {
		int lowest = Math.floorDiv(minTop - Caves.MAX_DEPTH - 2, CELL_Y) * CELL_Y;
		int highest = Math.floorDiv(maxTop + CELL_Y, CELL_Y) * CELL_Y;
		int cellsY = (highest - lowest) / CELL_Y + 1;
		int height = highest - lowest;

		int[] top = new int[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				top[z * 16 + x] = t.topSolidY[ChunkTerrain.index(x, z)];
			}
		}

		DensityFunction caves = Caves.forState(randomState, registries);
		double[] density = new double[cellsY * 25];
		int baseX = pos.getMinBlockX();
		int baseZ = pos.getMinBlockZ();
		for (int cz = 0; cz < 5; cz++) {
			for (int cx = 0; cx < 5; cx++) {
				int ground = t.topSolidY[ChunkTerrain.index(cx * CELL_XZ, cz * CELL_XZ)];
				for (int cy = 0; cy < cellsY; cy++) {
					int y = lowest + cy * CELL_Y;
					int depth = ground - y;
					double value;
					if (depth < 0 || depth > Caves.MAX_DEPTH + CELL_Y) {
						value = 1.0;
					} else {
						value = caves.compute(new DensityFunction.SinglePointContext(baseX + cx * CELL_XZ, Caves.VANILLA_SURFACE - depth, baseZ + cz * CELL_XZ));
					}
					density[(cy * 5 + cz) * 5 + cx] = value;
				}
			}
		}

		BitSet ravine = new BitSet(16 * 16 * Math.max(1, height));
		carveRavines(ravine, pos, top, lowest, height, randomState.getOrCreateRandomFactory(RAVINE_RANDOM));
		return new CaveMask(lowest, height, density, cellsY, ravine, top);
	}

	/**
	 * Vanilla-style ravines: long, narrow, very tall ellipsoid chains. Their depth is measured from
	 * the local ground at every step, so a ravine follows the land rather than cutting a level
	 * trench through hills.
	 */
	private static void carveRavines(BitSet mask, ChunkPos pos, int[] top, int lowest, int height, PositionalRandomFactory randoms) {
		int minX = pos.getMinBlockX();
		int minZ = pos.getMinBlockZ();
		for (int sx = pos.x() - RAVINE_REACH; sx <= pos.x() + RAVINE_REACH; sx++) {
			for (int sz = pos.z() - RAVINE_REACH; sz <= pos.z() + RAVINE_REACH; sz++) {
				RandomSource random = randoms.at(sx, 0, sz);
				if (random.nextFloat() >= RAVINE_CHANCE) {
					continue;
				}
				double x = sx * 16 + random.nextInt(16);
				double z = sz * 16 + random.nextInt(16);
				double depth = 12 + random.nextInt(48);
				float yaw = random.nextFloat() * Mth.TWO_PI;
				float pitch = (random.nextFloat() - 0.5f) * 0.25f;
				float yawDrift = 0;
				float pitchDrift = 0;
				double width = (random.nextFloat() * 2.0f + random.nextFloat()) * 2.0;
				int length = 84 + random.nextInt(28);

				for (int step = 0; step < length; step++) {
					double radius = 1.5 + Mth.sin(step * Mth.PI / length) * width;
					double vertical = radius * 3.0;
					x += Mth.cos(yaw) * Mth.cos(pitch);
					z += Mth.sin(yaw) * Mth.cos(pitch);
					depth -= Mth.sin(pitch);
					pitch *= 0.7f;
					pitch += pitchDrift * 0.05f;
					yaw += yawDrift * 0.05f;
					pitchDrift = pitchDrift * 0.8f + (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0f;
					yawDrift = yawDrift * 0.5f + (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0f;

					if (x + radius < minX || x - radius > minX + 15 || z + radius < minZ || z - radius > minZ + 15) {
						continue;
					}
					for (int lx = Math.max(0, (int) Math.floor(x - radius) - minX); lx <= Math.min(15, (int) Math.ceil(x + radius) - minX); lx++) {
						for (int lz = Math.max(0, (int) Math.floor(z - radius) - minZ); lz <= Math.min(15, (int) Math.ceil(z + radius) - minZ); lz++) {
							double dx = (minX + lx + 0.5 - x) / radius;
							double dz = (minZ + lz + 0.5 - z) / radius;
							double flat = dx * dx + dz * dz;
							if (flat >= 1.0) {
								continue;
							}
							double centreY = top[lz * 16 + lx] - depth;
							int y0 = (int) Math.floor(centreY - vertical);
							int y1 = (int) Math.ceil(centreY + vertical);
							for (int y = Math.max(y0, lowest); y <= Math.min(y1, lowest + height - 1); y++) {
								double dy = (y + 0.5 - centreY) / vertical;
								if (flat + dy * dy < 1.0) {
									mask.set(((y - lowest) * 16 + lz) * 16 + lx);
								}
							}
						}
					}
				}
			}
		}
	}
}
