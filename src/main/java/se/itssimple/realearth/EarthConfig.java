package se.itssimple.realearth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global settings, read from config/realearth.json and written back with defaults filled in.
 *
 * The origin is only the default for NEW worlds: once a world has been opened, its origin is pinned
 * in the world folder (see {@link WorldSettings}), because moving it would tear every chunk generated
 * so far away from every chunk generated afterwards.
 */
public final class EarthConfig {
	/**
	 * Where new players start on Earth, and what Overworld portals lead relative to: a portal at
	 * Overworld X, Z opens this far from the start point. Default: Stockholm. Read when a world is
	 * created; each world keeps its own copy (see WorldSettings).
	 */
	public Double startLatitude;
	public Double startLongitude;

	/**
	 * "mercator" (default): north is up and shapes are true everywhere, scale varies with latitude.
	 * "sinusoidal": one block per metre everywhere, but the map leans far from the start.
	 */
	public String projection = "mercator";

	/**
	 * Mercator: the latitude where one block is exactly one metre. Leave unset to use the start
	 * latitude, so the area around the start is true to scale.
	 */
	public Double scaleLatitude;

	/** Older name of the start point, when it was also the map's centre. Read for compatibility. */
	public Double originLatitude;
	public Double originLongitude;

	/** Terrarium tile template. Zoom 15 is the highest the bucket serves (z16 answers 404). */
	public String tileUrl = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";

	/** Folder under the game directory where downloaded tiles are kept. Shared by every world. */
	public String cacheDirectory = "realearth-cache";

	/** Concurrent tile downloads. */
	public int downloadThreads = 8;

	/**
	 * Largest the download cache may grow, in megabytes; the files used longest ago are deleted
	 * beyond it. 0 means no limit. Everything in the cache can be downloaded again.
	 */
	public long cacheLimitMegabytes = 8192;

	/**
	 * Gaussian smoothing of the elevation grid, as a standard deviation in metres. Rounds off the
	 * creases left where coarse source data was bilinearly upsampled into the tile set, and the
	 * one-metre terraces of integer-valued sources, without flattening real relief.
	 */
	public double smoothingMeters = 5.0;

	/** A sample further than this from the median of its 3x3 neighbourhood is treated as a spike. */
	public double despikeMeters = 25.0;

	/**
	 * Take water from OpenStreetMap (via OpenFreeMap vector tiles): seas, lakes, rivers, canals and
	 * streams exactly where they are, and land below sea level stays dry unless the map says sea.
	 * Without it -- or where the map cannot be fetched -- water is inferred from elevation alone.
	 */
	public boolean useMapWater = true;
	/** TileJSON of the OpenMapTiles-schema vector tiles to read water from. */
	public String mapTileJson = "https://tiles.openfreemap.org/planet";

	/** Take forests, fields, grassland, wetlands, bare ground and glaciers from ESA WorldCover (10 m). */
	public boolean useLandCover = true;

	/** Draw roads, tracks and paths from OpenStreetMap (needs useMapWater's map tiles). */
	public boolean useRoads = true;
	/** Draw railways and tram lines from OpenStreetMap, with real rails. */
	public boolean useRailways = true;

	/**
	 * Build OpenStreetMap's buildings as furnished village-style houses, with wells, farms and lamps
	 * between them. Where buildings are mapped, no villages are generated.
	 */
	public boolean useBuildings = true;
	/** Show the town's name as a title on walking into it, and the district's above the hotbar, from the map's place names. */
	public boolean placeTitles = true;
	/** Height of each storey, in metres (at least 5 blocks). Storeys are counted from the real height at 3 m each. */
	public double storeyMeters = 4.5;
	/** Most storeys a building gets. */
	public int maxFloors = 10;
	/**
	 * Chance that a chunk with beds in it gets a villager (one). Villagers are costly to simulate, and a
	 * city has thousands of chunks of houses: about one chunk of town in twenty ends up with one.
	 */
	public double villagerChance = 0.2;
	public String landCoverUrl = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_{tile}_Map.tif";

	/** Lakes are perfectly flat in the source data; this finds them and fills them with water. Used where map water is not. */
	public boolean detectLakes = true;
	/** Window, in samples, that must be flat for its centre to count as water. ~40-80 m at z15. */
	public int lakeWindowSamples = 17;
	/** How flat is flat, in metres. Real lake surfaces are exactly constant; farmland is not. */
	public double lakeFlatnessMeters = 0.02;
	/** Deepest a carved lake bed goes below the water surface, in metres. */
	public double maxLakeDepthMeters = 12.0;

	/**
	 * Real elevation above which mountains are compressed to fit under the build limit. Below this,
	 * one block is exactly one metre; above it the scale eases off smoothly so Everest still fits.
	 */
	public double compressAboveMeters = 2800.0;

	/** Ocean depth below which the seabed is compressed to fit above the bottom of the world. */
	public double compressBelowMeters = -600.0;

	/** Permission level needed for /earth tp. 0 lets everyone use it. */
	public int teleportPermissionLevel = 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static EarthConfig load(Path file) {
		EarthConfig config = new EarthConfig();
		try {
			if (Files.exists(file)) {
				EarthConfig read = GSON.fromJson(Files.readString(file), EarthConfig.class);
				if (read != null) {
					config = read;
				}
			}
			if (config.startLatitude == null || config.startLongitude == null) {
				boolean old = config.originLatitude != null && config.originLongitude != null;
				config.startLatitude = old ? config.originLatitude : 59.3293;
				config.startLongitude = old ? config.originLongitude : 18.0686;
			}
			config.originLatitude = null;
			config.originLongitude = null;
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(config));
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.error("Could not read {}; using defaults", file, e);
		}
		return config;
	}
}
