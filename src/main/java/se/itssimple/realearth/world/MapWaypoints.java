package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * The towns and railway stations round each player on the Earth, sent to their client for the
 * minimap's waypoints (Xaero's). A city's name reaches tens of kilometres, a village's a few; stations
 * the nearest few within walking distance. Worked out every few seconds from map tiles that are
 * already loaded, and sent only when the list changes.
 */
public final class MapWaypoints {
	/** What a mark is; the client picks its colour and letters from it. */
	public static final byte CAPITAL = 0;
	public static final byte CITY = 1;
	public static final byte TOWN = 2;
	public static final byte VILLAGE = 3;
	public static final byte STATION = 4;

	/** Every this many ticks each player's marks are looked at again. */
	private static final int EVERY = 100;
	private static final int MAX_TOWNS = 12;
	private static final int MAX_STATIONS = 8;
	/** Stations this far away at most, in metres. */
	private static final double STATION_REACH = 3000;

	/** A place to mark: its kind, name, and block column. */
	public record Mark(byte kind, String name, int x, int z) {
		static final StreamCodec<RegistryFriendlyByteBuf, Mark> CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, Mark::kind,
			ByteBufCodecs.STRING_UTF8, Mark::name,
			ByteBufCodecs.VAR_INT, Mark::x,
			ByteBufCodecs.VAR_INT, Mark::z,
			Mark::new);
	}

	/** All the marks round a player now; empty when they are not on the Earth. */
	public record Payload(List<Mark> marks) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(RealEarth.id("waypoints"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = Mark.CODEC.apply(ByteBufCodecs.list(256))
			.map(Payload::new, Payload::marks);

		@Override
		public Type<Payload> type() {
			return TYPE;
		}
	}

	private static final Map<UUID, List<Mark>> SENT = new ConcurrentHashMap<>();

	private MapWaypoints() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.getPlayer().getUUID()));
	}

	/** For the server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY != 0) {
			return;
		}
		TerrainField field = RealEarth.terrain();
		ServerLevel earth = RealEarth.earthLevel(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(player, Payload.TYPE)) {
				continue;
			}
			List<Mark> marks;
			if (field == null || player.level() != earth) {
				marks = List.of();
			} else {
				marks = around(field, player.getX(), player.getZ());
				if (marks == null) {
					continue; // the map is still on its way
				}
			}
			List<Mark> last = SENT.get(player.getUUID());
			if (marks.equals(last) || last == null && marks.isEmpty()) {
				continue;
			}
			SENT.put(player.getUUID(), marks);
			ServerPlayNetworking.send(player, new Payload(marks));
		}
	}

	static void clear() {
		SENT.clear();
	}

	/** The marks round a block position, by kind and name; null while the towns' tiles are loading. */
	public static List<Mark> around(TerrainField field, double blockX, double blockZ) {
		OsmWater map = field.map();
		double lat = field.projection().latitudeAt(blockZ);
		double lon = field.projection().longitudeAt(blockX, lat);
		if (map == null || Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 85) {
			return List.of();
		}
		double sx = Mercator.sampleX(lon);
		double sy = Mercator.sampleY(lat);
		double spacing = Mercator.sampleSpacingMeters(lat);
		List<OsmWater.Place> places = Places.around(map, Places.TOWN_ZOOM, sx, sy);
		if (places == null) {
			return null;
		}
		record Near(Mark mark, double meters) {
		}
		// Neighbouring tiles repeat the places near their edges: each name once, the nearest.
		Map<String, Near> towns = new HashMap<>();
		for (OsmWater.Place p : places) {
			byte kind;
			double reach;
			switch (p.cls()) {
				case "city" -> {
					kind = p.capital() == 2 ? CAPITAL : CITY;
					reach = 40000;
				}
				case "town" -> {
					kind = TOWN;
					reach = 15000;
				}
				case "village" -> {
					kind = VILLAGE;
					reach = 4000;
				}
				default -> {
					continue;
				}
			}
			double d = Math.hypot(p.x() - sx, p.y() - sy) * spacing;
			Near old = p.name() == null ? null : towns.get(p.name());
			if (d < reach && p.name() != null && !p.name().isEmpty() && (old == null || d < old.meters())) {
				towns.put(p.name(), new Near(mark(field, kind, p.name(), p.x(), p.y(), blockX), d));
			}
		}
		// Stations from the full-detail tiles: the player's and the eight round it.
		double per = Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		int tx = (int) Math.floor(sx / per);
		int ty = (int) Math.floor(sy / per);
		Map<String, Near> stations = new HashMap<>();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				CompletableFuture<OsmWater.Shapes> f = map.tile(tx + dx, ty + dy);
				if (!f.isDone()) {
					continue; // stations there come when the tile does
				}
				for (OsmWater.Poi poi : f.getNow(OsmWater.Shapes.UNKNOWN).pois()) {
					if (!"railway".equals(poi.cls()) || !("station".equals(poi.subclass()) || "halt".equals(poi.subclass()))
						|| poi.name() == null || poi.name().isEmpty()) {
						continue;
					}
					double d = Math.hypot(poi.x() - sx, poi.y() - sy) * spacing;
					Near old = stations.get(poi.name());
					if (d < STATION_REACH && (old == null || d < old.meters())) {
						// A station is often several points (platforms, entrances): the nearest, once.
						stations.put(poi.name(), new Near(mark(field, STATION, poi.name(), poi.x(), poi.y(), blockX), d));
					}
				}
			}
		}
		// The nearest of each, then by name: the list stays the same, and is not sent again, while the player walks.
		List<Mark> out = new ArrayList<>();
		towns.values().stream().sorted(Comparator.comparingDouble(Near::meters)).limit(MAX_TOWNS).forEach(n -> out.add(n.mark()));
		stations.values().stream().sorted(Comparator.comparingDouble(Near::meters)).limit(MAX_STATIONS).forEach(n -> out.add(n.mark()));
		out.sort(Comparator.comparingInt(Mark::kind).thenComparing(Mark::name));
		return List.copyOf(out);
	}

	private static Mark mark(TerrainField field, byte kind, String name, double sx, double sy, double nearX) {
		double[] b = field.projection().blockOfSample(sx, sy, nearX);
		return new Mark(kind, name, (int) Math.floor(b[0]), (int) Math.floor(b[1]));
	}
}
