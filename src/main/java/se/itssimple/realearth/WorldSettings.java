package se.itssimple.realearth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import se.itssimple.realearth.geo.Projection;

/**
 * The per-world part of the configuration: how this world is laid onto the globe, and where players
 * start.
 *
 * Written to realearth.json in the world folder the first time a world is opened with the mod,
 * copied from the global config. From then on the global config no longer changes this world, so
 * editing it cannot tear terrain that already exists.
 *
 * Worlds made before the Mercator projection existed have no {@code projection} here and stay
 * sinusoidal around their origin, starting at it.
 */
public final class WorldSettings {
	/** "mercator" or "sinusoidal"; missing in worlds made before Mercator. */
	public String projection;
	/** Sinusoidal only: the projection's centre. Mercator is always centred on 0, 0. */
	public double originLatitude;
	public double originLongitude;
	/** Where new players appear, and where Overworld portals lead relative to. */
	public Double startLatitude;
	public Double startLongitude;
	/** Mercator only: the latitude where one block is exactly one metre. */
	public Double scaleLatitude;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Beyond this the rest of the world would come out very small. */
	private static final double MAX_SCALE_LATITUDE = 70.0;

	public static WorldSettings loadOrCreate(Path file, EarthConfig defaults) {
		try {
			if (Files.exists(file)) {
				WorldSettings read = GSON.fromJson(Files.readString(file), WorldSettings.class);
				if (read != null) {
					if (read.projection == null) {
						// Made before Mercator: sinusoidal around the origin, starting there.
						read.projection = "sinusoidal";
						read.startLatitude = read.originLatitude;
						read.startLongitude = read.originLongitude;
						read.save(file);
					}
					return read;
				}
			}
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.error("Could not read {}; falling back to the global configuration", file, e);
		}

		WorldSettings settings = new WorldSettings();
		settings.projection = "sinusoidal".equalsIgnoreCase(defaults.projection) ? "sinusoidal" : "mercator";
		settings.startLatitude = defaults.startLatitude;
		settings.startLongitude = defaults.startLongitude;
		if (settings.projection.equals("sinusoidal")) {
			settings.originLatitude = defaults.startLatitude;
			settings.originLongitude = defaults.startLongitude;
		} else {
			double scale = defaults.scaleLatitude != null ? defaults.scaleLatitude : defaults.startLatitude;
			settings.scaleLatitude = Math.max(-MAX_SCALE_LATITUDE, Math.min(MAX_SCALE_LATITUDE, scale));
		}
		settings.save(file);
		return settings;
	}

	private void save(Path file) {
		try {
			Files.writeString(file, GSON.toJson(this));
		} catch (IOException e) {
			RealEarth.LOGGER.error("Could not write {}", file, e);
		}
	}

	public Projection toProjection() {
		return "sinusoidal".equals(projection)
			? new Projection.Sinusoidal(originLatitude, originLongitude)
			: new Projection.WebMercator(scaleLatitude == null ? 0.0 : scaleLatitude);
	}
}
