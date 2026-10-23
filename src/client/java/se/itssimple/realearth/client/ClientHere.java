package se.itssimple.realearth.client;

import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import se.itssimple.realearth.world.HereLink;

/** Where on Earth the server last said this player is, for what the client shows (Xaero's minimap). */
public final class ClientHere {
	private static volatile HereLink.Payload now = HereLink.Payload.NOWHERE;

	private ClientHere() {
	}

	static void install() {
		ClientPlayNetworking.registerGlobalReceiver(HereLink.Payload.TYPE, (payload, context) -> now = payload);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> now = HereLink.Payload.NOWHERE);
	}

	/** Where the player is; latitude NaN when they are not on the Earth (or the server has no Real Earth). */
	public static HereLink.Payload now() {
		return now;
	}

	/** 59.32930° N, 18.06860° E */
	public static String latLon(double lat, double lon) {
		return String.format(Locale.ROOT, "%.5f° %s, %.5f° %s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
	}
}
