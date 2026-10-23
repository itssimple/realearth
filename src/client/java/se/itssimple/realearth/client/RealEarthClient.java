package se.itssimple.realearth.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import se.itssimple.realearth.RealEarth;

public class RealEarthClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientHere.install();
		if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
			try {
				se.itssimple.realearth.compat.xaero.XaeroMinimapCompat.install();
			} catch (Throwable e) {
				RealEarth.LOGGER.warn("Could not add Real Earth's lines to Xaero's Minimap", e);
			}
		}
		if (Boolean.getBoolean("realearth.shots")) {
			ShotTour.install();
		}
		String playtest = System.getProperty("realearth.playtest", "");
		if (!playtest.isEmpty()) {
			PlayTest.install(playtest);
		}
	}
}
