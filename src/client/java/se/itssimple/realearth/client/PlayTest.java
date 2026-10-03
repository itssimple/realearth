package se.itssimple.realearth.client;

import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import se.itssimple.realearth.RealEarth;

/**
 * For play testing (./gradlew runPlaytest): once the world is open, makes the player an operator in
 * creative mode and sends them to the place given as -Drealearth.playtest=latitude,longitude.
 */
final class PlayTest {
	private static boolean done;
	private static int ticks;

	private PlayTest() {
	}

	static void install(String place) {
		String[] parts = place.split(",");
		double lat = Double.parseDouble(parts[0].trim());
		double lon = Double.parseDouble(parts[1].trim());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, lat, lon));
	}

	private static void tick(Minecraft mc, double lat, double lon) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (done || mc.player == null || server == null || RealEarth.terrain() == null || ++ticks < 40) {
			return;
		}
		done = true;
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			server.getPlayerList().op(player.nameAndId());
			var source = server.createCommandSourceStack();
			String name = player.getGameProfile().name();
			server.getCommands().performPrefixedCommand(source, "gamemode creative " + name);
			server.getCommands().performPrefixedCommand(source, "time set morning");
			server.getCommands().performPrefixedCommand(source,
				String.format(Locale.ROOT, "execute as %s run earth tp %.6f %.6f", name, lat, lon));
		});
	}
}
