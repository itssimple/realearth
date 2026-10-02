package se.itssimple.realearth.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Development aid, enabled with -Drealearth.shots=true (./gradlew runShots): once a singleplayer
 * world is open, flies a spectator to a list of real places, waits for the terrain to render, saves
 * a screenshot of each to screenshots/realearth-*.png, and quits. Needs no keyboard or mouse.
 */
final class ShotTour {
	/**
	 * A camera position. Either at a real place (lat/lon) or at block X/Z; either a height above the
	 * ground, or -- when minDepth > 0 -- inside the first open cave space found between minDepth and
	 * maxDepth below the ground nearby, with night vision.
	 */
	private record Shot(String name, double lat, double lon, double above, float yaw, float pitch, int blockX, int blockZ, int minDepth, int maxDepth) {
		Shot(String name, double lat, double lon, double above, float yaw, float pitch) {
			this(name, lat, lon, above, yaw, pitch, 0, 0, 0, 0);
		}

		static Shot atBlock(String name, int x, int z, double above, float yaw, float pitch) {
			return new Shot(name, Double.NaN, Double.NaN, above, yaw, pitch, x, z, 0, 0);
		}

		static Shot cave(String name, int x, int z, int minDepth, int maxDepth, float yaw) {
			return new Shot(name, Double.NaN, Double.NaN, 0, yaw, 5, x, z, minDepth, maxDepth);
		}
	}

	// Yaw: 0 looks south, 90 west, 180 north, -90 east.
	private static final List<Shot> PLACES = List.of(
		new Shot("stockholm", 59.3190, 18.0500, 70, -120, 18),
		new Shot("kebnekaise", 67.8800, 18.4500, 140, -150, 12),
		new Shot("geiranger", 62.1000, 7.1300, 120, 90, 14),
		new Shot("mont-blanc", 45.8800, 6.8900, 220, 170, 10)
	);

	/** Places around the world, from the ground and from high up, for checking the Mercator map. */
	private static final List<Shot> TOUR = List.of(
		new Shot("stockholm", 59.3190, 18.0500, 70, -120, 18),
		new Shot("stockholm-sky", 59.3600, 18.1200, 1500, 45, 40),
		new Shot("grand-canyon", 36.0617, -112.1077, 30, 180, 15),
		new Shot("grand-canyon-sky", 35.9900, -112.1200, 1200, 180, 35),
		new Shot("fuji", 35.4200, 138.8300, 150, 55, -5),
		new Shot("fuji-sky", 35.4700, 138.8500, 2200, 55, 15),
		new Shot("gotland", 57.6400, 18.2900, 40, 0, 12),
		new Shot("gotland-sky", 57.8500, 18.7500, 3000, 15, 35)
	);

	/** Structures found by the self-test in the Earth world type test world (its seed), relative to the start point. */
	private static final List<Shot> STRUCTURES = List.of(
		Shot.atBlock("village", -760, -520, 28, -30, 38),
		Shot.atBlock("outpost", 104, 104, 30, 150, 35),
		Shot.cave("cave", 0, 0, 15, 60, 90),
		Shot.cave("ancient-city", 72, 232, 60, 100, 0)
	);

	private static final List<Shot> SHOTS = (switch (System.getProperty("realearth.shotList", "")) {
		case "structures" -> STRUCTURES;
		case "tour" -> TOUR;
		default -> PLACES;
	}).stream()
		.filter(s -> System.getProperty("realearth.shotOnly", "").isEmpty()
			|| java.util.Arrays.asList(System.getProperty("realearth.shotOnly").split(",")).contains(s.name()))
		.toList();

	private static final int SETTLE_TICKS = Integer.getInteger("realearth.shotTicks", 600);

	private static int index = -1;
	private static int ticks;
	private static volatile boolean moving;

	private ShotTour() {
	}

	static void install() {
		ClientTickEvents.END_CLIENT_TICK.register(ShotTour::tick);
	}

	private static void tick(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (mc.player == null || server == null || RealEarth.terrain() == null || moving) {
			return;
		}
		ticks++;
		// The window may be unfocused; a pause menu would stop the integrated server.
		mc.options.pauseOnLostFocus = false;
		if (mc.screen != null) {
			mc.setScreen(null);
		}
		if (index < 0) {
			if (ticks > 60) {
				server.execute(() -> {
					var source = server.createCommandSourceStack();
					server.getCommands().performPrefixedCommand(source, "time set noon");
					server.getCommands().performPrefixedCommand(source, "weather clear 100000");
				});
				index = 0;
				go(mc, server);
			}
			return;
		}
		if (ticks % 200 == 0 && mc.level != null) {
			RealEarth.LOGGER.info("[shots] t={}s client chunks loaded: {}", ticks / 20, mc.level.getChunkSource().getLoadedChunksCount());
		}
		if (ticks == SETTLE_TICKS) {
			mc.options.hideGui = true;
		} else if (ticks == SETTLE_TICKS + 5) {
			Shot shot = SHOTS.get(index);
			Screenshot.grab(mc.gameDirectory, "realearth-" + shot.name + ".png", mc.getMainRenderTarget(), 1,
				message -> RealEarth.LOGGER.info("[shots] {}: {}", shot.name, message.getString()));
		} else if (ticks == SETTLE_TICKS + 25) {
			mc.options.hideGui = false;
			index++;
			if (index >= SHOTS.size()) {
				RealEarth.LOGGER.info("[shots] done");
				mc.stop();
			} else {
				go(mc, server);
			}
		}
	}

	/** The most open air pocket below the ground near (x, z): a cave to look around in. */
	private static BlockPos findOpenSpace(ServerLevel level, int x, int z, int minDepth, int maxDepth) {
		BlockPos best = new BlockPos(x, RealEarth.surfaceY(level, Heightmap.Types.OCEAN_FLOOR, x, z) - minDepth, z);
		int bestOpen = -1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -40; dx <= 40; dx += 4) {
			for (int dz = -40; dz <= 40; dz += 4) {
				int ground = RealEarth.surfaceY(level, Heightmap.Types.OCEAN_FLOOR, x + dx, z + dz);
				for (int y = ground - minDepth; y >= ground - maxDepth; y -= 2) {
					if (!level.getBlockState(p.set(x + dx, y, z + dz)).isAir()) {
						continue;
					}
					int open = 0;
					for (int ox = -4; ox <= 4; ox += 2) {
						for (int oy = -2; oy <= 2; oy += 2) {
							for (int oz = -4; oz <= 4; oz += 2) {
								if (level.getBlockState(p.set(x + dx + ox, y + oy, z + dz + oz)).isAir()) {
									open++;
								}
							}
						}
					}
					if (open > bestOpen) {
						bestOpen = open;
						best = new BlockPos(x + dx, y, z + dz);
					}
				}
			}
		}
		return best;
	}

	private static void go(Minecraft mc, IntegratedServer server) {
		Shot shot = SHOTS.get(index);
		TerrainField field = RealEarth.terrain();
		// Block shots are relative to the world's start point.
		int x = Double.isNaN(shot.lat) ? field.startX() + shot.blockX : (int) Math.floor(field.projection().blockX(shot.lat, shot.lon));
		int z = Double.isNaN(shot.lat) ? field.startZ() + shot.blockZ : (int) Math.floor(field.projection().blockZ(shot.lat));
		moving = true;
		ticks = 0;

		List<CompletableFuture<?>> pending = new ArrayList<>();
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				pending.add(field.chunk((x >> 4) + dx, (z >> 4) + dz));
			}
		}
		CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> server.execute(() -> {
			ServerLevel earth = RealEarth.earthLevel(server);
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			int ground = RealEarth.surfaceY(earth, Heightmap.Types.MOTION_BLOCKING, x, z);
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
			player.setGameMode(GameType.SPECTATOR);
			BlockPos camera = new BlockPos(x, (int) Math.min(ground + shot.above, earth.getMaxY() - 4), z);
			if (shot.minDepth > 0) {
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 600, 0, false, false));
				camera = findOpenSpace(earth, x, z, shot.minDepth, shot.maxDepth);
			}
			player.teleportTo(earth, camera.getX() + 0.5, camera.getY(), camera.getZ() + 0.5, Set.of(), shot.yaw, shot.pitch, true);
			RealEarth.LOGGER.info("[shots] {} at X {} Z {}, ground Y {} ({} m)", shot.name, x, z, ground,
				Math.round(field.toElevation(ground, field.projection().latitudeAt(z))));
			moving = false;
		}));
	}
}
