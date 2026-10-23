package se.itssimple.realearth.api;

import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.terrain.BuildingPlan;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.Interior;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.world.Places;

/**
 * What Real Earth knows about a place, for other mods: where on Earth a block is, and what the map has
 * there (the town, the street, the building and its room).
 *
 * Every query is for the server side (a {@link ServerLevel} of the Earth), from the server thread, and
 * answers empty for any other level. Answers about the map are empty, too, where its data is not
 * loaded yet: nothing here waits for a download or generates a chunk.
 *
 * Names in answers (a building's use, a room's kind) are lower-case words that stay the same across
 * versions; new ones may be added.
 */
public final class RealEarthApi {
	/** Goes up when something is added; a mod that needs a newer query can check it. */
	public static final int VERSION = 1;
	/** How far from a street's centre line a block still counts as on it, in metres. */
	private static final double STREET_REACH = 15;

	/** A place on Earth in degrees: north and east positive. */
	public record LatLon(double latitude, double longitude) {
	}

	/** A block column, in block coordinates. */
	public record Column(double x, double z) {
	}

	/** A town (city, town or village) and the district of it, from the map's place names. */
	public record Place(String town, @Nullable String district, boolean capital) {
	}

	/**
	 * A building from the map.
	 *
	 * @param use    house, flats, offices, hall or warehouse
	 * @param hall   for a hall, what it is: church, library, school, market; else null
	 * @param name   the building's name from the map (a hall's, or the shop's for a room in a shop), or null
	 * @param floors how many storeys it has
	 * @param floor  the storey a block is on, from 0 at the ground; -1 for its roof
	 * @param room   the room a block is in, or null (a wall, the roof, a stairwell)
	 */
	public record Building(String use, @Nullable String hall, @Nullable String name, int floors, int floor, @Nullable Room room) {
	}

	/**
	 * A room in a building.
	 *
	 * @param kind  hall, living, kitchen, bed, bath, studio, office, shop, hallroom, landing, store
	 * @param trade for a shop, what it sells (bakery, grocery, books...); else null
	 * @param name  the shop's name from the map, or null
	 */
	public record Room(String kind, @Nullable String trade, @Nullable String name) {
	}

	private RealEarthApi() {
	}

	/** Whether a level is Real Earth's Earth, with its terrain running. */
	public static boolean isEarth(Level level) {
		return field(level) != null;
	}

	/** Latitude and longitude of a block position; empty off the Earth or beyond the edge of the map. */
	public static Optional<LatLon> latLon(Level level, double x, double z) {
		TerrainField field = field(level);
		if (field == null) {
			return Optional.empty();
		}
		double lat = field.projection().latitudeAt(z);
		if (Double.isNaN(lat) || Math.abs(lat) > 90) {
			return Optional.empty();
		}
		double offset = field.projection().longitudeOffsetAt(x, lat);
		if (Double.isNaN(offset) || !field.projection().onGlobe(lat, offset)) {
			return Optional.empty();
		}
		return Optional.of(new LatLon(lat, field.projection().longitudeAt(x, lat)));
	}

	/** The block column at a latitude and longitude; empty off the Earth. */
	public static Optional<Column> toBlock(Level level, double latitude, double longitude) {
		TerrainField field = field(level);
		if (field == null || Double.isNaN(latitude) || Double.isNaN(longitude) || Math.abs(latitude) > 90) {
			return Optional.empty();
		}
		return Optional.of(new Column(field.projection().blockX(latitude, longitude), field.projection().blockZ(latitude)));
	}

	/**
	 * Real elevation above sea level, in metres, of a block height at block Z. Heights are drawn to the
	 * same scale as distances, except where the world settings squeeze high mountains or deep seas.
	 */
	public static OptionalDouble elevationMeters(Level level, double y, double z) {
		TerrainField field = field(level);
		if (field == null) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(field.toElevation(y, field.projection().latitudeAt(z)));
	}

	/** The block height of a real elevation in metres, at block Z: the inverse of {@link #elevationMeters}. */
	public static OptionalDouble yOf(Level level, double meters, double z) {
		TerrainField field = field(level);
		if (field == null) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(field.toY(meters, field.projection().latitudeAt(z)));
	}

	/** Real ground elevation in metres at a block column, where its terrain has been worked out. */
	public static OptionalDouble groundMeters(Level level, int x, int z) {
		TerrainField field = field(level);
		double m = field == null ? Double.NaN : field.groundMetersAt(x, z);
		return Double.isNaN(m) ? OptionalDouble.empty() : OptionalDouble.of(m);
	}

	/** The town and district at a block position; empty outside any town, or while the map is loading. */
	public static Optional<Place> place(Level level, double x, double z) {
		TerrainField field = field(level);
		Places.Here here = field == null ? null : Places.at(field, x, z);
		return Optional.ofNullable(place(here));
	}

	/** The name of the street a block column is on, or next to; empty where its chunk is not generated. */
	public static Optional<String> street(Level level, int x, int z) {
		TerrainField field = field(level);
		ChunkTerrain t = field == null ? null : field.chunkIfReady(x >> 4, z >> 4);
		if (t == null) {
			return Optional.empty();
		}
		int k = ChunkTerrain.index(x & 15, z & 15);
		double lat = t.latitude[k];
		double sx = Mercator.sampleX(t.longitude[k]);
		double sy = Mercator.sampleY(lat);
		double nearest = STREET_REACH / Mercator.sampleSpacingMeters(lat);
		String name = null;
		for (OsmWater.Street st : t.streets) {
			if (st.maxX() < sx - nearest || st.minX() > sx + nearest || st.maxY() < sy - nearest || st.minY() > sy + nearest) {
				continue;
			}
			double[] p = st.points();
			for (int i = 0; i + 3 < p.length; i += 2) {
				double dx = p[i + 2] - p[i];
				double dy = p[i + 3] - p[i + 1];
				double len2 = dx * dx + dy * dy;
				double u = len2 > 0 ? Math.max(0, Math.min(1, ((sx - p[i]) * dx + (sy - p[i + 1]) * dy) / len2)) : 0;
				double d = Math.hypot(p[i] + u * dx - sx, p[i + 1] + u * dy - sy);
				if (d < nearest) {
					nearest = d;
					name = st.name();
				}
			}
		}
		return Optional.ofNullable(name);
	}

	/** The building a block belongs to, and the room it is in; empty outside buildings, or where its chunk is not generated. */
	public static Optional<Building> building(Level level, BlockPos pos) {
		TerrainField field = field(level);
		ChunkTerrain t = field == null ? null : field.chunkIfReady(pos.getX() >> 4, pos.getZ() >> 4);
		BuildingPlan plan = t == null ? null : t.buildingAt(pos.getX() & 15, pos.getZ() & 15);
		if (plan == null) {
			return Optional.empty();
		}
		int top = t.roofY[ChunkTerrain.index(pos.getX() & 15, pos.getZ() & 15)];
		if (pos.getY() < plan.floorY - 1 || pos.getY() > Math.max(top, plan.roofBaseY())) {
			return Optional.empty();
		}
		int floor = pos.getY() >= plan.roofBaseY() ? -1 : Math.max(0, Math.min(plan.floors - 1, (pos.getY() - plan.floorY) / plan.storey));
		Room room = null;
		String name = plan.hall != null && !plan.shops.isEmpty() ? plan.shops.get(0).name() : null;
		if (floor >= 0) {
			Interior.Room r = plan.interior().roomAt(pos.getX(), pos.getZ(), floor);
			if (r != null) {
				room = new Room(word(r.kind), r.trade == null ? null : word(r.trade), r.name);
				if (r.name != null) {
					name = r.name;
				}
			}
		}
		return Optional.of(new Building(word(plan.use), plan.hall == null ? null : word(plan.hall), name, plan.floors, floor, room));
	}

	/** The API's form of where someone is, or null outside any town. */
	public static @Nullable Place place(@Nullable Places.Here here) {
		return here == null || here.town() == null ? null : new Place(here.town(), here.district(), here.capital());
	}

	private static String word(Enum<?> e) {
		return e.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The terrain, when the level is the Earth on this side's server. */
	private static @Nullable TerrainField field(@Nullable Level level) {
		if (!(level instanceof ServerLevel) || !RealEarth.isEarth(level)) {
			return null;
		}
		return RealEarth.terrain();
	}
}
