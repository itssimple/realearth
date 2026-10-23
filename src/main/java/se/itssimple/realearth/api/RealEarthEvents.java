package se.itssimple.realearth.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Things that happen on the Earth that other mods can listen for. Fired on the server thread. */
public final class RealEarthEvents {
	/**
	 * A player has walked into a town, or joined the game in one. Checked about once a second, so a
	 * player passing through a village on a fast horse may skip it.
	 */
	public static final Event<TownEntered> TOWN_ENTERED = EventFactory.createArrayBacked(TownEntered.class,
		listeners -> (player, town, previous) -> {
			for (TownEntered l : listeners) {
				l.onTownEntered(player, town, previous);
			}
		});

	private RealEarthEvents() {
	}

	@FunctionalInterface
	public interface TownEntered {
		/**
		 * @param town     the town now, with its district
		 * @param previous the town the player was in before, or null when they came from open country or
		 *                 just arrived on the Earth
		 */
		void onTownEntered(ServerPlayer player, RealEarthApi.Place town, @Nullable RealEarthApi.Place previous);
	}
}
