package se.itssimple.realearth.compat.xaero;

import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.world.MapWaypoints;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;

/**
 * The towns and stations round the player, from the server, as waypoints in Xaero's Minimap. They are
 * the minimap's "third party" waypoints, kept apart from the player's own and never saved: the server
 * sends new ones as the player moves. Only on the Earth's dimension; off with mapWaypoints in the config.
 */
final class XaeroWaypoints {
	private static final Identifier ORIGIN = RealEarth.id("map");

	/** The marks from the server, and whether they are in the minimap yet. */
	private static List<MapWaypoints.Mark> marks = List.of();
	private static boolean applied = true;
	/** Where the marks were put, to take them away again. */
	private static ThirdPartyWaypoints shown;
	private static ResourceKey<Level> shownIn;

	private XaeroWaypoints() {
	}

	static void install() {
		ClientPlayNetworking.registerGlobalReceiver(MapWaypoints.Payload.TYPE, (payload, context) -> {
			marks = payload.marks();
			applied = false;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			marks = List.of();
			shown = null;
			shownIn = null;
			applied = true;
		});
		ClientTickEvents.END_CLIENT_TICK.register(XaeroWaypoints::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		// A new dimension: the marks belong to the one they were sent in, and the server sends again.
		if (shownIn != null && shownIn != mc.level.dimension() && shown != null) {
			shown.clear();
			shown = null;
		}
		if (applied) {
			return;
		}
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession() instanceof MinimapSession s ? s : null;
		if (session == null || session.getWorldManager() == null) {
			return; // the minimap is still starting; next tick
		}
		MinimapWorldRootContainer root = session.getWorldManager().getAutoRootContainer();
		if (root == null) {
			return;
		}
		if (shown != null) {
			shown.clear();
		}
		String dir = session.getDimensionHelper().getDimensionDirectoryName(mc.level.dimension());
		MinimapWorldContainer container = root.addSubContainer(root.getPath().resolve(dir));
		ThirdPartyWaypoints waypoints = container.getThirdPartyWaypointManager().get(ORIGIN);
		if (!waypoints.hasEnabledStateGetter()) {
			waypoints.setEnabledStateGetter(() -> RealEarth.config().mapWaypoints);
		}
		waypoints.clear();
		int y = mc.player == null ? 64 : mc.player.getBlockY();
		for (MapWaypoints.Mark m : marks) {
			WaypointColor colour = switch (m.kind()) {
				case MapWaypoints.CAPITAL -> WaypointColor.GOLD;
				case MapWaypoints.CITY -> WaypointColor.YELLOW;
				case MapWaypoints.TOWN -> WaypointColor.WHITE;
				case MapWaypoints.STATION -> WaypointColor.AQUA;
				default -> WaypointColor.GRAY;
			};
			String initials = m.kind() == MapWaypoints.STATION ? "St" : m.name().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			// Without a height: the map does not know the ground there, and the minimap then ignores Y.
			waypoints.add(m.kind() + ":" + m.name(), new Waypoint(m.x(), y, m.z(), m.name(), initials, colour, WaypointPurpose.NORMAL, true, false));
		}
		shown = waypoints;
		shownIn = mc.level.dimension();
		applied = true;
	}
}
