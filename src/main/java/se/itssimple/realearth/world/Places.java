package se.itssimple.realearth.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Where on Earth a player is, by the map's place names: the town (city, town or village) and the district
 * of it (suburb, quarter, neighbourhood). Walking into a town shows its name as a title, with the
 * district under it; walking from one district to another, the district above the hotbar.
 *
 * A town's name reaches as far as its importance: a city's some kilometres, a village's one or two.
 * Towns come from the map at zoom 10 (a tile covers about 20 km and keeps the bigger places), districts
 * from the zoom-14 tiles the terrain already reads.
 */
public final class Places {
	/** Zoom of the tiles towns are read from. */
	private static final int TOWN_ZOOM = 10;
	/** Every this many ticks each player's place is looked at again. */
	private static final int EVERY = 20;
	/** A district's name reaches this far from its point, in metres; a neighbourhood's less far. */
	private static final double SUBURB_REACH = 2000;
	private static final double NEIGHBOURHOOD_REACH = 800;

	/** Where someone is: a town and a district of it, either null. */
	public record Here(String town, String district) {
	}

	private static final Map<UUID, Here> LAST = new HashMap<>();

	private Places() {
	}

	/** For the server tick: tells players who have moved into another town or district. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY != 0 || !RealEarth.config().placeTitles) {
			return;
		}
		TerrainField field = RealEarth.terrain();
		ServerLevel earth = RealEarth.earthLevel(server);
		if (field == null || earth == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != earth) {
				LAST.remove(player.getUUID());
				continue;
			}
			Here here = at(field, player.getX(), player.getZ());
			if (here == null) {
				continue; // the map is still on its way
			}
			Here last = LAST.put(player.getUUID(), here);
			if (Objects.equals(last, here)) {
				continue;
			}
			if (here.town() != null && (last == null || !here.town().equals(last.town()))) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(here.district() == null ? "" : here.district())
					.withStyle(ChatFormatting.GRAY)));
				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(here.town())));
			} else if (here.district() != null && (last == null || !here.district().equals(last.district()))) {
				String line = here.town() == null ? here.district() : here.district() + ", " + here.town();
				player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(line).withStyle(ChatFormatting.GRAY)));
			}
		}
	}

	/** Forgets who was where; for a new world. */
	public static void clear() {
		LAST.clear();
	}

	/**
	 * The town and district at a block position, or null when the map tiles for it are not loaded yet
	 * (they are asked for, so a later call has them).
	 */
	public static Here at(TerrainField field, double blockX, double blockZ) {
		double lat = field.projection().latitudeAt(blockZ);
		double lon = field.projection().longitudeAt(blockX, lat);
		if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 85) {
			return new Here(null, null);
		}
		double sx = Mercator.sampleX(lon);
		double sy = Mercator.sampleY(lat);
		double spacing = Mercator.sampleSpacingMeters(lat);
		OsmWater map = field.map();
		if (map == null) {
			return new Here(null, null);
		}
		List<OsmWater.Place> towns = around(map, TOWN_ZOOM, sx, sy);
		List<OsmWater.Place> districts = around(map, OsmWater.ZOOM, sx, sy);
		if (towns == null || districts == null) {
			return null;
		}
		String town = null;
		double best = Double.MAX_VALUE;
		for (OsmWater.Place p : towns) {
			double reach = switch (p.cls()) {
				case "city" -> 12000;
				case "town" -> 5000;
				case "village" -> 2000;
				default -> 0;
			};
			double d = Math.hypot(p.x() - sx, p.y() - sy) * spacing;
			// Nearest by how far its name reaches: a city's suburb is the city's, not the village's next door.
			if (d < reach && d / reach < best) {
				best = d / reach;
				town = p.name();
			}
		}
		String district = null;
		best = Double.MAX_VALUE;
		for (OsmWater.Place p : districts) {
			double reach = switch (p.cls()) {
				case "suburb" -> SUBURB_REACH;
				case "quarter", "neighbourhood" -> NEIGHBOURHOOD_REACH;
				case "village", "hamlet" -> town == null ? 1500 : 0;
				default -> 0;
			};
			double d = Math.hypot(p.x() - sx, p.y() - sy) * spacing;
			if (d < reach && d / reach < best && !p.name().equals(town)) {
				best = d / reach;
				district = p.name();
			}
		}
		if (town == null && district != null) {
			// A village or hamlet the low zoom leaves out: the place itself.
			return new Here(district, null);
		}
		return new Here(town, district);
	}

	/** The places in the 3 x 3 tiles round a sample position at a zoom, or null while any is still loading. */
	private static List<OsmWater.Place> around(OsmWater map, int zoom, double sx, double sy) {
		double per = (double) Mercator.TILE_SIZE * (1 << (Mercator.ZOOM - zoom));
		int tx = (int) Math.floor(sx / per);
		int ty = (int) Math.floor(sy / per);
		List<OsmWater.Place> out = new java.util.ArrayList<>();
		boolean ready = true;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				CompletableFuture<List<OsmWater.Place>> f = map.places(zoom, tx + dx, ty + dy);
				if (!f.isDone()) {
					ready = false;
					continue;
				}
				out.addAll(f.getNow(List.of()));
			}
		}
		return ready ? out : null;
	}
}
