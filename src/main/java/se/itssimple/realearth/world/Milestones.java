package se.itssimple.realearth.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Advancements for real places (the "Real Earth" tab): setting foot on Earth, walking into a town and a
 * capital, crossing the equator, a polar circle, the prime meridian and the date line, standing high
 * in thin air or on dry land below sea level. Each has one criterion, granted from here.
 */
public final class Milestones {
	/** Every this many ticks each player's whereabouts are looked at. */
	private static final int EVERY = 20;
	/** Latitude of the Arctic and Antarctic Circles, in degrees. */
	private static final double POLAR_CIRCLE = 66.5634;
	/** Real height above sea level for "Thin Air", in metres. */
	private static final double THIN_AIR = 4000;
	/** Ground this far below sea level counts as below it: polders and the Dead Sea, not a beach. */
	private static final double BELOW_SEA = -1.5;
	private static final String CRITERION = "been_there";

	/** Each player's last latitude and longitude. */
	private static final Map<UUID, double[]> LAST = new HashMap<>();

	private Milestones() {
	}

	/** For the server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY != 0) {
			return;
		}
		TerrainField field = RealEarth.terrain();
		ServerLevel earth = RealEarth.earthLevel(server);
		if (field == null || earth == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != earth || player.isSpectator()) {
				LAST.remove(player.getUUID());
				continue;
			}
			award(player, "root");
			double lat = field.projection().latitudeAt(player.getZ());
			double lon = field.projection().longitudeAt(player.getX(), lat);
			if (Double.isNaN(lat) || Double.isNaN(lon)) {
				continue;
			}
			lon = ((lon + 180) % 360 + 360) % 360 - 180; // the copies round the globe too
			double[] last = LAST.put(player.getUUID(), new double[] {lat, lon});
			if (Math.abs(lat) >= POLAR_CIRCLE) {
				award(player, "polar_circle");
			}
			if (last != null) {
				if (Math.signum(last[0]) != Math.signum(lat) && Math.abs(last[0] - lat) < 1) {
					award(player, "equator");
				}
				// Over zero, a small step from one side to the other; over 180, a step all the way round.
				double step = Math.abs(last[1] - lon);
				if (Math.signum(last[1]) != Math.signum(lon) && step < 1) {
					award(player, "prime_meridian");
				} else if (step > 359) {
					award(player, "date_line");
				}
			}
			if (player.onGround() && !player.isInWater()) {
				double metres = field.scale().toElevation(player.getY());
				if (metres >= THIN_AIR) {
					award(player, "thin_air");
				} else if (metres <= BELOW_SEA && earth.canSeeSky(player.blockPosition())) {
					award(player, "below_the_sea"); // out in the open: not a cellar or a mine
				}
			}
		}
	}

	/** A player has walked into a town on the map. */
	static void town(ServerPlayer player, Places.Here here) {
		award(player, "town");
		if (here.capital()) {
			award(player, "capital");
		}
	}

	/** Forgets where players were; for a new world. */
	public static void clear() {
		LAST.clear();
	}

	private static void award(ServerPlayer player, String name) {
		MinecraftServer server = player.level().getServer();
		AdvancementHolder holder = server == null ? null : server.getAdvancements().get(RealEarth.id(name));
		if (holder != null && !player.getAdvancements().getOrStartProgress(holder).isDone()) {
			player.getAdvancements().award(holder, CRITERION);
		}
	}
}
