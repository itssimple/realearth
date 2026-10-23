package se.itssimple.realearth.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Tells a player's client where on Earth they are, for maps and overlays on the client (Xaero's
 * minimap): latitude, longitude, real elevation, town and district. Sent about once a second, and
 * only when it has changed and the client has Real Earth to read it.
 */
public final class HereLink {
	/**
	 * Where a player is. Latitude NaN when they are not on the Earth; town and district empty when
	 * there is none, or while the map is still loading.
	 */
	public record Payload(double latitude, double longitude, double meters, String town, String district) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(RealEarth.id("here"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, Payload::latitude,
			ByteBufCodecs.DOUBLE, Payload::longitude,
			ByteBufCodecs.DOUBLE, Payload::meters,
			ByteBufCodecs.STRING_UTF8, Payload::town,
			ByteBufCodecs.STRING_UTF8, Payload::district,
			Payload::new);
		public static final Payload NOWHERE = new Payload(Double.NaN, Double.NaN, Double.NaN, "", "");

		@Override
		public Type<Payload> type() {
			return TYPE;
		}

		/** The same to the metre: nothing to send. */
		boolean same(Payload o) {
			return Math.round(latitude * 1e5) == Math.round(o.latitude * 1e5) && Math.round(longitude * 1e5) == Math.round(o.longitude * 1e5)
				&& Math.round(meters) == Math.round(o.meters) && town.equals(o.town) && district.equals(o.district)
				&& Double.isNaN(latitude) == Double.isNaN(o.latitude);
		}
	}

	private static final Map<UUID, Payload> SENT = new ConcurrentHashMap<>();

	private HereLink() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		// A player who comes back has a new client, which knows nothing yet.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.getPlayer().getUUID()));
	}

	/**
	 * Sends a player where they are, if it has changed. A null field means they have left the Earth; a
	 * null place, that the map's names for it are still loading (the last town is kept until they come).
	 */
	static void send(ServerPlayer player, TerrainField field, Places.Here here) {
		if (!ServerPlayNetworking.canSend(player, Payload.TYPE)) {
			return;
		}
		Payload last = SENT.get(player.getUUID());
		Payload now;
		if (field == null) {
			now = Payload.NOWHERE;
		} else {
			double lat = field.projection().latitudeAt(player.getZ());
			double lon = field.projection().longitudeAt(player.getX(), lat);
			String town = here == null ? (last == null ? "" : last.town()) : here.town() == null ? "" : here.town();
			String district = here == null ? (last == null ? "" : last.district()) : here.district() == null ? "" : here.district();
			now = new Payload(lat, lon, field.toElevation(player.getY(), lat), town, district);
		}
		if (last != null && last.same(now)) {
			return;
		}
		SENT.put(player.getUUID(), now);
		ServerPlayNetworking.send(player, now);
	}

	static void clear() {
		SENT.clear();
	}
}
