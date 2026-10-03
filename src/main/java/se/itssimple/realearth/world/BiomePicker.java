package se.itssimple.realearth.world;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import se.itssimple.realearth.climate.Koppen;
import se.itssimple.realearth.landcover.WorldCover;

/**
 * Chooses a vanilla biome for a place on Earth from real data.
 *
 * Land cover (ESA WorldCover, 10 m) says WHAT grows there: forest, scrub, grass, crops, wetland, bare
 * ground or ice. The climate (Köppen-Geiger, 1 km) says WHICH kind: the forest of a Swedish hillside
 * is taiga, of the Amazon jungle, of Tuscany a light oak wood. Elevation still decides the snowline
 * on mountains, and smooth noise only picks between neighbouring kinds within one type (spruce or
 * birch, oak or dark oak), so regions are not one biome edge to edge.
 *
 * Where a source has no answer -- land cover outside its coverage, or no climate at sea -- the
 * other carries on alone, and with neither, latitude does.
 */
public final class BiomePicker {
	private BiomePicker() {
	}

	/** Real-world snowline: ~5,000 m in the tropics, ~3,300 m in the Alps, ~1,400 m at Kebnekaise. */
	public static double snowlineMeters(double latitude) {
		double t = Math.abs(latitude) / 80.0;
		return Math.max(0, 5000.0 * (1.0 - t * t));
	}

	public static double treelineMeters(double latitude) {
		return snowlineMeters(latitude) - 800.0;
	}

	public static ResourceKey<Biome> pick(double latitude, double longitude, double groundMeters, boolean water, boolean lake,
		double waterMeters, int climate, int cover) {
		double alat = Math.abs(latitude);

		if (water && !lake) {
			double depth = waterMeters - groundMeters;
			boolean deep = depth > 150;
			if (alat < 20) {
				return Biomes.WARM_OCEAN;
			} else if (alat < 32) {
				return deep ? Biomes.DEEP_LUKEWARM_OCEAN : Biomes.LUKEWARM_OCEAN;
			} else if (alat >= 66) {
				return deep ? Biomes.DEEP_FROZEN_OCEAN : Biomes.FROZEN_OCEAN;
			} else if (alat >= 50) {
				return deep ? Biomes.DEEP_COLD_OCEAN : Biomes.COLD_OCEAN;
			}
			return deep ? Biomes.DEEP_OCEAN : Biomes.OCEAN;
		}

		double snowline = Koppen.iceCap(climate) ? -1000 : snowlineMeters(latitude);
		double treeline = treelineMeters(latitude);
		if (lake) {
			return Koppen.polar(climate) || Koppen.veryCold(climate) || waterMeters > snowline ? Biomes.FROZEN_RIVER : Biomes.RIVER;
		}

		double variety = GeoNoise.sample(latitude, longitude, 25.0, 2);
		double patch = GeoNoise.sample(latitude, longitude, 3.0, 5);
		double e = groundMeters;

		if (cover == WorldCover.SNOW_ICE || (Koppen.iceCap(climate) && cover != WorldCover.BARE)) {
			return e > treeline ? Biomes.FROZEN_PEAKS : Biomes.SNOWY_PLAINS;
		}
		if (cover != WorldCover.TREES) {
			if (e > snowline + 400) {
				return variety > 0 ? Biomes.FROZEN_PEAKS : Biomes.JAGGED_PEAKS;
			}
			if (e > snowline) {
				return Biomes.SNOWY_SLOPES;
			}
		}

		if (climate == Koppen.SEA && cover == WorldCover.NONE) {
			return byLatitude(latitude, e, treeline, variety);
		}

		return switch (cover) {
			case WorldCover.TREES -> forest(climate, latitude, variety, patch);
			case WorldCover.SHRUBS -> shrubs(climate);
			case WorldCover.GRASS, WorldCover.MOSS -> grass(climate, e, treeline);
			case WorldCover.CROPS -> Koppen.temperate(climate) && patch > 0.6 ? Biomes.SUNFLOWER_PLAINS : Biomes.PLAINS;
			case WorldCover.BUILT -> builtUp(climate);
			case WorldCover.BARE -> bare(climate, e, treeline, variety);
			case WorldCover.WATER -> Koppen.polar(climate) ? Biomes.FROZEN_RIVER : Biomes.RIVER;
			case WorldCover.WETLAND -> Biomes.SWAMP;
			case WorldCover.MANGROVES -> Biomes.MANGROVE_SWAMP;
			default -> climateOnly(climate, latitude, e, treeline, variety, patch);
		};
	}

	/**
	 * Towns take the biome whose village style suits the climate: adobe in deserts, acacia in the
	 * tropics and dry savanna, spruce in the north, snowy in the far north, plains villages elsewhere.
	 */
	private static ResourceKey<Biome> builtUp(int c) {
		if (Koppen.desert(c)) {
			return Biomes.DESERT;
		}
		if (Koppen.tropical(c) || (Koppen.arid(c) && Koppen.hotArid(c))) {
			return Biomes.SAVANNA;
		}
		if (Koppen.polar(c) || Koppen.veryCold(c)) {
			return Biomes.SNOWY_PLAINS;
		}
		if (Koppen.subarctic(c)) {
			return Biomes.TAIGA;
		}
		return Biomes.PLAINS;
	}

	private static ResourceKey<Biome> forest(int c, double latitude, double variety, double patch) {
		if (c == 1) {
			return Biomes.JUNGLE;
		}
		if (c == 2) {
			return patch > 0.55 ? Biomes.BAMBOO_JUNGLE : Biomes.JUNGLE;
		}
		if (c == 3) {
			return patch > 0.2 ? Biomes.SPARSE_JUNGLE : Biomes.SAVANNA;
		}
		if (Koppen.hotArid(c)) {
			return Biomes.SAVANNA;
		}
		if (Koppen.arid(c)) {
			return Biomes.TAIGA;
		}
		if (Koppen.mediterranean(c) && c != 10) {
			return patch > 0.5 ? Biomes.FLOWER_FOREST : Biomes.FOREST;
		}
		if (Koppen.veryCold(c) || Koppen.polar(c)) {
			return Biomes.SNOWY_TAIGA;
		}
		if (Koppen.coolSummer(c)) {
			return patch > 0.55 ? Biomes.OLD_GROWTH_SPRUCE_TAIGA : Biomes.TAIGA;
		}
		if (c == 26 || c == 22 || c == 18) {
			// Warm-summer continental: the hemiboreal belt -- conifers in the north, mixed further south.
			if (Math.abs(latitude) >= 58) {
				return patch > 0.45 ? Biomes.BIRCH_FOREST : Biomes.TAIGA;
			}
			return patch > 0.35 ? Biomes.BIRCH_FOREST : patch < -0.4 ? Biomes.TAIGA : Biomes.FOREST;
		}
		if (c == 15 || c == 12) {
			return patch > 0.4 ? Biomes.BIRCH_FOREST : patch < -0.55 ? Biomes.DARK_FOREST : Biomes.FOREST;
		}
		if (c == 14 || c == 11 || c == 25 || c == 21 || c == 17) {
			return patch > 0.4 ? Biomes.DARK_FOREST : variety > 0.6 ? Biomes.FLOWER_FOREST : Biomes.FOREST;
		}
		return Biomes.FOREST;
	}

	private static ResourceKey<Biome> shrubs(int c) {
		if (Koppen.tropical(c) || Koppen.hotArid(c) || Koppen.mediterranean(c)) {
			return Biomes.SAVANNA;
		}
		if (Koppen.polar(c) || Koppen.veryCold(c)) {
			return Biomes.SNOWY_PLAINS;
		}
		return Biomes.PLAINS;
	}

	private static ResourceKey<Biome> grass(int c, double e, double treeline) {
		if (Koppen.tropical(c) || Koppen.hotArid(c)) {
			return Biomes.SAVANNA;
		}
		if (Koppen.polar(c)) {
			return Biomes.SNOWY_PLAINS;
		}
		return e > treeline ? Biomes.MEADOW : Biomes.PLAINS;
	}

	private static ResourceKey<Biome> bare(int c, double e, double treeline, double variety) {
		if (Koppen.tropical(c) || c == 4 || c == 6) {
			return variety > 0.55 ? Biomes.BADLANDS : Biomes.DESERT;
		}
		if (c == 5 || c == 7) {
			return Biomes.DESERT;
		}
		if (Koppen.polar(c)) {
			return Biomes.SNOWY_PLAINS;
		}
		return e > treeline ? Biomes.STONY_PEAKS : Biomes.WINDSWEPT_GRAVELLY_HILLS;
	}

	/** No land cover here: let the climate's typical vegetation stand in. */
	private static ResourceKey<Biome> climateOnly(int c, double latitude, double e, double treeline, double variety, double patch) {
		if (e > treeline) {
			return Koppen.polar(c) ? Biomes.SNOWY_PLAINS : Biomes.MEADOW;
		}
		if (Koppen.desert(c)) {
			return bare(c, e, treeline, variety);
		}
		if (Koppen.arid(c)) {
			return grass(c, e, treeline);
		}
		if (Koppen.polar(c)) {
			return Biomes.SNOWY_PLAINS;
		}
		return forest(c, latitude, variety, patch);
	}

	/** The fallback when there is no data at all: climate belts by latitude. */
	private static ResourceKey<Biome> byLatitude(double latitude, double e, double treeline, double variety) {
		double alat = Math.abs(latitude);
		if (e > treeline) {
			return alat >= 64 ? Biomes.SNOWY_PLAINS : Biomes.MEADOW;
		}
		if (alat >= 64) {
			return Biomes.SNOWY_TAIGA;
		}
		if (alat >= 55) {
			return Biomes.TAIGA;
		}
		if (alat >= 35) {
			return variety > 0.3 ? Biomes.BIRCH_FOREST : Biomes.FOREST;
		}
		if (alat >= 15) {
			return Biomes.SAVANNA;
		}
		return Biomes.JUNGLE;
	}

	/** Every biome {@link #pick} can return. The biome source must declare them all up front. */
	public static final List<ResourceKey<Biome>> ALL = List.of(
		Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
		Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.OCEAN, Biomes.DEEP_OCEAN,
		Biomes.RIVER, Biomes.FROZEN_RIVER,
		Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.SNOWY_SLOPES, Biomes.SNOWY_PLAINS, Biomes.BADLANDS,
		Biomes.STONY_PEAKS, Biomes.MEADOW, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.BIRCH_FOREST,
		Biomes.TAIGA, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.DARK_FOREST, Biomes.FOREST, Biomes.SWAMP,
		Biomes.MANGROVE_SWAMP, Biomes.DESERT, Biomes.FLOWER_FOREST, Biomes.SAVANNA, Biomes.SPARSE_JUNGLE, Biomes.JUNGLE,
		Biomes.BAMBOO_JUNGLE, Biomes.WINDSWEPT_GRAVELLY_HILLS
	);
}
