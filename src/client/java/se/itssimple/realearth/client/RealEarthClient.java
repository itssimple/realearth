package se.itssimple.realearth.client;

import net.fabricmc.api.ClientModInitializer;

public class RealEarthClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (Boolean.getBoolean("realearth.shots")) {
			ShotTour.install();
		}
		String playtest = System.getProperty("realearth.playtest", "");
		if (!playtest.isEmpty()) {
			PlayTest.install(playtest);
		}
	}
}
