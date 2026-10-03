package se.itssimple.realearth.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.landcover.WorldCover;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Structures where the real world is built up: a random-spread grid, much denser than vanilla's
 * villages, of which only the cells whose ground is mostly built-up land (ESA WorldCover) are used.
 * So towns and cities fill with villages, and the countryside keeps vanilla's scattered ones.
 *
 * Only decided while Earth generates structures for a chunk (see {@link GenContext}): anywhere else
 * -- other dimensions, /locate probing far away -- the answer is no, so no level but Earth gets these,
 * and /locate never points at a village that isn't there.
 */
public final class BuiltUpPlacement extends RandomSpreadStructurePlacement {
	public static final MapCodec<BuiltUpPlacement> CODEC = RecordCodecBuilder.<BuiltUpPlacement>mapCodec(i -> i.group(
			Codec.intRange(1, 4096).fieldOf("spacing").forGetter((BuiltUpPlacement p) -> p.spacing()),
			Codec.intRange(0, 4096).fieldOf("separation").forGetter((BuiltUpPlacement p) -> p.separation()),
			Codec.INT.fieldOf("salt").forGetter((BuiltUpPlacement p) -> p.salt()),
			Codec.doubleRange(0.0, 1.0).optionalFieldOf("min_built_share", 0.35).forGetter((BuiltUpPlacement p) -> p.minBuiltShare()),
			Codec.intRange(8, 256).optionalFieldOf("radius", 48).forGetter((BuiltUpPlacement p) -> p.radius())
		).apply(i, BuiltUpPlacement::new))
		.validate(p -> p.spacing() <= p.separation() ? DataResult.error(() -> "Spacing has to be larger than separation") : DataResult.success(p));

	public static final StructurePlacementType<BuiltUpPlacement> TYPE = () -> CODEC;

	/** Land cover is sampled on a grid this many points across, over the village's area. */
	private static final int SAMPLES = 9;

	private final double minBuiltShare;
	private final int radius;

	public BuiltUpPlacement(int spacing, int separation, int salt, double minBuiltShare, int radius) {
		super(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F, salt, Optional.empty(), spacing, separation, RandomSpreadType.LINEAR);
		this.minBuiltShare = minBuiltShare;
		this.radius = radius;
	}

	public double minBuiltShare() {
		return minBuiltShare;
	}

	public int radius() {
		return radius;
	}

	@Override
	public boolean applyAdditionalChunkRestrictions(int sourceX, int sourceZ, long levelSeed) {
		return super.applyAdditionalChunkRestrictions(sourceX, sourceZ, levelSeed) && GenContext.exact()
			&& builtShare(sourceX * 16 + 8, sourceZ * 16 + 8, radius) >= minBuiltShare
			&& !nearRailOrMotorway(sourceX * 16 + 8, sourceZ * 16 + 8, radius + 16)
			&& mappedBuildings(sourceX * 16 + 8, sourceZ * 16 + 8, radius) < BUILDINGS_REPLACE_VILLAGE;
	}

	/**
	 * Share of built-up land around a block position, 0..1; 0 where the centre is water or the land
	 * cover is unavailable. Blocks on the download; call it from world generation threads only.
	 */
	public static double builtShare(int centreX, int centreZ, int radius) {
		TerrainField field = RealEarth.terrain();
		WorldCover cover = field == null ? null : field.landCover();
		if (cover == null) {
			return 0;
		}
		double[] lats = new double[SAMPLES * SAMPLES];
		double[] lons = new double[SAMPLES * SAMPLES];
		double step = 2.0 * radius / (SAMPLES - 1);
		for (int j = 0; j < SAMPLES; j++) {
			for (int i = 0; i < SAMPLES; i++) {
				double x = centreX - radius + i * step;
				double z = centreZ - radius + j * step;
				int k = j * SAMPLES + i;
				lats[k] = field.projection().latitudeAt(z);
				lons[k] = field.projection().longitudeAt(x, lats[k]);
			}
		}
		try {
			WorldCover.Lookup lookup = cover.prepare(lats, lons, 0).get(60, TimeUnit.SECONDS);
			if (lookup.degraded()) {
				return 0;
			}
			int centre = (SAMPLES / 2) * SAMPLES + SAMPLES / 2;
			if (lookup.classAt(lats[centre], lons[centre]) == WorldCover.WATER) {
				return 0;
			}
			int built = 0;
			for (int k = 0; k < lats.length; k++) {
				if (lookup.classAt(lats[k], lons[k]) == WorldCover.BUILT) {
					built++;
				}
			}
			return built / (double) lats.length;
		} catch (Exception e) {
			return 0;
		}
	}

	/**
	 * True when a railway or motorway passes within {@code radius} blocks. A village there would lay its
	 * houses across the tracks and reshape the ground under them.
	 */
	private static boolean nearRailOrMotorway(int centreX, int centreZ, int radius) {
		TerrainField field = RealEarth.terrain();
		if (field == null || field.map() == null) {
			return false;
		}
		double lat = field.projection().latitudeAt(centreZ);
		double lon = field.projection().longitudeAt(centreX, lat);
		double sx = se.itssimple.realearth.geo.Mercator.sampleX(lon);
		double sy = se.itssimple.realearth.geo.Mercator.sampleY(lat);
		double reach = radius / field.projection().blocksPerMeter(lat) / se.itssimple.realearth.geo.Mercator.sampleSpacingMeters(lat);
		try {
			for (int[] t : se.itssimple.realearth.osm.OsmWater.tilesCovering((long) (sx - reach), (long) (sy - reach), (long) (sx + reach), (long) (sy + reach))) {
				for (var way : field.map().tile(t[0], t[1]).get(60, TimeUnit.SECONDS).ways()) {
					if (way.kind() != se.itssimple.realearth.osm.Ways.RAIL && way.kind() != se.itssimple.realearth.osm.Ways.MOTORWAY) {
						continue;
					}
					if (way.maxX() < sx - reach || way.minX() > sx + reach || way.maxY() < sy - reach || way.minY() > sy + reach) {
						continue;
					}
					double[] p = way.points();
					for (int i = 0; i + 3 < p.length; i += 2) {
						if (segmentDistance(sx, sy, p[i], p[i + 1], p[i + 2], p[i + 3]) < reach) {
							return true;
						}
					}
				}
			}
		} catch (Exception e) {
			return false;
		}
		return false;
	}

	private static double segmentDistance(double x, double y, double ax, double ay, double bx, double by) {
		double dx = bx - ax;
		double dy = by - ay;
		double len = dx * dx + dy * dy;
		double u = len > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len)) : 0;
		return Math.hypot(x - (ax + u * dx), y - (ay + u * dy));
	}

	/** With this many real buildings mapped around, the town is built from them instead of a village. */
	public static final int BUILDINGS_REPLACE_VILLAGE = 2;

	/**
	 * How many of the map's buildings reach within {@code radius} blocks (by their bounding boxes), when
	 * buildings are being built at all; 0 otherwise. Blocks on the map download.
	 */
	public static int mappedBuildings(int centreX, int centreZ, int radius) {
		TerrainField field = RealEarth.terrain();
		if (field == null || field.map() == null || !RealEarth.config().useBuildings) {
			return 0;
		}
		double lat = field.projection().latitudeAt(centreZ);
		double lon = field.projection().longitudeAt(centreX, lat);
		double sx = se.itssimple.realearth.geo.Mercator.sampleX(lon);
		double sy = se.itssimple.realearth.geo.Mercator.sampleY(lat);
		double reach = radius / field.projection().blocksPerMeter(lat) / se.itssimple.realearth.geo.Mercator.sampleSpacingMeters(lat);
		int count = 0;
		try {
			for (int[] t : se.itssimple.realearth.osm.OsmWater.tilesCovering((long) (sx - reach), (long) (sy - reach), (long) (sx + reach), (long) (sy + reach))) {
				for (var b : field.map().tile(t[0], t[1]).get(60, TimeUnit.SECONDS).buildings()) {
					if (b.maxX() >= sx - reach && b.minX() <= sx + reach && b.maxY() >= sy - reach && b.minY() <= sy + reach) {
						count++;
					}
				}
			}
		} catch (Exception e) {
			return 0;
		}
		return count;
	}

	@Override
	public StructurePlacementType<?> type() {
		return TYPE;
	}
}
