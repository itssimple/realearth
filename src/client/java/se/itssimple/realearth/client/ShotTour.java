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

	/** Towns, streets, railways and bridges in Stockholm. */
	private static final List<Shot> TOWNS = List.of(
		new Shot("fagersjo", 59.2465, 18.0623, 25, 0, 30),
		new Shot("fagersjo-sky", 59.2480, 18.0640, 150, 0, 45),
		new Shot("city-street", 59.3150, 18.0720, 25, 90, 25),
		new Shot("inside", 59.3150, 18.0720, 0, 90, 10),
		new Shot("inside-stairs", 59.3293, 18.0686, 0, 0, -20),
		new Shot("inside-shop", 59.3293, 18.0686, 0, 0, 15),
		new Shot("inside-flat", 59.3293, 18.0686, 0, 0, 15),
		new Shot("inside-front", 59.3293, 18.0686, 0, 0, 5),
		new Shot("inside-tower", 59.3293, 18.0686, 0, 0, 10),
		// Landmarks with mapped roofs and parts: the City Hall across the water from Riddarholmen, and
		// Riddarholmskyrkan's spire from the west.
		new Shot("stadshuset", 59.3250, 18.0625, 35, 135, 8),
		new Shot("riddarholmskyrkan", 59.3247, 18.0610, 25, -90, 12),
		// Street life: Kungstradgarden's park, the market on Hotorget, boats along Strandvagen.
		new Shot("park", 59.3318, 18.0712, 14, 0, 30),
		new Shot("market", 59.3346, 18.0629, 8, 0, 35),
		new Shot("marina", 59.3324, 18.0860, 10, 0, 25),
		new Shot("city-sky", 59.3300, 18.0700, 300, 180, 50),
		new Shot("railway", 59.34583, 18.01816, 20, 0, 45),
		new Shot("road-bridge", 59.3223, 18.0299, 25, 180, 20),
		new Shot("suburb", 59.3380, 17.9400, 60, -90, 30),
		new Shot("paris-sky", 48.8610, 2.3420, 250, 0, 55),
		new Shot("new-york-sky", 40.7580, -73.9855, 250, 180, 50),
		new Shot("brooklyn-bridge", 40.7095, -74.0010, 30, -45, 15),
		new Shot("tokyo-station", 35.6812, 139.7671, 40, 180, 35),
		new Shot("cairo-sky", 30.0444, 31.2357, 200, 0, 45),
		new Shot("rio-sky", -22.9068, -43.1729, 200, 0, 45)
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
		case "towns" -> TOWNS;
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

	/** A room column on the first floor of the nearest building with two or more storeys, at eye height. */
	private static BlockPos findRoom(TerrainField field, int x, int z) {
		for (int r = 0; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					var t = field.chunkIfReady((x >> 4) + dx, (z >> 4) + dz);
					if (t == null) {
						continue;
					}
					for (int lx = 2; lx < 14; lx++) {
						for (int lz = 2; lz < 14; lz++) {
							var plan = t.buildingAt(lx, lz);
							if (plan == null || plan.floors < 2 || plan.edgeDistance(((x >> 4) + dx) * 16 + lx, ((z >> 4) + dz) * 16 + lz) < 3) {
								continue;
							}
							return new BlockPos(((x >> 4) + dx) * 16 + lx, plan.floorY + plan.storey + 2, ((z >> 4) + dz) * 16 + lz);
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * A place to stand inside a building near (x, z): on a stairwell's landing facing the flights
	 * ("stairs"), in a shop ("shop"), or in a living room on the first floor up ("flat"); with the
	 * yaw to look across it in look[0].
	 */
	private static BlockPos findInterior(ServerLevel level, TerrainField field, int x, int z, String what, float[] look) {
		java.util.Set<Long> seen = new java.util.HashSet<>();
		boolean tower = what.equals("tower");
		for (int r = 0; r <= (tower ? 16 : 6); r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue; // each ring once
					}
					se.itssimple.realearth.terrain.ChunkTerrain t;
					try {
						// Towers are few: the search goes further, loading the terrain on its way.
						t = tower ? field.chunkBlocking((x >> 4) + dx, (z >> 4) + dz, 30) : field.chunkIfReady((x >> 4) + dx, (z >> 4) + dz);
					} catch (Exception e) {
						t = null;
					}
					if (t == null) {
						continue;
					}
					for (var plan : t.plans) {
						if (!seen.add(plan.id)) {
							continue;
						}
						if (what.equals("tower")) {
							// From the street side, some way off and a little below its top, looking at the tallest tower.
							var best = plan.towers.stream().max(java.util.Comparator.comparingInt(se.itssimple.realearth.terrain.BuildingPlan.Tower::topY));
							if (best.isEmpty()) {
								continue;
							}
							var tw = best.get();
							int tx = (int) Math.round((tw.minX() + tw.maxX()) / 2);
							int tz = (int) Math.round((tw.minZ() + tw.maxZ()) / 2);
							int away = 30 + (int) Math.max(tw.maxX() - tw.minX(), tw.maxZ() - tw.minZ());
							look[0] = yaw(0, -1);
							RealEarth.LOGGER.info("[shots] tower of {} at {},{} up to Y {} (roof {}), cap {}", plan.id, tx, tz, tw.topY(), plan.roofBaseY(),
								tw.capRise());
							return new BlockPos(tx, Math.max(plan.roofBaseY(), tw.topY() - 12), tz + away);
						}
						var inside = plan.interior();
						if (what.equals("front")) {
							// Out in the street before a shop's door, looking at it and its sign.
							int[] fx = {0, 1, 0, -1};
							int[] fz = {-1, 0, 1, 0};
							for (var d : inside.doors) {
								var room = d.entrance() ? inside.roomAt(d.x() - fx[d.facing()], d.z() - fz[d.facing()], 0) : null;
								if (room != null && room.name != null && room.kind == se.itssimple.realearth.terrain.Interior.Kind.SHOP) {
									look[0] = yaw(-fx[d.facing()], -fz[d.facing()]);
									// As far out as the street is open, up to six blocks.
									int out = 1;
									while (out < 6) {
										BlockPos feet = new BlockPos(d.x() + fx[d.facing()] * (out + 1), plan.floorY + 1, d.z() + fz[d.facing()] * (out + 1));
										if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
											|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
											break;
										}
										out++;
									}
									if (out < 3) {
										continue;
									}
									RealEarth.LOGGER.info("[shots] front of {} at {},{} facing {}, from {} blocks out, yaw {}", room.name, d.x(), d.z(), d.facing(), out, look[0]);
									return new BlockPos(d.x() + fx[d.facing()] * out, plan.floorY + 1, d.z() + fz[d.facing()] * out);
								}
							}
							continue;
						}
						if (what.equals("stairs") && !inside.stairs.isEmpty()) {
							var st = inside.stairs.get(0);
							// On the landing's far corner, looking back along the shaft.
							int lx = st.ox() + st.rdx() * 0 + st.sdx() * 2;
							int lz = st.oz() + st.rdz() * 0 + st.sdz() * 2;
							look[0] = yaw(st.rdx() - st.sdx(), st.rdz() - st.sdz());
							return new BlockPos(lx, plan.floorY + 1, lz);
						}
						for (var room : inside.rooms) {
							boolean wanted = what.equals("shop") ? room.kind == se.itssimple.realearth.terrain.Interior.Kind.SHOP
								: room.kind == se.itssimple.realearth.terrain.Interior.Kind.LIVING && plan.floors > 1;
							if (!wanted || room.size() < 12) {
								continue;
							}
							var cells = inside.cellsOf(room);
							double cx = 0;
							double cz = 0;
							for (int[] c : cells) {
								cx += c[0];
								cz += c[1];
							}
							cx /= cells.size();
							cz /= cells.size();
							// From the cell farthest from the middle, looking at the middle.
							int[] far = cells.get(0);
							for (int[] c : cells) {
								if (Math.hypot(c[0] - cx, c[1] - cz) > Math.hypot(far[0] - cx, far[1] - cz)) {
									far = c;
								}
							}
							look[0] = yaw(cx - far[0], cz - far[1]);
							int floor = what.equals("shop") ? 0 : 1;
							return new BlockPos(far[0], plan.floorY + floor * plan.storey + 1, far[1]);
						}
					}
				}
			}
		}
		return null;
	}

	/** Minecraft's yaw for looking along (dx, dz): 0 south, 90 west. */
	private static float yaw(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
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
		// Inside shots look for a building of a kind round about: wider.
		int around = shot.name.startsWith("inside-") ? 6 : 2;
		for (int dz = -around; dz <= around; dz++) {
			for (int dx = -around; dx <= around; dx++) {
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
			float yaw = shot.yaw;
			if (shot.name.startsWith("inside-")) {
				// In a stairwell, a shop or a flat's living room nearby, looking across it.
				float[] look = new float[1];
				BlockPos room = findInterior(earth, field, x, z, shot.name.substring("inside-".length()), look);
				if (room != null) {
					camera = room;
					yaw = look[0];
				}
			} else if (shot.name.startsWith("inside")) {
				// Inside the nearest building with storeys above, on its first floor, at eye height.
				BlockPos room = findRoom(field, x, z);
				if (room != null) {
					camera = room;
				}
			} else if (shot.minDepth > 0) {
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 600, 0, false, false));
				camera = findOpenSpace(earth, x, z, shot.minDepth, shot.maxDepth);
			}
			player.teleportTo(earth, camera.getX() + 0.5, camera.getY(), camera.getZ() + 0.5, Set.of(), yaw, shot.pitch, true);
			RealEarth.LOGGER.info("[shots] {} at X {} Z {}, ground Y {} ({} m)", shot.name, x, z, ground,
				Math.round(field.toElevation(ground, field.projection().latitudeAt(z))));
			moving = false;
		}));
	}
}
