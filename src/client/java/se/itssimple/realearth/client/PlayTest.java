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
 *
 * With -Drealearth.flight=seconds,blocksPerSecond,heading (degrees, 0 north, 90 east) the player then
 * flies straight on at that speed, 60 blocks over the start, with the performance log on every five
 * seconds; and the game quits at the end. For seeing how chunk loading keeps up with a fast flight.
 */
final class PlayTest {
	private static boolean done;
	private static int ticks;
	/** Ticks flown so far, -1 before the flight starts. */
	private static int flown = -1;
	private static double[] from;

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
		if (mc.player == null || server == null || RealEarth.terrain() == null || ++ticks < 40) {
			return;
		}
		if (done) {
			fly(mc, server);
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

	/** The flight, a step a tick, once the player has been at the start for ten seconds. */
	private static void fly(Minecraft mc, IntegratedServer server) {
		String flight = System.getProperty("realearth.flight", "");
		if (flight.isEmpty() || ticks < 240) {
			return;
		}
		String[] parts = flight.split(",");
		int seconds = Integer.parseInt(parts[0].trim());
		double speed = Double.parseDouble(parts[1].trim());
		double heading = Math.toRadians(Double.parseDouble(parts[2].trim()));
		if (flown < 0) {
			RealEarth.config().perfLogSeconds = 5;
			from = new double[] {mc.player.getX(), mc.player.getY() + 60, mc.player.getZ()};
			RealEarth.LOGGER.info("[flight] {} s at {} blocks/s, heading {}, from {}, {}, {}", seconds, speed, parts[2].trim(), (int) from[0], (int) from[1],
				(int) from[2]);
		}
		flown++;
		if (flown > seconds * 20) {
			if (flown == seconds * 20 + 1) {
				RealEarth.LOGGER.info("[flight] done");
				mc.stop();
			}
			return;
		}
		double step = speed / 20 * flown;
		double x = from[0] + Math.sin(heading) * step;
		double z = from[2] - Math.cos(heading) * step;
		server.execute(() -> {
			var player = server.getPlayerList().getPlayers().get(0);
			player.getAbilities().flying = true;
			player.teleportTo(x, from[1], z);
		});
	}
}
