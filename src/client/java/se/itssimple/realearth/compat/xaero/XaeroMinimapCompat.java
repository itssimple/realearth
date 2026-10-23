package se.itssimple.realearth.compat.xaero;

import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.client.ClientHere;
import se.itssimple.realearth.world.HereLink;
import xaero.common.HudMod;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.info.InfoDisplay;
import xaero.hud.minimap.info.InfoDisplayManager;
import xaero.hud.minimap.info.widget.InfoDisplayCommonWidgetFactories;
import xaero.lib.common.config.option.value.io.serialization.BuiltInConfigValueIOCodecs;

/**
 * Xaero's Minimap: two lines under the minimap on the Earth, latitude, longitude and real elevation,
 * and the town and district. Each can be turned off, and moved, in the minimap's info display
 * settings like its own lines. And waypoints for the towns and stations round the player
 * ({@link XaeroWaypoints}). Only loaded when the minimap is.
 */
public final class XaeroMinimapCompat {
	private static boolean added;

	private XaeroMinimapCompat() {
	}

	public static void install() {
		XaeroWaypoints.install();
		// The minimap sets itself up in its own initialiser, which may run after ours: add the lines once it has.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (added) {
				return;
			}
			HudMod hud = HudMod.INSTANCE;
			Minimap minimap = hud == null ? null : hud.getMinimap();
			if (minimap == null || minimap.getInfoDisplays() == null) {
				return;
			}
			added = true;
			InfoDisplayManager manager = minimap.getInfoDisplays().getManager();
			manager.add(line("realearth_lat_lon", "gui.realearth.xaero.lat_lon", here ->
				// Short, to fit under the minimap: four decimals are about 10 m.
				String.format(Locale.ROOT, "%.4f°%s %.4f°%s, %d m", Math.abs(here.latitude()), here.latitude() >= 0 ? "N" : "S",
					Math.abs(here.longitude()), here.longitude() >= 0 ? "E" : "W", Math.round(here.meters()))));
			manager.add(line("realearth_town", "gui.realearth.xaero.town", here ->
				here.town().isEmpty() ? null : here.district().isEmpty() ? here.town() : here.district() + ", " + here.town()));
			// The minimap orders its lines once, from its settings; again now, so ours are among them.
			manager.applyLocalConfig();
			RealEarth.LOGGER.info("Added Real Earth's lines to Xaero's Minimap");
		});
	}

	/** An on/off line that shows text worked out from where the player is, when they are on the Earth. */
	private static InfoDisplay<Boolean> line(String id, String name, java.util.function.Function<HereLink.Payload, String> text) {
		return InfoDisplay.Builder.<Boolean>begin()
			.setId(id)
			.setName(Component.translatable(name))
			.setDefaultState(true)
			.setCodec(BuiltInConfigValueIOCodecs.BOOLEAN)
			.setWidgetFactory(InfoDisplayCommonWidgetFactories.OFF_ON)
			.setCompiler((display, compiler, session, size, playerPos) -> {
				HereLink.Payload here = ClientHere.now();
				if (!Boolean.TRUE.equals(display.getState()) || Double.isNaN(here.latitude())) {
					return;
				}
				String line = text.apply(here);
				if (line != null) {
					compiler.addLine(line);
				}
			})
			.build();
	}
}
