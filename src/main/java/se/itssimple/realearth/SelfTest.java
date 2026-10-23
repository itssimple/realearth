package se.itssimple.realearth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.ChunkPos;
import se.itssimple.realearth.command.AreaDownload;
import se.itssimple.realearth.command.ChunkRepair;
import se.itssimple.realearth.data.DiskCache;
import se.itssimple.realearth.data.TileStore;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.world.DegradedChunks;
import se.itssimple.realearth.portal.PortalFrame;
import se.itssimple.realearth.portal.PortalTravel;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.TerrainField;
import se.itssimple.realearth.terrain.VerticalScale;

/**
 * Headless end-to-end check, run with ./gradlew runSelftest. Downloads real tiles, generates real
 * chunks, lights and walks through a portal, then stops the server. Every line is tagged [selftest];
 * the last one says PASS or FAIL.
 */
final class SelfTest {
	private static final List<String> FAILURES = new ArrayList<>();
	/** Where the building checks found containers with loot: chunk x, chunk z, how many; for the Lootr check. */
	private static volatile int[] lootrCheck;
	/** The chunk of central Stockholm with the most beds, for the townsfolk check. */
	private static volatile int[] homesAt;
	/** A chunk of Stockholm with a marina, dock or ferry terminal on the map, for the boats check. */
	private static volatile int[] harbourAt;

	private SelfTest() {
	}

	static void install() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> Thread.ofPlatform().daemon().name("RealEarth-SelfTest").start(() -> run(server)));
	}

	private static void log(String format, Object... args) {
		RealEarth.LOGGER.info("[selftest] " + String.format(Locale.ROOT, format, args));
	}

	private static void check(boolean ok, String format, Object... args) {
		String line = String.format(Locale.ROOT, format, args);
		log("%s %s", ok ? "ok  " : "FAIL", line);
		if (!ok) {
			FAILURES.add(line);
		}
	}

	private record Place(String name, double lat, double lon, double minM, double maxM, Boolean lake, boolean water) {
		Place(String name, double lat, double lon, double minM, double maxM, Boolean lake) {
			this(name, lat, lon, minM, maxM, lake, Boolean.TRUE.equals(lake));
		}
	}

	private static void run(MinecraftServer server) {
		try {
			TerrainField field = RealEarth.terrain();
			VerticalScale scale = field.scale();

			check(Math.abs(scale.toY(0) - VerticalScale.SEA_LEVEL_Y) < 1e-9, "sea level maps to Y %d", VerticalScale.SEA_LEVEL_Y);
			check(Math.abs(scale.toY(1000) - (VerticalScale.SEA_LEVEL_Y + 1000)) < 1e-9, "1000 m maps to sea level + 1000 blocks");
			check(scale.toY(8849) <= VerticalScale.MAX_Y && scale.toY(8849) > VerticalScale.MAX_Y - 20, "Everest fits under the build limit: Y %.1f", scale.toY(8849));
			check(scale.toY(-10994) >= VerticalScale.MIN_Y, "Challenger Deep fits above the floor: Y %.1f", scale.toY(-10994));
			check(Math.abs(scale.toElevation(scale.toY(4000)) - 4000) < 0.01, "vertical scale round-trips at 4000 m");

			double lat = 59.3293;
			double lon = 18.0686;
			double bx = field.projection().blockX(lat + 0.1, lon + 0.2);
			double bz = field.projection().blockZ(lat + 0.1);
			double backLat = field.projection().latitudeAt(bz);
			double backLon = field.projection().longitudeAt(bx, backLat);
			check(Math.abs(backLat - (lat + 0.1)) < 1e-9 && Math.abs(backLon - (lon + 0.2)) < 1e-9, "%s projection round-trips", field.projection().id());
			double perMeter = field.projection().blocksPerMeter(lat);
			double degree = field.projection().blockX(lat, lon + 1) - field.projection().blockX(lat, lon);
			check(Math.abs(degree - 111_195 * Math.cos(Math.toRadians(lat)) * perMeter) < 50,
				"one degree of longitude at %.2fN is %.0f blocks (%.3f blocks per metre)", lat, degree, perMeter);
			for (Projection probe : List.of(new Projection.Sinusoidal(59.3293, 18.0686), new Projection.WebMercator(59.3293))) {
				double px = probe.blockX(37.0, -112.0);
				double pz = probe.blockZ(37.0);
				double rLat = probe.latitudeAt(pz);
				double rLon = probe.longitudeAt(px, rLat);
				check(Math.abs(rLat - 37.0) < 1e-9 && Math.abs(rLon + 112.0) < 1e-9, "%s round-trips at the Grand Canyon", probe.id());
			}
			{
				Projection m = new Projection.WebMercator(59.3293);
				check(Math.abs(m.blocksPerMeter(59.3293) - 1.0) < 1e-12, "Mercator: one block is one metre at the scale latitude");
				// North is straight up: a meridian keeps its X at every latitude.
				check(Math.abs(m.blockX(36.0, -112.1) - m.blockX(37.0, -112.1)) < 1e-6 && m.shear(36.1, -112.1) == 0,
					"Mercator: no lean at the Grand Canyon");
				// Conformal: the same scale north-south as east-west, so shapes are true.
				double kGc = m.blocksPerMeter(36.1);
				double ns = (m.blockZ(36.09) - m.blockZ(36.11)) / (0.02 * Projection.METERS_PER_DEGREE);
				double ew = (m.blockX(36.1, -112.09) - m.blockX(36.1, -112.11)) / (0.02 * Projection.METERS_PER_DEGREE * Math.cos(Math.toRadians(36.1)));
				check(Math.abs(ns / kGc - 1) < 0.002 && Math.abs(ew / kGc - 1) < 0.002,
					"Mercator: true shapes at the Grand Canyon (%.3f blocks per metre north-south, %.3f east-west)", ns, ew);
				double wrapped = m.longitudeAt(m.blockX(10.0, 179.9) + 10000, 10.0);
				check(wrapped < -179.0, "Mercator: flying east past 180 degrees carries on at %.2f", wrapped);
			}

			List<Place> places = List.of(
				new Place("Stockholm (start)", 59.3293, 18.0686, -5, 60, null),
				new Place("Riddarfjarden (Malaren)", 59.3240, 18.0520, -15, 6, null, true),
				new Place("Saltsjon (Baltic)", 59.3205, 18.1100, -60, 0, false, true),
				new Place("Nacka strand (Baltic)", 59.3195, 18.1600, -60, 0, false, true),
				new Place("Lilla Vartan (Baltic)", 59.3500, 18.1200, -60, 0, false, true),
				new Place("Lake Siljan", 60.88, 14.75, 140, 165, true),
				new Place("Lake Vattern", 58.30, 14.50, 60, 90, true),
				new Place("Kebnekaise", 67.9000, 18.5300, 1700, 2120, false),
				new Place("Mont Blanc", 45.8326, 6.8652, 4300, 4830, false),
				new Place("Half Dome", 37.7459, -119.5332, 2400, 2700, false),
				new Place("Mid-Atlantic", 45.0, -30.0, -5000, -1000, false, true)
			);
			for (Place p : places) {
				long t0 = System.nanoTime();
				int x = (int) Math.floor(field.projection().blockX(p.lat, p.lon));
				int z = (int) Math.floor(field.projection().blockZ(p.lat));
				ChunkTerrain t = field.chunkBlocking(x >> 4, z >> 4, 120);
				int k = ChunkTerrain.index(x & 15, z & 15);
				double ground = t.groundMeters[k];
				double water = t.waterMeters[k];
				double measured = Boolean.TRUE.equals(p.lake) ? water : ground;
				boolean inRange = measured >= p.minM && measured <= p.maxM;
				check(inRange, "%-20s ground %7.1f m, water %s, top Y %d (%d ms)", p.name, ground,
					Double.isNaN(water) ? "none" : String.format(Locale.ROOT, "%.1f m%s", water, t.lake[k] ? " (lake)" : " (sea)"),
					t.topSolidY[k], (System.nanoTime() - t0) / 1_000_000);
				if (p.water) {
					check(!Float.isNaN(t.waterMeters[k]), "%-20s is under water", p.name);
				}
				if (p.lake != null) {
					check(t.lake[k] == p.lake, "%-20s lake detection says %s", p.name, t.lake[k]);
				}
			}

			// Real climate and land cover, and what they make of each place.
			record Real(String name, double lat, double lon, String climate, int cover, String biome, Boolean wet) {
			}
			List<Real> reals = List.of(
				new Real("Stockholm city", 59.3346, 18.0632, "Dfb", se.itssimple.realearth.landcover.WorldCover.BUILT, null, false),
				new Real("Norrstrom (city water)", 59.3326, 18.0649, "Dfb", -1, null, true),
				new Real("Sahara (Algeria)", 26.0, 3.0, "BWh", se.itssimple.realearth.landcover.WorldCover.BARE, "desert", false),
				new Real("Amazon", -4.0, -63.0, "Af", se.itssimple.realearth.landcover.WorldCover.TREES, "jungle", null),
				new Real("Haarlemmermeer polder", 52.30, 4.69, "Cfb", -1, null, false),
				new Real("Kebnekaise", 67.90, 18.53, "ET", -1, null, false),
				new Real("Beauce farmland", 48.25, 1.75, "Cfb", se.itssimple.realearth.landcover.WorldCover.CROPS, "plains", false)
			);
			for (Real p : reals) {
				int x = (int) Math.floor(field.projection().blockX(p.lat, p.lon));
				int z = (int) Math.floor(field.projection().blockZ(p.lat));
				ChunkTerrain t = field.chunkBlocking(x >> 4, z >> 4, 180);
				int k = ChunkTerrain.index(x & 15, z & 15);
				int climate = t.climate[k] & 0xFF;
				int cover = t.cover[k] & 0xFF;
				boolean wet = !Float.isNaN(t.waterMeters[k]) && t.waterTopY[k] != Integer.MIN_VALUE;
				String biome = se.itssimple.realearth.world.BiomePicker.pick(t.latitude[k], t.longitude[k], t.groundMeters[k], wet, t.lake[k],
					wet ? t.waterMeters[k] : 0, climate, cover).identifier().getPath();
				log("%-22s climate %s (%s), cover %s, biome %s, ground %.1f m, %s", p.name, se.itssimple.realearth.climate.Koppen.code(climate),
					se.itssimple.realearth.climate.Koppen.name(climate), se.itssimple.realearth.landcover.WorldCover.name(cover), biome,
					t.groundMeters[k], wet ? String.format(Locale.ROOT, "water at %.1f m", t.waterMeters[k]) : "dry");
				check(se.itssimple.realearth.climate.Koppen.code(climate).equals(p.climate), "%s climate is %s", p.name, p.climate);
				if (p.cover >= 0) {
					check(cover == p.cover, "%s land cover is %s", p.name, se.itssimple.realearth.landcover.WorldCover.name(p.cover));
				}
				if (p.biome != null) {
					check(biome.equals(p.biome), "%s biome is %s", p.name, p.biome);
				}
				if (p.wet != null) {
					check(wet == p.wet, "%s is %s", p.name, p.wet ? "under water" : "dry land");
				}
				// Known data limitation, logged rather than asserted: central Stockholm is a flat 26-27 m
				// patch in the elevation source, water and land alike, so its city water stands at 26 m.
			}

			// Distant terrain for LOD mods: a 16 km patch around Kebnekaise at 256 m per column.
			{
				int cx = (int) field.projection().blockX(67.90, 18.53);
				int cz = (int) field.projection().blockZ(67.90);
				long t0 = System.nanoTime();
				var cols = new se.itssimple.realearth.lod.LodSampler(field).patch(cx - 32 * 256, cz - 32 * 256, 64, 256);
				long ms = (System.nanoTime() - t0) / 1_000_000;
				java.util.Map<String, Integer> surfaces = new java.util.TreeMap<>();
				int trees = 0;
				int maxTop = Integer.MIN_VALUE;
				for (var c : cols) {
					surfaces.merge(c.surface().replace("minecraft:", ""), 1, Integer::sum);
					trees += c.canopy() != null ? 1 : 0;
					maxTop = Math.max(maxTop, c.groundTop());
				}
				log("LOD patch 64x64 x 256 m around Kebnekaise in %d ms: highest %.0f m, %d forested, surfaces %s", ms,
					field.toElevation(maxTop, 67.9), trees, surfaces);
				check(field.toElevation(maxTop, 67.9) > 1500 && surfaces.containsKey("snow_block") && trees > 0,
					"LOD patch has mountains, snow and forest");
			}

			// Distant terrain must meet real chunks without a step: compare at every LOD column size.
			{
				int gx = (int) field.projection().blockX(62.10, 7.13);
				int gz = (int) field.projection().blockZ(62.10);
				for (int size : new int[] {1, 2, 4, 8, 16, 32, 64}) {
					var cols = new se.itssimple.realearth.lod.LodSampler(field).patch(gx, gz, 16, size);
					int worst = 0;
					double sum = 0;
					for (int j = 0; j < 16; j++) {
						for (int i = 0; i < 16; i++) {
							int px = gx + i * size + size / 2;
							int pz = gz + j * size + size / 2;
							ChunkTerrain t = field.chunkBlocking(px >> 4, pz >> 4, 120);
							int real = t.topSolidY[ChunkTerrain.index(px & 15, pz & 15)];
							int diff = cols[j * 16 + i].groundTop() - real;
							sum += diff;
							worst = Math.max(worst, Math.abs(diff));
						}
					}
					log("LOD vs chunks at %3d m columns: mean %+.1f blocks, worst %d", size, sum / 256, worst);
					if (size <= 64) {
						check(Math.abs(sum / 256) < 1.5, "LOD %d m columns sit on the real ground (mean offset %+.1f)", size, sum / 256);
					}
				}
			}

			// Seams: chunks computed separately must agree on the columns they share.
			int seamMismatches = 0;
			int seamChecks = 0;
			for (int cx = -3; cx <= 3; cx++) {
				for (int cz = -3; cz <= 3; cz++) {
					ChunkTerrain a = field.chunkBlocking(cx, cz, 120);
					ChunkTerrain east = field.chunkBlocking(cx + 1, cz, 120);
					ChunkTerrain south = field.chunkBlocking(cx, cz + 1, 120);
					for (int i = 0; i < 16; i++) {
						seamChecks += 2;
						if (a.topSolidY[ChunkTerrain.index(16, i)] != east.topSolidY[ChunkTerrain.index(0, i)]) {
							seamMismatches++;
						}
						if (a.topSolidY[ChunkTerrain.index(i, 16)] != south.topSolidY[ChunkTerrain.index(i, 0)]) {
							seamMismatches++;
						}
					}
				}
			}
			check(seamMismatches == 0, "chunk seams agree: %d of %d shared columns differ", seamMismatches, seamChecks);

			// Smoothness: how often neighbouring columns differ by more than 1 block near the start.
			int steps = 0;
			int bigSteps = 0;
			for (int cx = -3; cx <= 3; cx++) {
				for (int cz = -3; cz <= 3; cz++) {
					ChunkTerrain c = field.chunkBlocking((field.startX() >> 4) + cx, (field.startZ() >> 4) + cz, 120);
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							steps++;
							if (c.slope(x, z) > 2) {
								bigSteps++;
							}
						}
					}
				}
			}
			log("terrain around the start: %.1f%% of columns have a neighbour more than 2 blocks away", 100.0 * bigSteps / steps);
			log("tiles: %s", field.store().stats());

			offlineAndCache(server, field);

			// The checks in the world, a server task each, the server running on between them.
			List<Map.Entry<String, Runnable>> phases = server.submit(() -> inWorld(server)).join();
			long longest = 0;
			String longestName = "";
			// -Drealearth.selftestOnly=Tunnels,API runs only the phases whose names hold one of the words.
			String only = System.getProperty("realearth.selftestOnly", "");
			if (!only.isBlank()) {
				List<String> words = java.util.Arrays.stream(only.split(",")).map(String::trim).filter(w -> !w.isEmpty()).toList();
				phases.removeIf(p -> words.stream().noneMatch(w -> p.getKey().contains(w)));
				log("running only the phases with %s: %s", words, phases.stream().map(Map.Entry::getKey).toList());
			}
			for (Map.Entry<String, Runnable> phase : phases) {
				long started = System.nanoTime();
				server.submit(() -> {
					try {
						phase.getValue().run();
					} catch (Throwable e) {
						RealEarth.LOGGER.error("[selftest] " + phase.getKey() + " crashed", e);
						FAILURES.add("crash in " + phase.getKey() + ": " + e);
					}
				}).join();
				long ms = (System.nanoTime() - started) / 1_000_000;
				if (ms > 1000) {
					log("  phase '%s' held the server %d ms", phase.getKey(), ms);
				}
				if (ms > longest) {
					longest = ms;
					longestName = phase.getKey();
				}
				Thread.sleep(1500);
			}
			log("%d phases in the world; the longest, %s, took %d ms in one server task", phases.size(), longestName, longest);
			soak(server);
			if (lootrCheck != null && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("lootr")) {
				// Lootr converts containers with a loot table on its own tick, once their chunk and the chunks
				// two around it are fully loaded, as they are round a player: hold them loaded, let the server
				// run, then look again.
				ServerLevel earth = RealEarth.earthLevel(server);
				int r = 4;
				server.submit(() -> forceArea(earth, lootrCheck[0], lootrCheck[1], r, true)).join();
				Thread.sleep(10_000);
				int converted = server.submit(() -> {
					int n = 0;
					for (int cx = lootrCheck[0] - 1; cx <= lootrCheck[0] + 1; cx++) {
						for (int cz = lootrCheck[1] - 1; cz <= lootrCheck[1] + 1; cz++) {
							for (var be : earth.getChunk(cx, cz).getBlockEntities().values()) {
								if (be.getClass().getName().startsWith("noobanidus.mods.lootr")) {
									n++;
								}
							}
						}
					}
					return n;
				}).join();
				server.submit(() -> forceArea(earth, lootrCheck[0], lootrCheck[1], r, false)).join();
				check(converted >= lootrCheck[2], "Lootr turned the containers with loot into its own (%d of %d)", converted, lootrCheck[2]);
			}
			if (harbourAt != null) {
				// Boats by the harbour, held loaded so their entities can be counted.
				ServerLevel earth = RealEarth.earthLevel(server);
				server.submit(() -> forceArea(earth, harbourAt[0], harbourAt[1], 1, true)).join();
				Thread.sleep(3_000);
				int boats = server.submit(() -> earth.getEntitiesOfClass(net.minecraft.world.entity.vehicle.boat.AbstractBoat.class,
					new net.minecraft.world.phys.AABB(harbourAt[0] * 16 - 16, VerticalScale.MIN_Y, harbourAt[1] * 16 - 16, harbourAt[0] * 16 + 32,
						VerticalScale.MAX_Y, harbourAt[1] * 16 + 32)).size()).join();
				server.submit(() -> forceArea(earth, harbourAt[0], harbourAt[1], 1, false)).join();
				log("boats by the harbour in chunk %d,%d: %d", harbourAt[0], harbourAt[1], boats);
				check(boats >= 1, "boats lie by the map's marinas, docks and ferry terminals (%d)", boats);
			}
			if (homesAt != null) {
				// Townsfolk: villagers come home to beds round a player, and go again once nobody is near.
				// Only where entities tick, as they do round a player: hold the town loaded.
				ServerLevel earth = RealEarth.earthLevel(server);
				server.submit(() -> forceArea(earth, homesAt[0], homesAt[1], 4, true)).join();
				Thread.sleep(3_000);
				int[] seen = server.submit(() -> {
					BlockPos centre = new BlockPos(homesAt[0] * 16 + 8, 0, homesAt[1] * 16 + 8);
					var box = new net.minecraft.world.phys.AABB(centre).inflate(56, 2000, 56);
					int before = earth.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, box).size();
					int came = 0;
					for (int i = 0; i < 40; i++) {
						if (se.itssimple.realearth.world.Townsfolk.comeHome(earth, field, centre, RealEarth.config().townsfolk, List.of()) != null) {
							came++;
						}
					}
					int after = earth.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, box).size();
					int gone = se.itssimple.realearth.world.Townsfolk.leave(earth, List.of());
					var homes = earth.getPoiManager().getInSquare(t -> t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), centre, 56,
						net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).map(net.minecraft.world.entity.ai.village.poi.PoiRecord::getPos).toList();
					long ticking = homes.stream().filter(earth::isPositionEntityTicking).count();
					long built = homes.stream().filter(b -> {
						ChunkTerrain t = field.chunkIfReady(b.getX() >> 4, b.getZ() >> 4);
						return t != null && t.building[ChunkTerrain.index(b.getX() & 15, b.getZ() & 15)] != 0;
					}).count();
					log("townsfolk: %d beds round the centre, %d in buildings, %d where entities tick; centre ticking %s", homes.size(), built, ticking,
						earth.isPositionEntityTicking(centre));
					return new int[] {before, came, after, gone};
				}).join();
				server.submit(() -> forceArea(earth, homesAt[0], homesAt[1], 4, false)).join();
				log("townsfolk round the centre of Stockholm: %d villagers there, %d came home (%d there then), %d went again", seen[0], seen[1], seen[2], seen[3]);
				int most = RealEarth.config().townsfolk;
				check(seen[1] >= 1 && seen[2] == seen[0] + seen[1] && seen[2] >= Math.min(12, most) && seen[2] <= Math.max(most, seen[0]) && seen[3] == seen[1],
					"a city centre fills with villagers, up to %d, and they go again (%d there, %d came, %d went)", most, seen[2], seen[1], seen[3]);
			}
		} catch (Throwable e) {
			RealEarth.LOGGER.error("[selftest] crashed", e);
			FAILURES.add("crash: " + e);
		}

		log(FAILURES.isEmpty() ? "PASS" : "FAIL (%d): %s", FAILURES.size(), FAILURES);
		server.execute(() -> server.halt(false));
	}

	/**
	 * A walk across the city as a player makes one: an area of chunks held loaded, moved on step by step,
	 * what it leaves unloading. The heap must level off (nothing kept of the chunks gone), and the server's
	 * ticks stay short while it generates.
	 */
	private static void soak(MinecraftServer server) throws Exception {
		ServerLevel earth = RealEarth.earthLevel(server);
		TerrainField field = RealEarth.terrain();
		int z = field.startZ() >> 4;
		int x0 = (int) Math.floor(field.projection().blockX(59.3293, 17.98)) >> 4;
		int radius = 4;
		int steps = 30;
		int stride = 6;
		Runtime rt = Runtime.getRuntime();
		List<String> heaps = new ArrayList<>();
		long early = 0;
		long late = 0;
		long slowest = 0;
		long sum = 0;
		long count = 0;
		int previous = Integer.MIN_VALUE;
		long started = System.nanoTime();
		for (int step = 0; step < steps; step++) {
			int x = x0 + step * stride;
			server.submit(() -> forceArea(earth, x, z, radius, true)).join();
			long until = System.currentTimeMillis() + 120_000;
			while (System.currentTimeMillis() < until && !server.submit(() -> {
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (earth.getChunkSource().getChunkNow(x + dx, z + dz) == null) {
							return false;
						}
					}
				}
				return true;
			}).join()) {
				Thread.sleep(200);
				for (long nanos : server.getTickTimesNanos()) {
					slowest = Math.max(slowest, nanos);
				}
			}
			if (previous != Integer.MIN_VALUE) {
				int p = previous;
				server.submit(() -> forceArea(earth, p, z, radius, false)).join();
			}
			previous = x;
			for (long nanos : server.getTickTimesNanos()) {
				if (nanos > 0) {
					slowest = Math.max(slowest, nanos);
					sum += nanos;
					count++;
				}
			}
			if (step % 5 == 4) {
				Thread.sleep(2_000); // let what was left unload
				System.gc();
				long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
				heaps.add(used + " MB");
				if (step == 9) {
					early = used;
				}
				late = used;
			}
		}
		int last = previous;
		server.submit(() -> forceArea(earth, last, z, radius, false)).join();
		log("soak: %d steps of %d x %d chunks across Stockholm in %d s; heap after each 5 steps %s; ticks: slowest %d ms, mean %.1f ms",
			steps, radius * 2 + 1, radius * 2 + 1, (System.nanoTime() - started) / 1_000_000_000, heaps, slowest / 1_000_000,
			count == 0 ? 0 : sum / (double) count / 1_000_000);
		check(late <= early * 1.25 + 512, "the heap levels off while new land is generated (%d MB after 10 steps, %d MB after %d)", early, late, steps);
	}

	private static void forceArea(ServerLevel level, int cx, int cz, int r, boolean forced) {
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				level.setChunkForced(x, z, forced);
			}
		}
	}

	/**
	 * Offline generation, repair, the cache limit and area downloads. Offline is simulated with a
	 * second tile store pointed at a port nothing listens on, so the real one stays online.
	 */
	private static void offlineAndCache(MinecraftServer server, TerrainField field) throws Exception {
		Path scratch = server.getServerDirectory().resolve("selftest-scratch");
		deleteTree(scratch);

		// Offline: elevation comes back as a flagged stand-in, and is not kept, so it is asked for again.
		TileStore offline = new TileStore("http://127.0.0.1:9/{z}/{x}/{y}.png", scratch.resolve("offline"), 2, 16);
		TerrainField offlineField = new TerrainField(offline, RealEarth.config(), field.projection(), field.scale(), null, null);
		long t0 = System.nanoTime();
		CompletableFuture<float[]> first = offline.tile(Mercator.ZOOM, 17_000, 9_000);
		float[] standIn = first.get(60, java.util.concurrent.TimeUnit.SECONDS);
		check(offline.isDegraded(standIn), "offline: a tile that cannot be downloaded is flagged as a stand-in (%d ms)", (System.nanoTime() - t0) / 1_000_000);
		Thread.sleep(100);
		check(offline.tile(Mercator.ZOOM, 17_000, 9_000) != first, "offline: a stand-in tile is not kept in memory");
		check(offline.offline(), "offline: downloads pause after repeated failures");
		ChunkTerrain degraded = offlineField.chunkBlocking(5, 5, 60);
		check(degraded.degraded, "offline: chunk terrain built from stand-ins is flagged");
		ChunkTerrain good = field.chunkBlocking(5, 5, 120);
		check(!good.degraded, "online: chunk terrain from real data is not flagged");
		offlineField.close();

		// Repair: a recorded chunk whose data is now complete is cleared from the list; one a player
		// has built in is left alone; one that is loaded waits.
		ServerLevel earth = RealEarth.earthLevel(server);
		String level = earth.dimension().identifier().toString();
		// A chunk that is really saved: generate it, let it unload, and it must be gone from the save after repair.
		ChunkPos saved = new ChunkPos(9_000, 9_004);
		CompletableFuture<Void> generated = new CompletableFuture<>();
		server.execute(() -> {
			earth.getChunk(saved.x(), saved.z());
			generated.complete(null);
		});
		generated.get(180, java.util.concurrent.TimeUnit.SECONDS);
		long unloadBy = System.currentTimeMillis() + 120_000;
		boolean onDisk = false;
		while (System.currentTimeMillis() < unloadBy) {
			CompletableFuture<Boolean> gone = new CompletableFuture<>();
			server.execute(() -> gone.complete(earth.getChunkSource().chunkMap.getUpdatingChunkIfPresent(saved.pack()) == null
				&& !earth.areEntitiesLoaded(saved.pack())));
			if (gone.get(10, java.util.concurrent.TimeUnit.SECONDS)) {
				server.execute(() -> earth.getChunkSource().chunkMap.synchronize(true));
				onDisk = earth.getChunkSource().chunkMap.read(saved).get(30, java.util.concurrent.TimeUnit.SECONDS).isPresent();
				if (onDisk) {
					break;
				}
			}
			Thread.sleep(500);
		}
		check(onDisk, "repair: test chunk generated, unloaded and saved");
		DegradedChunks.record(level, saved);

		ChunkPos free = new ChunkPos(9_000, 9_000);
		ChunkPos built = new ChunkPos(9_002, 9_000);
		CompletableFuture<ChunkPos> loadedPos = new CompletableFuture<>();
		server.execute(() -> {
			earth.setChunkForced(0, 0, true);
			loadedPos.complete(new ChunkPos(0, 0));
		});
		ChunkPos loaded = loadedPos.get(120, java.util.concurrent.TimeUnit.SECONDS);
		DegradedChunks.record(level, free);
		DegradedChunks.record(level, built);
		DegradedChunks.record(level, loaded);
		DegradedChunks.touch(level, built.x(), built.z());
		check(DegradedChunks.isTouched(level, built.pack()) && !DegradedChunks.isTouched(level, free.pack()), "repair: player changes are tracked per chunk");
		CompletableFuture<Boolean> started = new CompletableFuture<>();
		server.execute(() -> started.complete(ChunkRepair.start(server.createCommandSourceStack(), false)));
		check(started.get(10, java.util.concurrent.TimeUnit.SECONDS), "repair: starts");
		long until = System.currentTimeMillis() + 180_000;
		while (ChunkRepair.isRunning() && System.currentTimeMillis() < until) {
			Thread.sleep(200);
		}
		List<Long> left = new ArrayList<>();
		for (long k : DegradedChunks.degraded(level)) {
			left.add(k);
		}
		check(!left.contains(free.pack()), "repair: an untouched, unloaded chunk is cleared for regeneration");
		earth.getChunkSource().chunkMap.synchronize(true).get(30, java.util.concurrent.TimeUnit.SECONDS);
		check(!left.contains(saved.pack()) && earth.getChunkSource().chunkMap.read(saved).get(30, java.util.concurrent.TimeUnit.SECONDS).isEmpty(),
			"repair: the saved chunk is deleted from the save, to be generated afresh");
		check(left.contains(built.pack()), "repair: a chunk players built in is kept unless 'all' is given");
		check(left.contains(loaded.pack()), "repair: a loaded chunk waits until it is unloaded");
		server.execute(() -> earth.setChunkForced(0, 0, false));
		DegradedChunks.remove(level, built.pack());
		DegradedChunks.remove(level, loaded.pack());
		DegradedChunks.save();
		check(DegradedChunks.count() == 0, "repair: list empty after clean-up");

		// Cache limit: the files used longest ago go first, down to 90 % of the limit.
		Path cacheDir = scratch.resolve("cache");
		long now = System.currentTimeMillis();
		for (int i = 0; i < 10; i++) {
			Path f = cacheDir.resolve("terrarium").resolve("15").resolve("1").resolve(i + ".png");
			Files.createDirectories(f.getParent());
			Files.write(f, new byte[100_000]);
			Files.setLastModifiedTime(f, FileTime.fromMillis(now - (10 - i) * 86_400_000L));
		}
		DiskCache.unmanaged(cacheDir, 500_000).trimNow();
		long kept = 0;
		boolean newestKept = Files.exists(cacheDir.resolve("terrarium/15/1/9.png"));
		boolean oldestGone = !Files.exists(cacheDir.resolve("terrarium/15/1/0.png"));
		try (var files = Files.walk(cacheDir)) {
			kept = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		}
		check(kept <= 450_000 && newestKept && oldestGone, "cache limit: trimmed to %d bytes, oldest removed first", kept);

		// Area download: plans the tiles an area needs and puts them on disk.
		double lat = 64.1466;
		double lon = -21.9426;
		double x = field.projection().blockX(lat, lon);
		double z = field.projection().blockZ(lat);
		AreaDownload.Plan plan = AreaDownload.plan(field, x, z, 300);
		check(plan.elevationTiles() >= 9 && plan.elevationTiles() <= 36, "download: a 300 m radius needs %d elevation tiles", plan.elevationTiles());
		CompletableFuture<Boolean> dl = new CompletableFuture<>();
		server.execute(() -> dl.complete(AreaDownload.start(server.createCommandSourceStack(), () -> plan, "Reykjavik")));
		check(dl.get(10, java.util.concurrent.TimeUnit.SECONDS), "download: starts");
		Thread.sleep(200);
		until = System.currentTimeMillis() + 300_000;
		while (AreaDownload.isRunning() && System.currentTimeMillis() < until) {
			Thread.sleep(200);
		}
		int tx = (int) Math.floor(Mercator.sampleX(lon) / Mercator.TILE_SIZE);
		int ty = (int) Math.floor(Mercator.sampleY(lat) / Mercator.TILE_SIZE);
		Path tile = field.store().root().resolve("15").resolve(Integer.toString(tx)).resolve(ty + ".png");
		Path map = field.store().root().getParent().resolve("openfreemap").resolve("14").resolve(Integer.toString(tx >> 1)).resolve((ty >> 1) + ".pbf");
		check(Files.exists(tile) && Files.exists(map), "download: elevation and map tiles of the area are on disk");
		deleteTree(scratch);
	}

	/**
	 * Walks a building as a player would: from outside each street door, onto any block a step up or
	 * down (stairs are blocks too), through doors, up and down ladders, with two blocks of headroom.
	 * Returns {rooms reached, rooms} counting each room once per floor it is on.
	 */
	/**
	 * Rides the rails of an area as a minecart would: from every rail, both its ends must lead on to a
	 * rail that leads back -- level, one up where this one slopes, or one down where that one slopes up
	 * toward it. Returns {rails, broken ends, powered rails, unpowered powered rails, ends on a gap, ends at
	 * a rail that doesn't join}. Rails at the area's edge are left out.
	 */
	private static int[] rideRails(ServerLevel level, TerrainField field, int cx0, int cz0, int radius) {
		int rails = 0;
		int broken = 0;
		int powered = 0;
		int unpowered = 0;
		int gaps = 0;
		int mismatched = 0;
		int tunnels = 0;
		int lanterns = 0;
		int ends = 0;
		int x0 = (cx0 - radius) * 16;
		int z0 = (cz0 - radius) * 16;
		int x1 = (cx0 + radius + 1) * 16 - 1;
		int z1 = (cz0 + radius + 1) * 16 - 1;
		for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
			for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				ChunkTerrain t = field.chunkIfReady(cx, cz);
				if (t == null) {
					continue;
				}
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						int ground = t.topSolidY[ChunkTerrain.index(x, z)];
						int deck = t.deckY[ChunkTerrain.index(x, z)];
						int lo = Math.min(ground, deck == Integer.MIN_VALUE ? ground : deck) - 12;
						int hi = Math.max(ground, deck == Integer.MIN_VALUE ? ground : deck) + 8;
						if (t.tunnel[ChunkTerrain.index(x, z)] != 0) {
							tunnels++;
							int floor = t.tunnelY[ChunkTerrain.index(x, z)];
							lo = Math.min(lo, floor);
							hi = Math.max(hi, floor + 3);
							for (int y = floor + 1; y <= floor + se.itssimple.realearth.terrain.Tunnels.HEADROOM_BLOCKS; y++) {
								if (chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.LANTERN)) {
									lanterns++;
								}
							}
						}
						for (int y = lo; y <= hi; y++) {
							BlockPos at = new BlockPos(cx * 16 + x, y, cz * 16 + z);
							var state = chunk.getBlockState(at);
							if (!(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock rail)) {
								continue;
							}
							if (at.getX() <= x0 + 1 || at.getX() >= x1 - 1 || at.getZ() <= z0 + 1 || at.getZ() >= z1 - 1) {
								continue;
							}
							rails++;
							if (state.is(Blocks.POWERED_RAIL)) {
								powered++;
								if (!state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
									unpowered++;
								}
							}
							var shape = state.getValue(rail.getShapeProperty());
							for (int[] exit : exits(shape)) {
								int result = joins(level, at, exit);
								if (result != 0) {
									// Only a break where the track's own line runs on to the next rail: where the line ends
									// (a buffer stop) or meets another line (a branch, a crossing), rails can't join.
									int k = ChunkTerrain.index(x, z);
									int bit = exit[1] < 0 ? 1 : exit[0] > 0 ? 2 : exit[1] > 0 ? 4 : 8;
									int back = exit[1] < 0 ? 4 : exit[0] > 0 ? 8 : exit[1] > 0 ? 1 : 2;
									int nx = x + exit[0];
									int nz = z + exit[1];
									boolean inData = nx >= -1 && nx <= 16 && nz >= -1 && nz <= 16;
									int links = t.railLinks[k] | t.tunnelLinks[k];
									int theirs = inData ? t.railLinks[ChunkTerrain.index(nx, nz)] | t.tunnelLinks[ChunkTerrain.index(nx, nz)] : back;
									if ((links & bit) == 0 || (theirs & back) == 0 || Integer.bitCount(theirs) >= 3 || Integer.bitCount(links) >= 3) {
										ends++;
										continue;
									}
								}
								if (result != 0 && broken < 14) {
									StringBuilder around = new StringBuilder();
									for (int dy = 1; dy >= -2; dy--) {
										var n = level.getBlockState(at.offset(exit[0], dy, exit[1]));
										around.append(dy).append(':').append(n.getBlock().getDescriptionId().replace("block.minecraft.", ""))
											.append(n.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock r2 ? "/" + n.getValue(r2.getShapeProperty()) : "").append(' ');
									}
									int k = ChunkTerrain.index(x, z);
									int nk = ChunkTerrain.index(Math.max(-1, Math.min(16, x + exit[0])), Math.max(-1, Math.min(16, z + exit[1])));
									log("    broken rail at %s %s toward %d,%d (%s): way %d deck %s links %d/%d y %d/%d; next links %d/%d y %d/%d tunnel %d; next %s",
										at.toShortString(), shape, exit[0], exit[1], result == 1 ? "gap" : "not joining", t.way[k],
										t.deckY[k] == Integer.MIN_VALUE ? "-" : Integer.toString(t.deckY[k]), t.railLinks[k], t.tunnelLinks[k], t.railY[k], t.tunnelRailY[k],
										t.railLinks[nk], t.tunnelLinks[nk], t.railY[nk], t.tunnelRailY[nk], t.tunnel[nk], around);
								}
								if (result != 0) {
									broken++;
									if (result == 1) {
										gaps++;
									} else {
										mismatched++;
									}
								}
							}
						}
					}
				}
			}
		}
		return new int[] {rails, broken, powered, unpowered, gaps, mismatched, tunnels, lanterns, ends};
	}

	/** A rail shape's two ends: {dx, dz, dy}, dy 1 where it slopes up that way. */
	private static int[][] exits(net.minecraft.world.level.block.state.properties.RailShape shape) {
		return switch (shape) {
			case NORTH_SOUTH -> new int[][] {{0, -1, 0}, {0, 1, 0}};
			case EAST_WEST -> new int[][] {{1, 0, 0}, {-1, 0, 0}};
			case ASCENDING_EAST -> new int[][] {{1, 0, 1}, {-1, 0, 0}};
			case ASCENDING_WEST -> new int[][] {{-1, 0, 1}, {1, 0, 0}};
			case ASCENDING_NORTH -> new int[][] {{0, -1, 1}, {0, 1, 0}};
			case ASCENDING_SOUTH -> new int[][] {{0, 1, 1}, {0, -1, 0}};
			case SOUTH_EAST -> new int[][] {{0, 1, 0}, {1, 0, 0}};
			case SOUTH_WEST -> new int[][] {{0, 1, 0}, {-1, 0, 0}};
			case NORTH_WEST -> new int[][] {{0, -1, 0}, {-1, 0, 0}};
			case NORTH_EAST -> new int[][] {{0, -1, 0}, {1, 0, 0}};
		};
	}

	/** 0 when the end at {@code exit} of the rail at {@code at} joins a rail that leads back; 1 on a gap; 2 on a rail that doesn't. */
	private static int joins(ServerLevel level, BlockPos at, int[] exit) {
		boolean any = false;
		for (int dy : exit[2] == 1 ? new int[] {1} : new int[] {0, -1}) {
			BlockPos n = at.offset(exit[0], dy, exit[1]);
			var state = level.getBlockState(n);
			if (!(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock rail)) {
				continue;
			}
			any = true;
			for (int[] back : exits(state.getValue(rail.getShapeProperty()))) {
				if (back[0] == -exit[0] && back[1] == -exit[1] && (dy == -1 ? back[2] == 1 : back[2] == 0 || dy == 1)) {
					return 0;
				}
			}
		}
		return any ? 2 : 1;
	}

	private static int[] walkRooms(ServerLevel level, se.itssimple.realearth.terrain.BuildingPlan plan) {
		var inside = plan.interior();
		int x0 = (int) Math.floor(plan.minX) - 3;
		int z0 = (int) Math.floor(plan.minZ) - 3;
		int x1 = (int) Math.ceil(plan.maxX) + 3;
		int z1 = (int) Math.ceil(plan.maxZ) + 3;
		int y0 = plan.floorY;
		int y1 = plan.roofBaseY();
		java.util.Set<Long> visited = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		for (var d : inside.doors) {
			if (d.entrance()) {
				// One block out from the door, the way it faces (0 north, 1 east, 2 south, 3 west).
				int[] dx = {0, 1, 0, -1};
				int[] dz = {-1, 0, 1, 0};
				BlockPos out = new BlockPos(d.x() + dx[d.facing()], y0 + 1, d.z() + dz[d.facing()]);
				if (visited.add(out.asLong())) {
					queue.add(out);
				}
			}
		}
		java.util.Set<Long> rooms = new java.util.HashSet<>();
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			int floor = (p.getY() - 1 - y0) / plan.storey;
			if ((p.getY() - 1 - y0) % plan.storey == 0 && floor >= 0 && floor < plan.floors) {
				var room = inside.roomAt(p.getX(), p.getZ(), floor);
				if (room != null && room.kind != se.itssimple.realearth.terrain.Interior.Kind.LANDING) {
					rooms.add((long) room.id << 8 | floor);
				}
			}
			boolean onLadder = level.getBlockState(p).is(Blocks.LADDER);
			for (Direction d : Direction.values()) {
				for (int dy = -1; dy <= 1; dy++) {
					if (d.getAxis() == Direction.Axis.Y && dy != 0) {
						continue;
					}
					BlockPos n = p.relative(d).above(dy);
					if (d.getAxis() == Direction.Axis.Y && !(onLadder || level.getBlockState(n).is(Blocks.LADDER))) {
						continue;
					}
					if (n.getX() < x0 || n.getX() > x1 || n.getZ() < z0 || n.getZ() > z1 || n.getY() <= y0 || n.getY() > y1 + 1 || visited.contains(n.asLong())) {
						continue;
					}
					boolean ladder = level.getBlockState(n).is(Blocks.LADDER);
					if (!passable(level, n) || !passable(level, n.above()) || !ladder && !standable(level, n.below())) {
						continue;
					}
					// Stepping up needs headroom over where you start.
					if (dy == 1 && !passable(level, p.above(2))) {
						continue;
					}
					visited.add(n.asLong());
					queue.add(n);
				}
			}
		}
		int total = 0;
		boolean dumped = false;
		for (var room : inside.rooms) {
			if (room.kind == se.itssimple.realearth.terrain.Interior.Kind.LANDING) {
				continue;
			}
			for (int f = 0; f < plan.floors; f++) {
				boolean on = false;
				// A room is on a floor when one of its cells is that room on that floor.
				for (int x = (int) Math.floor(plan.minX); x <= (int) Math.ceil(plan.maxX) && !on; x++) {
					for (int z = (int) Math.floor(plan.minZ); z <= (int) Math.ceil(plan.maxZ) && !on; z++) {
						var r = inside.roomAt(x, z, f);
						on = r != null && r.id == room.id;
					}
				}
				if (on) {
					total++;
					if (!rooms.contains((long) room.id << 8 | f) && total - rooms.size() <= 3) {
						log("    not reached: %s %d on floor %d; the floor:%n%s", room.kind, room.id, f, inside.sketch(f));
						if (!dumped) {
							dumped = true;
							// What else stands there: structures reaching into the building's chunks.
							var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
							java.util.Set<String> near = new java.util.TreeSet<>();
							for (int cx = (int) Math.floor(plan.minX) >> 4; cx <= (int) Math.ceil(plan.maxX) >> 4; cx++) {
								for (int cz = (int) Math.floor(plan.minZ) >> 4; cz <= (int) Math.ceil(plan.maxZ) >> 4; cz++) {
									for (var s : level.getChunk(cx, cz).getAllReferences().entrySet()) {
										if (!s.getValue().isEmpty()) {
											near.add(registry.getKey(s.getKey()) + " in " + cx + "," + cz);
										}
									}
								}
							}
							log("    structures reaching the building: %s", near);
							for (int dy = 1; dy <= 2; dy++) {
								int y = y0 + f * plan.storey + dy;
								StringBuilder built = new StringBuilder();
								for (int z = inside.z0; z < inside.z0 + inside.h; z++) {
									for (int x = inside.x0; x < inside.x0 + inside.w; x++) {
										BlockState b = level.getBlockState(new BlockPos(x, y, z));
										built.append(b.isAir() ? '.' : b.getBlock() instanceof net.minecraft.world.level.block.DoorBlock ? 'D'
											: b.getBlock() instanceof net.minecraft.world.level.block.StairBlock ? 'S' : '#');
									}
									built.append('\n');
								}
								log("    built on floor %d at Y %d:%n%s", f, y, built);
							}
							int feet = y0 + f * plan.storey + 1;
							StringBuilder walked = new StringBuilder();
							for (int z = inside.z0; z < inside.z0 + inside.h; z++) {
								for (int x = inside.x0; x < inside.x0 + inside.w; x++) {
									walked.append(visited.contains(new BlockPos(x, feet, z).asLong()) ? 'o' : '.');
								}
								walked.append('\n');
							}
							log("    walked on floor %d at Y %d:%n%s", f, feet, walked);
						}
					}
				}
			}
		}
		if (rooms.isEmpty()) {
			// The building as built, a block above its ground floor, beside its plan.
			StringBuilder built = new StringBuilder();
			for (int z = inside.z0; z < inside.z0 + inside.h; z++) {
				for (int x = inside.x0; x < inside.x0 + inside.w; x++) {
					BlockState b = level.getBlockState(new BlockPos(x, y0 + 1, z));
					built.append(b.isAir() ? '.' : b.getBlock() instanceof net.minecraft.world.level.block.DoorBlock ? 'D' : '#');
				}
				built.append('\n');
			}
			log("    built at Y %d (floor Y %d, %d floors of %d):%n%s", y0 + 1, plan.floorY, plan.floors, plan.storey, built);
			// What stands round the first street door: layers from the floor up, north at the top.
			for (var d : inside.doors) {
				if (!d.entrance()) {
					continue;
				}
				log("    street door at %d,%d facing %d; around it from Y %d:", d.x(), d.z(), d.facing(), y0);
				for (int y = y0; y <= y0 + 2; y++) {
					StringBuilder rows = new StringBuilder();
					for (int dz = -2; dz <= 2; dz++) {
						for (int dx = -2; dx <= 2; dx++) {
							BlockState b = level.getBlockState(new BlockPos(d.x() + dx, y, d.z() + dz));
							rows.append(b.isAir() ? '.' : b.getBlock() instanceof net.minecraft.world.level.block.DoorBlock ? 'D' : '#');
						}
						rows.append(' ');
					}
					log("      Y %d: %s", y, rows);
				}
				break;
			}
		}
		if (rooms.size() < total && !inside.stairs.isEmpty()) {
			// What the first stairwell is made of, a layer at a time from the ground floor up: r across,
			// s down; '.' air, '#' block, '^' '>' 'v' '<' stairs climbing that way, 'D' door, 'w' wall.
			var st = inside.stairs.get(0);
			log("    stairwell at %d,%d (r %d,%d; s %d,%d; flights %d and %d):", st.ox(), st.oz(), st.rdx(), st.rdz(), st.sdx(), st.sdz(), st.a(), st.b());
			for (int y = y0; y <= y0 + 2 * plan.storey + 1; y++) {
				StringBuilder row = new StringBuilder();
				for (int s = -1; s <= 3; s++) {
					for (int r = -1; r <= st.a() + 2; r++) {
						BlockPos at = new BlockPos(st.ox() + r * st.rdx() + s * st.sdx(), y, st.oz() + r * st.rdz() + s * st.sdz());
						BlockState b = level.getBlockState(at);
						char ch = b.isAir() ? '.' : b.getBlock() instanceof net.minecraft.world.level.block.StairBlock
							? "^>v<".charAt(b.getValue(net.minecraft.world.level.block.StairBlock.FACING).get2DDataValue() == 2 ? 0
								: b.getValue(net.minecraft.world.level.block.StairBlock.FACING).get2DDataValue() == 3 ? 1
								: b.getValue(net.minecraft.world.level.block.StairBlock.FACING).get2DDataValue() == 0 ? 2 : 3)
							: b.getBlock() instanceof net.minecraft.world.level.block.DoorBlock ? 'D' : '#';
						row.append(ch);
					}
					row.append(' ');
				}
				log("      Y %d: %s", y, row);
			}
		}
		return new int[] {Math.min(rooms.size(), total), total};
	}

	private static boolean passable(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		Block b = s.getBlock();
		return s.getCollisionShape(level, p).isEmpty() || b instanceof net.minecraft.world.level.block.DoorBlock
			|| b instanceof net.minecraft.world.level.block.CarpetBlock || b == Blocks.LADDER;
	}

	private static boolean standable(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return !s.getCollisionShape(level, p).isEmpty() && !(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) || s.is(Blocks.LADDER)
			|| s.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock;
	}

	/**
	 * Walks a way's centreline (sample coordinates), carried on {@code extend} blocks past each end, and
	 * counts the columns where the surface -- the deck where there is one, else the road -- is more than a
	 * block above or below the column before. Columns off any road aren't walked. Returns {columns, steps, x, worst step, z} with x, z the
	 * worst step's block, or Integer.MIN_VALUE when there is none.
	 */
	private static int[] walkSteps(TerrainField field, double[] p, int extend) {
		int count = p.length / 2;
		double[] bx = new double[count];
		double[] bz = new double[count];
		for (int i = 0; i < count; i++) {
			double lat = Mercator.latitudeOfSampleY(p[2 * i + 1]);
			double lon = Projection.wrapLongitude((p[2 * i] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
			bx[i] = field.projection().blockX(lat, lon);
			bz[i] = field.projection().blockZ(lat);
		}
		List<double[]> path = new ArrayList<>();
		double[] head = {bx[0] - bx[1], bz[0] - bz[1]};
		double[] tail = {bx[count - 1] - bx[count - 2], bz[count - 1] - bz[count - 2]};
		double hl = Math.max(1e-9, Math.hypot(head[0], head[1]));
		double tl = Math.max(1e-9, Math.hypot(tail[0], tail[1]));
		path.add(new double[] {bx[0] + head[0] / hl * extend, bz[0] + head[1] / hl * extend});
		for (int i = 0; i < count; i++) {
			path.add(new double[] {bx[i], bz[i]});
		}
		path.add(new double[] {bx[count - 1] + tail[0] / tl * extend, bz[count - 1] + tail[1] / tl * extend});
		int[] out = {0, 0, Integer.MIN_VALUE, 0, 0};
		int lastX = Integer.MIN_VALUE;
		int lastZ = Integer.MIN_VALUE;
		int lastY = Integer.MIN_VALUE;
		String lastHere = "";
		for (int i = 0; i + 1 < path.size(); i++) {
			double[] a = path.get(i);
			double[] b = path.get(i + 1);
			int n = (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) * 2) + 1;
			for (int s = 0; s <= n; s++) {
				int x = (int) Math.floor(a[0] + (b[0] - a[0]) * s / n);
				int z = (int) Math.floor(a[1] + (b[1] - a[1]) * s / n);
				if (x == lastX && z == lastZ) {
					continue;
				}
				ChunkTerrain c;
				try {
					c = field.chunkBlocking(x >> 4, z >> 4, 120);
				} catch (Exception e) {
					continue;
				}
				int k = ChunkTerrain.index(x & 15, z & 15);
				boolean onDeck = c.deckY[k] != Integer.MIN_VALUE && c.deckWay[k] != se.itssimple.realearth.osm.Ways.NONE;
				if (!onDeck && c.way[k] == se.itssimple.realearth.osm.Ways.NONE) {
					// Off the road (carried on straight past an end where the road turns): not walked.
					lastX = x;
					lastZ = z;
					lastY = Integer.MIN_VALUE;
					continue;
				}
				int y = onDeck ? c.deckY[k] : c.topSolidY[k];
				String here = String.format("%d,%d y %d (deck %s way %d ground %.1f m water top %s)", x, z, y,
					c.deckY[k] == Integer.MIN_VALUE ? "-" : c.deckY[k] + "/" + c.deckWay[k] + " " + String.format("%.1f m", c.deckMeters[k]), c.way[k],
					c.groundMeters[k], c.waterTopY[k] == Integer.MIN_VALUE ? "-" : String.valueOf(c.waterTopY[k]));
				out[0]++;
				if (lastY != Integer.MIN_VALUE && Math.abs(y - lastY) > 1) {
					out[1]++;
					log("  step %d: %s -> %s", y - lastY, lastHere, here);
					if (Math.abs(y - lastY) > out[3]) {
						out[2] = x;
						out[3] = Math.abs(y - lastY);
						out[4] = z;
					}
				}
				lastHere = here;
				lastX = x;
				lastZ = z;
				lastY = y;
			}
		}
		return out;
	}

	private static double length(double[] p) {
		double l = 0;
		for (int i = 0; i + 3 < p.length; i += 2) {
			l += Math.hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]);
		}
		return l;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/**
	 * The checks in the world, as phases: each is run as a server task of its own, with the server let
	 * run between them, so no one tick holds everything the checks load.
	 */
	private static List<Map.Entry<String, Runnable>> inWorld(MinecraftServer server) {
		List<Map.Entry<String, Runnable>> phases = new ArrayList<>();
		ServerLevel earth = RealEarth.earthLevel(server);
		check(earth != null, "Earth dimension is registered");
		if (earth == null) {
			return phases;
		}
		check(earth.getMinY() == VerticalScale.MIN_Y && earth.getMaxY() == VerticalScale.MAX_Y,
			"Earth spans Y %d to %d", earth.getMinY(), earth.getMaxY());

		// Everything below happens around the start point, which is where players are.
		int startCx = RealEarth.terrain().startX() >> 4;
		int startCz = RealEarth.terrain().startZ() >> 4;
		phases.add(Map.entry("chunks round the start", () -> {
			long t0 = System.nanoTime();
			int generated = 0;
			for (int cx = -2; cx <= 2; cx++) {
				for (int cz = -2; cz <= 2; cz++) {
					earth.getChunk(startCx + cx, startCz + cz);
					generated++;
				}
			}
			log("generated %d full chunks around the start in %d ms", generated, (System.nanoTime() - t0) / 1_000_000);
		}));

		phases.add(Map.entry("Throughput in a city", () -> {
			// Throughput in a city: Gothenburg, fresh, with its data downloaded first; then a breakdown.
			{
				TerrainField field = RealEarth.terrain();
				int bx = (int) field.projection().blockX(57.7060, 11.9680) >> 4;
				int bz = (int) field.projection().blockZ(57.7060) >> 4;
				List<CompletableFuture<ChunkTerrain>> warm = new ArrayList<>();
				for (int dx = -1; dx <= 11; dx++) {
					for (int dz = -1; dz <= 11; dz++) {
						warm.add(field.chunk(bx + dx, bz + dz));
					}
				}
				CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)).join();
				Perf.reset();
				long tc0 = System.nanoTime();
				for (int dx = 0; dx < 10; dx++) {
					for (int dz = 0; dz < 10; dz++) {
						earth.getChunk(bx + dx, bz + dz);
					}
				}
				long tc1 = System.nanoTime();
				int sections = 0;
				int blockEntities = 0;
				for (int dx = 0; dx < 10; dx++) {
					for (int dz = 0; dz < 10; dz++) {
						blockEntities += earth.getChunk(bx + dx, bz + dz).getBlockEntities().size();
						for (var section : earth.getChunk(bx + dx, bz + dz).getSections()) {
							if (!section.hasOnlyAir()) {
								sections++;
							}
						}
					}
				}
				log("perf city: 100 full chunks of central Gothenburg (terrain fetched before) in %d ms; %d non-empty sections, %d block entities; %s",
					(tc1 - tc0) / 1_000_000, sections, blockEntities, Perf.report());
				long villagers = Perf.total("villagers");
				check(villagers >= 2 && villagers <= 15, "a city gets a few villagers, not a crowd: %d in 100 chunks", villagers);
				check(blockEntities < 100 * 12, "a city's rooms keep block entities few: %d in 100 chunks", blockEntities);
			}
		}));

		phases.add(Map.entry("Throughput at a fresh place", () -> {
			// Throughput at a fresh place: terrain alone, then full chunks.
			{
				TerrainField field = RealEarth.terrain();
				int bx = (int) field.projection().blockX(62.10, 7.13) >> 4;
				int bz = (int) field.projection().blockZ(62.10) >> 4;
				field.chunk(bx, bz).join();
				Perf.reset();
				long ta = System.nanoTime();
				List<CompletableFuture<ChunkTerrain>> all = new ArrayList<>();
				for (int dx = 0; dx < 10; dx++) {
					for (int dz = 0; dz < 10; dz++) {
						all.add(field.chunk(bx + dx, bz + dz));
					}
				}
				CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();
				long tb = System.nanoTime();
				for (int dx = 0; dx < 10; dx++) {
					for (int dz = 0; dz < 10; dz++) {
						earth.getChunk(bx + dx, bz + dz);
					}
				}
				long tc = System.nanoTime();
				log("perf country breakdown: %s", Perf.report());
				log("perf: 100 chunk terrains in %d ms; 100 full chunks (sequential, server thread) in %d ms",
					(tb - ta) / 1_000_000, (tc - tb) / 1_000_000);
			}
		}));

		phases.add(Map.entry("Underground", () -> {
			// Underground: caves, ravines, and vanilla's ores at vanilla depths below the real ground.
			{
				int columns = 0;
				int air = 0;
				int lava = 0;
				java.util.Map<String, int[]> ores = new java.util.TreeMap<>();
				BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
				for (int cx = -3; cx <= 3; cx++) {
					for (int cz = -3; cz <= 3; cz++) {
						LevelChunk c = earth.getChunk(startCx + cx, startCz + cz);
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int top = c.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
								columns++;
								for (int depth = 1; depth <= 140; depth++) {
									BlockState s = c.getBlockState(p.set(x, top - depth, z));
									if (s.isAir()) {
										air++;
									} else if (s.is(Blocks.LAVA)) {
										lava++;
									} else if (s.is(net.minecraft.tags.BlockTags.COAL_ORES) || s.is(net.minecraft.tags.BlockTags.IRON_ORES)
										|| s.is(net.minecraft.tags.BlockTags.DIAMOND_ORES) || s.is(net.minecraft.tags.BlockTags.GOLD_ORES)
										|| s.is(net.minecraft.tags.BlockTags.REDSTONE_ORES) || s.is(net.minecraft.tags.BlockTags.COPPER_ORES)) {
										String name = s.getBlock().getDescriptionId().replace("block.minecraft.", "").replace("deepslate_", "");
										int[] stat = ores.computeIfAbsent(name, n -> new int[2]);
										stat[0]++;
										stat[1] += depth;
									}
								}
							}
						}
					}
				}
				double caveShare = 100.0 * air / (columns * 140.0);
				log("underground of %d columns, 1-140 m down: %.1f%% cave, %d lava", columns, caveShare, lava);
				ores.forEach((name, stat) -> log("  %-14s %5d blocks, mean depth %3d m", name, stat[0], stat[1] / stat[0]));
				check(caveShare > 1.0 && caveShare < 25.0, "caves exist underground (%.1f%% of the band)", caveShare);
				check(ores.containsKey("coal_ore") && ores.containsKey("iron_ore"), "vanilla coal and iron generate");
				int[] diamonds = ores.get("diamond_ore");
				check(diamonds != null && diamonds[1] / diamonds[0] > 90, "diamonds generate deep: %s", diamonds == null ? "none" : diamonds[1] / diamonds[0] + " m mean depth");
				int[] coal = ores.get("coal_ore");
				check(coal != null && coal[1] / coal[0] < 70, "coal sits shallow: %s", coal == null ? "none" : coal[1] / coal[0] + " m mean depth");
			}
		}));

		phases.add(Map.entry("Structures", () -> {
			// Structures: starts around the origin and out in the Atlantic, and where they sit against the ground.
			{
				var structures = earth.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
				TerrainField field = RealEarth.terrain();
				int atlanticX = (int) field.projection().blockX(45.0, -30.0) >> 4;
				int atlanticZ = (int) field.projection().blockZ(45.0) >> 4;
				for (int[] region : new int[][] {{startCx, startCz}, {atlanticX, atlanticZ}}) {
					java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
					long ts = System.nanoTime();
					for (int cx = region[0] - 32; cx < region[0] + 32; cx++) {
						for (int cz = region[1] - 32; cz < region[1] + 32; cz++) {
							var c = earth.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, true);
							for (var e : c.getAllStarts().entrySet()) {
								if (!e.getValue().isValid()) {
									continue;
								}
								String name = structures.getKey(e.getKey()).getPath();
								counts.merge(name, 1, Integer::sum);
								var box = e.getValue().getBoundingBox();
								ChunkTerrain t;
								try {
									t = field.chunkBlocking(cx, cz, 60);
								} catch (Exception ex) {
									continue;
								}
								int k = ChunkTerrain.index(8, 8);
								int ground = t.topSolidY[k];
								int water = t.waterTopY[k];
								if (counts.get(name) <= 2) {
									log("  %-18s chunk %6d,%6d  Y %d..%d, ground at Y %d", name, cx, cz, box.minY(), box.maxY(), ground);
								}
								if (name.startsWith("ancient_city") || name.startsWith("trial_chambers")) {
									check(box.maxY() < ground + 10, "%s is under the ground (top Y %d, ground Y %d)", name, box.maxY(), ground);
								}
								if (name.equals("monument")) {
									check(box.minY() < VerticalScale.SEA_LEVEL_Y && box.maxY() <= Math.max(water, ground) + 2,
										"monument sits under the sea (Y %d..%d, sea surface Y %d)", box.minY(), box.maxY(), water);
								}
								if (name.equals("mineshaft") || name.equals("stronghold")) {
									check(box.minY() >= VerticalScale.SEA_LEVEL_Y - 140, "%s within vanilla depth of sea level (Y %d)", name, box.minY());
								}
							}
						}
					}
					log("structure starts in 64 x 64 chunks at %d,%d (%d ms, world seed %d): %s", region[0], region[1], (System.nanoTime() - ts) / 1_000_000,
						earth.getSeed(), counts);
					check(!counts.isEmpty(), "structures generate");
					int villages = counts.entrySet().stream().filter(e -> e.getKey().startsWith("village")).mapToInt(java.util.Map.Entry::getValue).sum();
					if (region[0] == startCx) {
						// 64 x 64 chunks of central Stockholm is about 1 km square of city.
						// Central Stockholm's buildings are all mapped, so they are built instead of villages.
						check(villages <= 3, "real buildings replace villages in the city: %d villages in 1 km square", villages);
						check(!counts.containsKey("pillager_outpost"), "no pillager outposts in the city");
					} else {
						check(villages == 0, "no villages out at sea");
					}
				}
			}
		}));

		phases.add(Map.entry("Roads and railways", () -> {
			// Roads and railways: central Stockholm, about 600 m square around the start.
			{
				TerrainField field = RealEarth.terrain();
				int paved = 0;
				int paths = 0;
				int track = 0;
				int decks = 0;
				int bestRails = 0;
				int bestCx = 0;
				int bestCz = 0;
				for (int cx = startCx - 20; cx <= startCx + 20; cx++) {
					for (int cz = startCz - 20; cz <= startCz + 20; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						int rails = 0;
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								byte w = c.way[k];
								if (se.itssimple.realearth.osm.Ways.isPaved(w)) {
									paved++;
								} else if (w == se.itssimple.realearth.osm.Ways.PATH) {
									paths++;
								} else if (w == se.itssimple.realearth.osm.Ways.RAIL) {
									rails++;
								}
								if (c.deckY[k] != Integer.MIN_VALUE && c.deckWay[k] != se.itssimple.realearth.osm.Ways.NONE) {
									decks++;
								}
							}
						}
						track += rails;
						if (rails > bestRails) {
							bestRails = rails;
							bestCx = cx;
							bestCz = cz;
						}
					}
				}
				log("ways in 41 x 41 chunks of central Stockholm: %d paved, %d path, %d rail, %d bridge deck columns", paved, paths, track, decks);
				check(paved > 20_000 && paths > 2_000, "streets and paths are drawn in the city");
				check(decks > 100, "bridges carry ways over the water");
				// A surface railway from the map itself: the longest non-bridge line in the tile north of the city.
				{
					int mx = (int) Math.floor(Mercator.sampleX(18.0278) / (Mercator.TILE_SIZE * 2));
					int my = (int) Math.floor(Mercator.sampleY(59.3497) / (Mercator.TILE_SIZE * 2));
					double[] longest = null;
					for (var w : field.map().tile(mx, my).join().ways()) {
						if (w.kind() == se.itssimple.realearth.osm.Ways.RAIL && !w.bridge() && (longest == null || w.points().length > longest.length)) {
							longest = w.points();
						}
					}
					if (longest != null) {
						int mid = (longest.length / 4) * 2;
						double rLat = Mercator.latitudeOfSampleY(longest[mid + 1]);
						double rLon = (longest[mid] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0;
						int tCx = (int) field.projection().blockX(rLat, rLon) >> 4;
						int tCz = (int) field.projection().blockZ(rLat) >> 4;
						for (int cx = tCx - 3; cx <= tCx + 3; cx++) {
							for (int cz = tCz - 3; cz <= tCz + 3; cz++) {
								ChunkTerrain c;
								try {
									c = field.chunkBlocking(cx, cz, 120);
								} catch (Exception e) {
									continue;
								}
								int rails = 0;
								for (int x = 0; x < 16; x++) {
									for (int z = 0; z < 16; z++) {
										if (c.way[ChunkTerrain.index(x, z)] == se.itssimple.realearth.osm.Ways.RAIL) {
											rails++;
										}
									}
								}
								track += rails;
								if (rails > bestRails) {
									bestRails = rails;
									bestCx = cx;
									bestCz = cz;
								}
							}
						}
						log("railway at %.5f, %.5f: %d track columns nearby; most in chunk %d,%d (%d)", rLat, rLon, track, bestCx, bestCz, bestRails);
					}
				}
				check(track > 40, "railways are drawn");
				if (bestRails > 0) {
					LevelChunk c = earth.getChunk(bestCx, bestCz);
					ChunkTerrain terrain = RealEarth.terrain().chunkIfReady(bestCx, bestCz);
					int placed = 0;
					int joined = 0;
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							if (terrain != null && terrain.way[ChunkTerrain.index(x, z)] == se.itssimple.realearth.osm.Ways.RAIL) {
								BlockState rail = c.getBlockState(new BlockPos(x, terrain.topSolidY[ChunkTerrain.index(x, z)] + 1, z));
								if (placed == 0 && !rail.is(Blocks.RAIL)) {
									int ty = terrain.topSolidY[ChunkTerrain.index(x, z)];
									log("track column %d,%d top Y %d: %s / %s / %s; heightmap %d", x, z, ty, c.getBlockState(new BlockPos(x, ty - 1, z)),
										c.getBlockState(new BlockPos(x, ty, z)), rail, c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
								}
								if (rail.is(Blocks.RAIL)) {
									placed++;
									var shape = rail.getValue(net.minecraft.world.level.block.RailBlock.SHAPE);
									if (shape != net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH || terrain.way[ChunkTerrain.index(x, z - 1)] == se.itssimple.realearth.osm.Ways.RAIL
										|| terrain.way[ChunkTerrain.index(x, z + 1)] == se.itssimple.realearth.osm.Ways.RAIL) {
										joined++;
									}
								}
							}
						}
					}
					check(placed >= bestRails * 7 / 10 && joined >= placed * 9 / 10,
						"rails are laid along the track: %d of %d track columns, %d joined to a neighbour", placed, bestRails, joined);
				}
			}
		}));

		phases.add(Map.entry("Västerbron", () -> {
			// Västerbron, the long road bridge over Riddarfjärden: every chunk along it, decks over the water.
			{
				TerrainField field = RealEarth.terrain();
				int mx = (int) Math.floor(Mercator.sampleX(18.0262) / (Mercator.TILE_SIZE * 2));
				int my = (int) Math.floor(Mercator.sampleY(59.3228) / (Mercator.TILE_SIZE * 2));
				double[] longest = null;
				for (var w : field.map().tile(mx, my).join().ways()) {
					if (w.bridge() && se.itssimple.realearth.osm.Ways.isPaved(w.kind()) && (longest == null || length(w.points()) > length(longest))) {
						longest = w.points();
					}
				}
				java.util.Set<Long> along = new java.util.LinkedHashSet<>();
				if (longest != null) {
					for (int i = 0; i + 1 < longest.length; i += 2) {
						double rLat = Mercator.latitudeOfSampleY(longest[i + 1]);
						double rLon = (longest[i] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0;
						along.add(ChunkPos.pack((int) field.projection().blockX(rLat, rLon) >> 4, (int) field.projection().blockZ(rLat) >> 4));
					}
				}
				int decks = 0;
				int placed = 0;
				int overWater = 0;
				for (long key : along) {
					try {
						ChunkTerrain c = field.chunkBlocking(ChunkPos.getX(key), ChunkPos.getZ(key), 120);
						LevelChunk chunk = earth.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								if (c.deckY[k] != Integer.MIN_VALUE && c.deckWay[k] != se.itssimple.realearth.osm.Ways.NONE) {
									decks++;
									if (c.waterTopY[k] != Integer.MIN_VALUE) {
										overWater++;
									}
									BlockState at = chunk.getBlockState(new BlockPos(x, c.deckY[k], z));
									if (!at.isAir() && at.getFluidState().isEmpty()) {
										placed++;
									} else if (decks - placed == 1) {
										log("deck column %d,%d: deck Y %d (%.1f m), water top %d, ground top %d, block there %s", x, z, c.deckY[k], c.deckMeters[k],
											c.waterTopY[k], c.topSolidY[k], at);
									}
								}
							}
						}
					} catch (Exception e) {
						log("bridge chunk failed: %s", e);
					}
				}
				log("Västerbron: %d chunks along it, %d deck columns (%d over water), %d with a deck block", along.size(), decks, overWater, placed);
				check(overWater > 50 && placed >= decks * 9 / 10, "Västerbron's deck stands over the water (%d of %d columns)", placed, decks);

				// Every paved bridge in the map tiles of Västerbron, the city centre and Slussen can be walked
				// onto and along: down its centreline, from a few blocks before it to a few after, the surface
				// never steps more than a block.
				int bridges = 0;
				int columns = 0;
				int steps = 0;
				java.util.Set<Long> walkedTiles = new java.util.LinkedHashSet<>();
				for (double[] at : new double[][] {{59.3228, 18.0262}, {59.3293, 18.0686}, {59.3195, 18.0717}}) {
					walkedTiles.add(ChunkPos.pack((int) Math.floor(Mercator.sampleX(at[1]) / (Mercator.TILE_SIZE * 2)),
						(int) Math.floor(Mercator.sampleY(at[0]) / (Mercator.TILE_SIZE * 2))));
				}
				for (long tile : walkedTiles) {
					for (var w : field.map().tile(ChunkPos.getX(tile), ChunkPos.getZ(tile)).join().ways()) {
						if (!w.bridge() || !se.itssimple.realearth.osm.Ways.isPaved(w.kind())) {
							continue;
						}
						bridges++;
						int[] walked = walkSteps(field, w.points(), 8);
						columns += walked[0];
						steps += walked[1];
						if (walked[1] > 0 && walked[2] != Integer.MIN_VALUE) {
							log("bridge step: %d blocks at block %d,%d", walked[3], walked[2], walked[4]);
						}
					}
				}
				log("bridges in %d map tiles of Stockholm: %d, %d columns along them, %d steps of more than a block", walkedTiles.size(), bridges, columns, steps);
				check(bridges > 10 && steps * 1000 <= columns, "bridges are walkable: %d steps over %d columns", steps, columns);
			}
		}));

		phases.add(Map.entry("Place names", () -> {
			// Place names: which town and district a position is in, from the map.
			{
				TerrainField field = RealEarth.terrain();
				String[][] expect = {{"59.3150", "18.0720", "Stockholm", "Södermalm"}, {"59.3250", "18.0710", "Stockholm", "Gamla stan"},
					{"67.5000", "20.3000", null, null}};
				for (String[] e : expect) {
					double lat = Double.parseDouble(e[0]);
					double lon = Double.parseDouble(e[1]);
					double bx = field.projection().blockX(lat, lon);
					double bz = field.projection().blockZ(lat);
					se.itssimple.realearth.world.Places.Here here = null;
					for (int i = 0; i < 300 && here == null; i++) {
						here = se.itssimple.realearth.world.Places.at(field, bx, bz);
						if (here == null) {
							try {
								Thread.sleep(200);
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							}
						}
					}
					var got = here;
					log("place at %s, %s: %s", e[0], e[1], got);
					check(got != null && java.util.Objects.equals(got.town(), e[2]) && java.util.Objects.equals(got.district(), e[3]),
						"%s, %s is in %s, %s", e[0], e[1], e[3], e[2]);
					check(got != null && got.capital() == (e[2] != null), "%s, %s is %sa capital", e[0], e[1], e[2] != null ? "" : "not ");
				}
			}
		}));

		phases.add(Map.entry("The API for other mods", () -> {
			// The API for other mods: positions both ways, and what the map has at a block.
			{
				TerrainField field = RealEarth.terrain();
				var column = se.itssimple.realearth.api.RealEarthApi.toBlock(earth, 59.3293, 18.0686).orElseThrow();
				var back = se.itssimple.realearth.api.RealEarthApi.latLon(earth, column.x(), column.z()).orElseThrow();
				check(Math.abs(back.latitude() - 59.3293) < 1e-6 && Math.abs(back.longitude() - 18.0686) < 1e-6,
					"the API: lat/lon to block and back (%.7f, %.7f)", back.latitude(), back.longitude());
				double y = se.itssimple.realearth.api.RealEarthApi.yOf(earth, 25, column.z()).orElseThrow();
				double m = se.itssimple.realearth.api.RealEarthApi.elevationMeters(earth, y, column.z()).orElseThrow();
				check(Math.abs(m - 25) < 1e-6, "elevation to Y and back (25 m -> Y %.1f -> %.3f m)", y, m);
				check(!se.itssimple.realearth.api.RealEarthApi.isEarth(server.overworld()) || RealEarth.earthIsOverworld(server),
					"the API answers nothing outside the Earth");
				var gamla = se.itssimple.realearth.api.RealEarthApi.toBlock(earth, 59.3250, 18.0710).orElseThrow();
				var place = se.itssimple.realearth.api.RealEarthApi.place(earth, gamla.x(), gamla.z());
				check(place.isPresent() && place.get().town().equals("Stockholm") && "Gamla stan".equals(place.get().district()) && place.get().capital(),
					"the API's place in Gamla stan is Gamla stan, Stockholm, a capital (%s)", place);
				int buildingColumns = 0;
				int answered = 0;
				int rooms = 0;
				int wayColumns = 0;
				int named = 0;
				java.util.Map<String, Integer> uses = new java.util.TreeMap<>();
				java.util.Set<String> streets = new java.util.TreeSet<>();
				List<String> examples = new ArrayList<>();
				// Södermalm: blocks of flats along a street grid.
				var soder = se.itssimple.realearth.api.RealEarthApi.toBlock(earth, 59.3150, 18.0720).orElseThrow();
				int sCx = (int) Math.floor(soder.x()) >> 4;
				int sCz = (int) Math.floor(soder.z()) >> 4;
				for (int cx = sCx - 3; cx <= sCx + 3; cx++) {
					for (int cz = sCz - 3; cz <= sCz + 3; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								int bx = cx * 16 + x;
								int bz = cz * 16 + z;
								se.itssimple.realearth.terrain.BuildingPlan plan = c.buildingAt(x, z);
								if (plan != null) {
									buildingColumns++;
									var b = se.itssimple.realearth.api.RealEarthApi.building(earth, new BlockPos(bx, plan.floorY + 1, bz));
									if (b.isPresent()) {
										answered++;
										uses.merge(b.get().use(), 1, Integer::sum);
										if (b.get().room() != null) {
											rooms++;
											if (examples.size() < 6 && (bx + bz) % 97 == 0) {
												examples.add(b.get().toString());
											}
										}
									}
								}
								if (c.way[k] != 0 && plan == null) {
									wayColumns++;
									var st = se.itssimple.realearth.api.RealEarthApi.street(earth, bx, bz);
									if (st.isPresent()) {
										named++;
										streets.add(st.get());
									}
								}
							}
						}
					}
				}
				log("API on Södermalm: %d of %d building columns answered, %d in a room; uses %s; %d of %d street columns named, %d streets, e.g. %s",
					answered, buildingColumns, rooms, uses, named, wayColumns, streets.size(), streets.stream().limit(8).toList());
				for (String e : examples) {
					log("  %s", e);
				}
				check(buildingColumns > 1000 && answered > buildingColumns * 0.95, "the API knows the buildings on Södermalm (%d of %d)", answered, buildingColumns);
				check(rooms > answered / 3, "the API finds rooms on the ground floors (%d of %d)", rooms, answered);
				check(streets.size() >= 5 && named > wayColumns / 4, "the API names the streets on Södermalm (%d of %d columns, %d names)", named, wayColumns, streets.size());
			}
		}));

		phases.add(Map.entry("Waypoints for the minimap", () -> {
			// Waypoints for the minimap: the towns and stations round central Stockholm.
			{
				TerrainField field = RealEarth.terrain();
				var at = se.itssimple.realearth.api.RealEarthApi.toBlock(earth, 59.3310, 18.0600).orElseThrow();
				List<se.itssimple.realearth.world.MapWaypoints.Mark> marks = null;
				for (int i = 0; i < 300; i++) {
					marks = se.itssimple.realearth.world.MapWaypoints.around(field, at.x(), at.z());
					if (marks != null && marks.stream().anyMatch(m -> m.kind() == se.itssimple.realearth.world.MapWaypoints.STATION)) {
						break;
					}
					try {
						Thread.sleep(200);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
				var got = marks == null ? List.<se.itssimple.realearth.world.MapWaypoints.Mark>of() : marks;
				log("waypoints round central Stockholm: %s", got.stream().map(m -> m.kind() + " " + m.name()).toList());
				var stockholm = got.stream().filter(m -> m.name().equals("Stockholm")).findFirst();
				check(stockholm.isPresent() && stockholm.get().kind() == se.itssimple.realearth.world.MapWaypoints.CAPITAL,
					"Stockholm is a capital's waypoint round its centre");
				check(stockholm.isPresent() && Math.hypot(stockholm.get().x() - at.x(), stockholm.get().z() - at.z()) < 3000,
					"Stockholm's waypoint is near its centre");
				long stations = got.stream().filter(m -> m.kind() == se.itssimple.realearth.world.MapWaypoints.STATION).count();
				long towns = got.size() - stations;
				check(stations >= 2 && stations <= 8 && towns >= 3 && towns <= 12, "a few towns and stations are marked (%d towns, %d stations)", towns, stations);
			}
		}));

		phases.add(Map.entry("Downloads", () -> {
			// Downloads: one per resource at a time, the rest wait for it; and the counts /earth perf shows.
			{
				java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
				java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
				List<java.util.concurrent.CompletableFuture<byte[]>> asks = new ArrayList<>();
				for (int i = 0; i < 8; i++) {
					asks.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
						try {
							go.await();
							return se.itssimple.realearth.data.Net.once("selftest://same-resource", "selftest", () -> {
								ran.incrementAndGet();
								try {
									Thread.sleep(300);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
								}
								return new byte[] {42};
							});
						} catch (Exception e) {
							throw new java.util.concurrent.CompletionException(e);
						}
					}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));
				}
				go.countDown();
				boolean same = asks.stream().allMatch(f -> f.join()[0] == 42);
				check(ran.get() == 1 && same, "eight asks for one resource at once make one download (%d made)", ran.get());
				for (String line : se.itssimple.realearth.data.Net.report()) {
					log("  downloads: %s", line);
				}
			}
		}));

		phases.add(Map.entry("Advancements for real places", () -> {
			// Advancements for real places: all load, under one root.
			{
				int found = 0;
				for (String name : new String[] {"root", "town", "capital", "equator", "polar_circle", "prime_meridian", "date_line", "thin_air", "below_the_sea"}) {
					if (server.getAdvancements().get(RealEarth.id(name)) != null) {
						found++;
					}
				}
				check(found == 9, "the Real Earth advancements load (%d of 9)", found);
			}
		}));

		phases.add(Map.entry("Cemeteries and allotments", () -> {
			// Cemeteries and allotments: Norra begravningsplatsen's headstones, Tantolunden's plots.
			{
				TerrainField field = RealEarth.terrain();
				ServerLevel level = RealEarth.earthLevel(server);
				int[] found = new int[4];
				for (double[] place : new double[][] {{59.3555, 18.0225}, {59.3122, 18.0470}}) {
					int cx0 = (int) Math.floor(field.projection().blockX(place[0], place[1])) >> 4;
					int cz0 = (int) Math.floor(field.projection().blockZ(place[0])) >> 4;
					for (int cx = cx0 - 2; cx <= cx0 + 2; cx++) {
						for (int cz = cz0 - 2; cz <= cz0 + 2; cz++) {
							LevelChunk chunk = level.getChunk(cx, cz);
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							for (int k2 = 0; c != null && k2 < 256; k2++) {
								int x = k2 & 15;
								int z = k2 >> 4;
								int k = ChunkTerrain.index(x, z);
								var above = chunk.getBlockState(new BlockPos(x, c.topSolidY[k] + 1, z));
								if (c.grounds[k] == ChunkTerrain.CEMETERY) {
									found[0]++;
									found[1] += above.is(Blocks.STONE_BRICK_WALL) || above.is(Blocks.MOSSY_STONE_BRICK_WALL) || above.is(Blocks.ANDESITE_WALL)
										|| above.is(Blocks.CHISELED_STONE_BRICKS) ? 1 : 0;
								} else if (c.grounds[k] == ChunkTerrain.ALLOTMENT) {
									found[2]++;
									found[3] += above.is(net.minecraft.tags.BlockTags.CROPS) ? 1 : 0;
								}
							}
						}
					}
				}
				log("cemetery: %d columns, %d headstones; allotments: %d columns, %d crops", found[0], found[1], found[2], found[3]);
				check(found[0] > 100 && found[1] > 10 && found[2] > 50 && found[3] > 25, "cemeteries have headstones, allotments crops (%d, %d)", found[1], found[3]);
			}
		}));

		phases.add(Map.entry("Spawning on land", () -> {
			// Spawning on land: a start point out on Saltsjön moves to the shore.
			{
				TerrainField field = RealEarth.terrain();
				int x = (int) field.projection().blockX(59.3205, 18.1100);
				int z = (int) field.projection().blockZ(59.3205);
				int[] land = field.landNear(x, z);
				ChunkTerrain t = field.chunkIfReady(land[0] >> 4, land[1] >> 4);
				boolean dry = t != null && t.waterTopY[ChunkTerrain.index(land[0] & 15, land[1] & 15)] == Integer.MIN_VALUE;
				double km = Math.hypot(land[0] - x, land[1] - z) / field.projection().blocksPerMeter(59.32) / 1000;
				log("spawn from Saltsjön: land at %d,%d, %.2f km away, %s", land[0], land[1], km, dry ? "dry" : "in water");
				check(dry && km > 0.05 && km < 3, "a start point in the sea moves to the nearest land (%.2f km)", km);
			}
		}));

		phases.add(Map.entry("Fish in lakes above the sea", () -> {
			// Fish in lakes above the sea: Siljan, 161 m up.
			{
				TerrainField field = RealEarth.terrain();
				int x = (int) field.projection().blockX(60.88, 14.75);
				int z = (int) field.projection().blockZ(60.88);
				var chunk = earth.getChunk(x >> 4, z >> 4);
				int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
				BlockPos pos = new BlockPos(x, top - 2, z);
				boolean water = earth.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER) && earth.getFluidState(pos.above(2)).is(net.minecraft.tags.FluidTags.WATER);
				int ok = 0;
				for (long seed = 0; seed < 20; seed++) {
					ok += net.minecraft.world.entity.animal.fish.WaterAnimal.checkSurfaceWaterAnimalSpawnRules(net.minecraft.world.entity.EntityType.COD, earth,
						net.minecraft.world.entity.EntitySpawnReason.NATURAL, pos, net.minecraft.util.RandomSource.create(seed)) ? 1 : 0;
				}
				log("fish in Siljan: water surface at Y %d (%s), the spawn rule allows %d of 20", top - 1, water ? "deep enough" : "not water", ok);
				check(water && ok == 20, "fish may spawn in lakes above the sea (%d of 20)", ok);
			}
		}));

		// Railways you can ride: every rail joins the next, on the ground, over bridges and through tunnels.
		// Karlberg's yards; the main line by Marsta; the Arlanda line going under ground; Gothenburg's rail
		// tunnels; Soderledstunneln, a road under Sodermalm. An area a phase; the sums judged at the end.
		int[] ride = new int[9];
		for (double[] place : new double[][] {{59.3458, 18.0182}, {59.6200, 17.8600}, {59.6141, 17.8612}, {57.7150, 11.9904}, {59.3140, 18.0700}}) {
			phases.add(Map.entry(String.format(java.util.Locale.ROOT, "Railways at %.4f, %.4f", place[0], place[1]), () -> {
				TerrainField field = RealEarth.terrain();
				int cx = (int) Math.floor(field.projection().blockX(place[0], place[1])) >> 4;
				int cz = (int) Math.floor(field.projection().blockZ(place[0])) >> 4;
				int[] r = rideRails(earth, field, cx, cz, 3);
				log("rails at %.4f, %.4f: %d rails, %d broken ends (%d on a gap, %d not joining), %d track ends, %d powered (%d without power); "
					+ "%d tunnel columns, %d lanterns", place[0], place[1], r[0], r[1], r[4], r[5], r[8], r[2], r[3], r[6], r[7]);
				for (int i = 0; i < ride.length; i++) {
					ride[i] += r[i];
				}
			}));
		}
		// Tunnels under the ground: a long tunnel's roof stays under the ground over it, and no lantern hangs
		// under the open sky. Soderledstunneln under Sodermalm, Citybanan under the city, a rail tunnel in
		// Gothenburg; and a short one, a railway under a road by Arlanda (last: no cover asked of it).
		for (double[] place : new double[][] {{59.3150, 18.0720, 1}, {59.3335, 18.0587, 1}, {57.7122, 11.9785, 1}, {59.6141, 17.8612, 0}}) {
			phases.add(Map.entry(String.format(java.util.Locale.ROOT, "Tunnels under the ground at %.4f, %.4f", place[0], place[1]), () -> {
				TerrainField field = RealEarth.terrain();
				int cx0 = (int) Math.floor(field.projection().blockX(place[0], place[1])) >> 4;
				int cz0 = (int) Math.floor(field.projection().blockZ(place[0])) >> 4;
				int bores = 0;
				int open = 0;
				// Of main roads, motorways and railways: these have to be under the ground. A short service
				// road, street or path in a tunnel (into a garage, under a road) may stay at street level.
				int mainBores = 0;
				int mainOpen = 0;
				int lanterns = 0;
				int hanging = 0;
				Map<Integer, Integer> openKinds = new java.util.TreeMap<>();
				List<String> samples = new ArrayList<>();
				for (int cx = cx0 - 3; cx <= cx0 + 3; cx++) {
					for (int cz = cz0 - 3; cz <= cz0 + 3; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						earth.getChunk(cx, cz);
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								if (c.tunnel[k] != ChunkTerrain.TUNNEL_BORE) {
									continue;
								}
								bores++;
								byte kind = c.tunnelKind[k];
								boolean main = kind == se.itssimple.realearth.osm.Ways.MAJOR || kind == se.itssimple.realearth.osm.Ways.MOTORWAY
									|| kind == se.itssimple.realearth.osm.Ways.RAIL_BED || kind == se.itssimple.realearth.osm.Ways.RAIL
									|| kind == se.itssimple.realearth.osm.Ways.TRAM;
								if (main) {
									mainBores++;
								}
								int bx = cx * 16 + x;
								int bz = cz * 16 + z;
								int roof = c.tunnelY[k] + se.itssimple.realearth.terrain.Tunnels.HEADROOM_BLOCKS + 1;
								int sky = earth.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bx, bz);
								boolean exposed = sky <= roof + 1;
								if (exposed) {
									open++;
									openKinds.merge((int) c.tunnelKind[k], 1, Integer::sum);
									if (main) {
										mainOpen++;
									}
								}
								if (earth.getBlockState(new BlockPos(bx, roof - 1, bz)).is(net.minecraft.world.level.block.Blocks.LANTERN)) {
									lanterns++;
									if (earth.getBlockState(new BlockPos(bx, roof, bz)).isAir()) {
										hanging++;
									}
								}
								if ((bx * 31 + bz) % 211 == 0 && samples.size() < 12) {
									samples.add(String.format(java.util.Locale.ROOT, "%d,%d floor %d roof %d ground %d (%.1f m) surface %d%s", bx, bz, c.tunnelY[k], roof,
										c.topSolidY[k], c.groundMeters[k], sky, exposed ? " OPEN" : ""));
								}
							}
						}
					}
				}
				log("tunnels at %.4f, %.4f: %d bore columns, %d open to the sky (by way kind %s); %d lanterns, %d hanging from nothing", place[0], place[1],
					bores, open, openKinds, lanterns, hanging);
				for (String sample : samples) {
					log("  %s", sample);
				}
				if (place[2] > 0) {
					check(mainBores > 50 && mainOpen * 20 <= mainBores, "main road and rail tunnels at %.4f, %.4f run under the ground (%d of %d bore columns open)",
						place[0], place[1], mainOpen, mainBores);
					check(lanterns > 0, "tunnels at %.4f, %.4f are lit (%d lanterns)", place[0], place[1], lanterns);
				}
				check(hanging == 0, "no tunnel lantern at %.4f, %.4f hangs under the open sky (%d)", place[0], place[1], hanging);
			}));
		}
		phases.add(Map.entry("Railways: the verdict", () -> {
			check(ride[0] > 100 && ride[1] * 200 <= ride[0], "rails join up end to end (%d broken ends over %d rails)", ride[1], ride[0]);
			check(ride[2] * 20 >= ride[0] && ride[3] == 0, "railways are powered (%d powered rails, %d without power, over %d rails)", ride[2], ride[3], ride[0]);
			check(ride[6] > 50 && ride[7] > 0, "railway tunnels are dug out and lit (%d tunnel columns, %d lanterns)", ride[6], ride[7]);
		}));

		phases.add(Map.entry("Slimes", () -> {
			// Slimes: in slime chunks only well below the sea, as in vanilla; not on the surface.
			{
				TerrainField field = RealEarth.terrain();
				int startX = (int) field.projection().blockX(59.3293, 18.0686) >> 4;
				int startZ = (int) field.projection().blockZ(59.3293) >> 4;
				int surface = 0;
				int deep = 0;
				int chunks = 0;
				for (int cx = startX - 8; cx <= startX + 8 && chunks < 3; cx++) {
					for (int cz = startZ - 8; cz <= startZ + 8 && chunks < 3; cz++) {
						if (net.minecraft.world.level.levelgen.WorldgenRandom.seedSlimeChunk(cx, cz, earth.getSeed(), 987234911L).nextInt(10) != 0) {
							continue;
						}
						chunks++;
						var chunk = earth.getChunk(cx, cz);
						int x = cx * 16 + 8;
						int z = cz * 16 + 8;
						BlockPos top = new BlockPos(x, chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8) + 1, z);
						BlockPos low = new BlockPos(x, VerticalScale.SEA_LEVEL_Y - 60, z);
						for (long seed = 0; seed < 200; seed++) {
							surface += net.minecraft.world.entity.monster.Slime.checkSlimeSpawnRules(net.minecraft.world.entity.EntityType.SLIME, earth,
								net.minecraft.world.entity.EntitySpawnReason.NATURAL, top, net.minecraft.util.RandomSource.create(seed)) ? 1 : 0;
							deep += net.minecraft.world.entity.monster.Slime.checkSlimeSpawnRules(net.minecraft.world.entity.EntityType.SLIME, earth,
								net.minecraft.world.entity.EntitySpawnReason.NATURAL, low, net.minecraft.util.RandomSource.create(seed)) ? 1 : 0;
						}
					}
				}
				log("slime spawn rule in %d slime chunks, 200 tries each: %d on the surface, %d 60 blocks under the sea", chunks, surface, deep);
				check(chunks > 0 && surface == 0 && deep > 0, "slimes spawn deep in slime chunks, not on the surface (%d surface, %d deep)", surface, deep);
			}
		}));

		phases.add(Map.entry("Buildings", () -> {
			// Buildings: central Stockholm, as planned and as built.
			{
				TerrainField field = RealEarth.terrain();
				int columns = 0;
				int maxFloors = 0;
				int minStorey = Integer.MAX_VALUE;
				int bestCx = 0;
				int bestCz = 0;
				int bestColumns = 0;
				java.util.Set<Long> ids = new java.util.HashSet<>();
				for (int cx = startCx - 6; cx <= startCx + 6; cx++) {
					for (int cz = startCz - 6; cz <= startCz + 6; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						int here = 0;
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								if (c.building[ChunkTerrain.index(x, z)] != 0) {
									here++;
								}
							}
						}
						for (var plan : c.plans) {
							ids.add(plan.id);
							maxFloors = Math.max(maxFloors, plan.floors);
							minStorey = Math.min(minStorey, plan.storey);
						}
						columns += here;
						if (here > bestColumns) {
							bestColumns = here;
							bestCx = cx;
							bestCz = cz;
						}
					}
				}
				log("buildings in 13 x 13 chunks of central Stockholm: %d buildings over %d columns; up to %d floors, storeys at least %d blocks",
					ids.size(), columns, maxFloors, minStorey);
				check(ids.size() > 20 && columns > 10_000, "the city's real buildings are planned");
				check(maxFloors <= RealEarth.config().maxFloors && maxFloors >= 4 && minStorey >= 5, "floors are 5+ blocks, at most %d of them", RealEarth.config().maxFloors);

				java.util.Map<String, Integer> blocks = new java.util.TreeMap<>();
				for (int cx = bestCx - 1; cx <= bestCx + 1; cx++) {
					for (int cz = bestCz - 1; cz <= bestCz + 1; cz++) {
						LevelChunk c = earth.getChunk(cx, cz);
						ChunkTerrain t = field.chunkIfReady(cx, cz);
						if (t == null) {
							continue;
						}
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								if (t.building[k] == 0) {
									continue;
								}
								var plan = t.plans.get(t.building[k] - 1);
								for (int y = plan.floorY; y <= t.roofY[k] + 1; y++) {
									String name = c.getBlockState(new BlockPos(x, y, z)).getBlock().getDescriptionId().replace("block.minecraft.", "");
									blocks.merge(name, 1, Integer::sum);
								}
							}
						}
					}
				}
				log("blocks in 3 x 3 chunks of buildings: %s", blocks);
				// Beds over the whole centre: the 3 x 3 chunks can be all offices.
				int beds = 0;
				int mostBeds = 0;
				for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
					for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
						earth.getChunk(cx, cz);
						ChunkTerrain t = field.chunkIfReady(cx, cz);
						beds += t == null ? 0 : t.beds.size();
						if (t != null && t.beds.size() > mostBeds) {
							mostBeds = t.beds.size();
							homesAt = new int[] {cx, cz};
						}
					}
				}
				int doors = blocks.entrySet().stream().filter(e -> e.getKey().endsWith("_door")).mapToInt(java.util.Map.Entry::getValue).sum();
				check(blocks.getOrDefault("glass_pane", 0) > 100 && blocks.getOrDefault("lantern", 0) > 5 && blocks.getOrDefault("air", 0) > 1000,
					"buildings have windows, lanterns and rooms");
				check(beds >= 5 && doors >= 6, "buildings have beds (%d) and door blocks (%d)", beds, doors);
				// Chests and storeroom barrels carry a loot table (so Lootr converts them); job barrels and
				// chiseled bookshelves don't, but the shelves hold books.
				int looted = 0;
				int containers = 0;
				int books = 0;
				for (int cx = bestCx - 1; cx <= bestCx + 1; cx++) {
					for (int cz = bestCz - 1; cz <= bestCz + 1; cz++) {
						for (var be : earth.getChunk(cx, cz).getBlockEntities().values()) {
							if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r) {
								containers++;
								if (r.getLootTable() != null) {
									looted++;
								}
							} else if (be instanceof net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity shelf) {
								books += shelf.getItems().stream().filter(s -> !s.isEmpty()).count();
							}
						}
					}
				}
				log("containers in 3 x 3 chunks of buildings: %d, %d with a loot table; %d books on chiseled shelves", containers, looted, books);
				check(looted >= 3 && looted * 2 >= containers, "chests and storeroom barrels have loot tables (%d of %d)", looted, containers);
				lootrCheck = new int[] {bestCx, bestCz, looted};
				var storeroom = server.reloadableRegistries().getLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
					net.minecraft.resources.Identifier.fromNamespaceAndPath("realearth", "chests/storeroom")));
				check(storeroom != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "the storeroom loot table loads");
				// Town maps in bookshops: a filled map of the town, named after it.
				{
					var bookshop = server.reloadableRegistries().getLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
						net.minecraft.resources.Identifier.fromNamespaceAndPath("realearth", "chests/shop_books")));
					net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(field.projection().blockX(59.3250, 18.0710), 0, field.projection().blockZ(59.3250));
					var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(earth)
						.withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, at)
						.create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
					net.minecraft.world.item.ItemStack map = null;
					for (long seed = 0; seed < 200 && map == null; seed++) {
						for (var stack : bookshop.getRandomItems(params, seed)) {
							if (stack.is(net.minecraft.world.item.Items.FILLED_MAP) || stack.is(net.minecraft.world.item.Items.MAP)) {
								map = stack;
							}
						}
					}
					String name = map == null ? "none" : map.getHoverName().getString();
					log("town map from a bookshop in Gamla stan: %s, %s", map == null ? "none" : map.getItem(), name);
					check(map != null && map.is(net.minecraft.world.item.Items.FILLED_MAP) && name.contains("Stockholm"), "bookshops sell maps of their own town (%s)", name);
				}

				// What the buildings of the city centre are used for, from the map's shops and land use.
				{
					java.util.Map<String, Integer> uses = new java.util.TreeMap<>();
					java.util.Map<String, Integer> trades = new java.util.TreeMap<>();
					java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
					java.util.Set<Long> seen = new java.util.HashSet<>();
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							if (c == null) {
								continue;
							}
							for (var plan : c.plans) {
								if (!seen.add(plan.id)) {
									continue;
								}
								uses.merge(plan.use + (plan.hall != null ? "/" + plan.hall : ""), 1, Integer::sum);
								if (plan.floors > 1 && plan.use != se.itssimple.realearth.terrain.BuildingPlan.Use.WAREHOUSE) {
									uses.merge(plan.interior().stairs.isEmpty() ? "storeys by ladder" : "storeys by stairs", 1, Integer::sum);
									if (plan.interior().stairs.isEmpty()) {
										log("ladder building %d (%s, %d floors of %d): ground floor\n%s", plan.id, plan.use, plan.floors, plan.storey, plan.interior().sketch(0));
									}
								}
								plan.shops.forEach(s -> trades.merge(s.trade().name(), 1, Integer::sum));
								plan.interior().rooms.forEach(r -> kinds.merge(r.kind.name(), 1, Integer::sum));
							}
						}
					}
					log("building uses in 13 x 13 chunks of Stockholm: %s; shops %s; rooms %s", uses, trades, kinds);
					check(uses.getOrDefault("FLATS", 0) + uses.getOrDefault("OFFICES", 0) > 10 && !trades.isEmpty() && kinds.getOrDefault("BED", 0) > 20,
						"buildings are flats, offices and shops, divided into rooms");
				}

				// Buildings as the map colours them, and towers on their roofs.
				{
					int coloured = 0;
					int matched = 0;
					int towers = 0;
					int standing = 0;
					java.util.Map<String, Integer> walls = new java.util.TreeMap<>();
					java.util.Set<Long> seen = new java.util.HashSet<>();
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							if (c == null) {
								continue;
							}
							LevelChunk chunk = earth.getChunk(cx, cz);
							for (int x = 0; x < 16; x++) {
								for (int z = 0; z < 16; z++) {
									int k = ChunkTerrain.index(x, z);
									if (c.building[k] == 0) {
										continue;
									}
									var plan = c.plans.get(c.building[k] - 1);
									int wx = cx * 16 + x;
									int wz = cz * 16 + z;
									var tower = plan.towerAt(wx, wz);
									if (tower != null && seen.add(plan.id * 31 + tower.topY())) {
										towers++;
										if (!chunk.getBlockState(new BlockPos(x, tower.topY() - tower.capRise(), z)).isAir()) {
											standing++;
										}
									}
									net.minecraft.world.level.block.Block wall = se.itssimple.realearth.world.Facades.wall(plan.colour);
									if (wall == null || !seen.add(plan.id)) {
										continue;
									}
									coloured++;
									walls.merge(wall.getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
									// Its wall block somewhere in its outer wall, a storey up.
									boolean found = false;
									for (int i = 0; i < 16 && !found; i++) {
										for (int j = 0; j < 16 && !found; j++) {
											int kk = ChunkTerrain.index(i, j);
											if (c.building[kk] != 0 && c.plans.get(c.building[kk] - 1).id == plan.id) {
												for (int dy = 1; dy <= plan.storey && !found; dy++) {
													found = chunk.getBlockState(new BlockPos(i, plan.floorY + plan.storey + dy, j)).is(wall);
												}
											}
										}
									}
									if (found) {
										matched++;
									}
								}
							}
						}
					}
					log("map colours in 13 x 13 chunks of Stockholm: %d coloured buildings (%d built in their colour's block), walls %s; %d towers, %d standing",
						coloured, matched, walls, towers, standing);
					check(coloured >= 3 && matched * 10 >= coloured * 8, "buildings are built in their colours from the map (%d of %d)", matched, coloured);
					check(towers >= 1 && standing == towers, "towers stand on the roofs of their buildings (%d of %d)", standing, towers);
				}

				// Roofs as mapped: OpenStreetMap's 3D tags from the Overture buildings tiles.
				{
					var detail = field.map() == null ? null : field.map().detail();
					int shapes = -1;
					try {
						shapes = detail == null ? -1 : detail.tile(9014, 4818).get(120, java.util.concurrent.TimeUnit.SECONDS).shapes().size();
					} catch (Exception e) {
						log("building detail: %s", e);
					}
					java.util.Map<String, Integer> roofs = new java.util.TreeMap<>();
					java.util.Map<String, Integer> caps = new java.util.TreeMap<>();
					java.util.Set<Long> seenPlans = new java.util.HashSet<>();
					// Over all of the city planned so far (the centre's blocks are mostly offices, roofs unmapped).
					for (int cx = bestCx - 40; cx <= bestCx + 40; cx++) {
						for (int cz = bestCz - 40; cz <= bestCz + 40; cz++) {
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							for (var plan : c == null ? List.<se.itssimple.realearth.terrain.BuildingPlan>of() : c.plans) {
								if (seenPlans.add(plan.id)) {
									roofs.merge(plan.roof.name(), 1, Integer::sum);
									plan.towers.forEach(t -> caps.merge(t.roof().name(), 1, Integer::sum));
								}
							}
						}
					}
					log("building detail: %d shapes in central Stockholm's tile; of %d buildings planned round it, roofs %s; tower caps %s", shapes, seenPlans.size(), roofs, caps);
					int mapped = roofs.entrySet().stream().filter(e -> !e.getKey().equals("NONE")).mapToInt(java.util.Map.Entry::getValue).sum();
					check(shapes > 100 && seenPlans.size() >= 30 && mapped * 20 >= seenPlans.size(), "buildings take their roofs as mapped (%d shapes in the tile, %d of %d buildings)",
						shapes, mapped, seenPlans.size());
				}

				// Street life: the map's furniture where it is mapped, and parks with benches and fountains.
				{
					java.util.Map<String, Integer> life = new java.util.TreeMap<>();
					int parkColumns = 0;
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							if (c == null) {
								continue;
							}
							LevelChunk chunk = earth.getChunk(cx, cz);
							for (int x = 0; x < 16; x++) {
								for (int z = 0; z < 16; z++) {
									int k = ChunkTerrain.index(x, z);
									if (c.building[k] != 0) {
										continue;
									}
									parkColumns += c.park[k] ? 1 : 0;
									if (c.grounds[k] != 0) {
										life.merge(switch (c.grounds[k]) {
											case ChunkTerrain.CEMETERY -> "cemetery columns";
											case ChunkTerrain.ALLOTMENT -> "allotment columns";
											case ChunkTerrain.FLOWERBED -> "flower bed columns";
											default -> "pitch columns";
										}, 1, Integer::sum);
									}
									if (c.park[k] && c.way[k] == se.itssimple.realearth.osm.Ways.NONE) {
										boolean path = false;
										for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
											path |= c.way[ChunkTerrain.index(x + d[0], z + d[1])] != se.itssimple.realearth.osm.Ways.NONE;
										}
										if (path) {
											life.merge("park edges by paths", 1, Integer::sum);
										}
									}
									int ground = c.topSolidY[k];
									for (int y = ground + 1; y <= ground + 3; y++) {
										var state = chunk.getBlockState(new BlockPos(x, y, z));
										String what = state.is(Blocks.CAULDRON) ? "bins" : state.is(Blocks.WATER_CAULDRON) ? "drinking water"
											: state.is(Blocks.WAXED_OXIDIZED_CHISELED_COPPER) ? "statues" : state.is(Blocks.CHISELED_QUARTZ_BLOCK) ? "monuments"
											: state.is(Blocks.SPRUCE_STAIRS) ? "benches" : state.is(Blocks.STONE_BRICK_WALL) ? "fountain columns"
											: state.is(Blocks.OAK_PRESSURE_PLATE) ? "picnic tables" : c.park[k] && state.is(Blocks.LANTERN) ? "park lamps"
											: c.park[k] && state.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS) ? "park flowers"
											: c.grounds[k] == ChunkTerrain.CEMETERY && (state.is(Blocks.STONE_BRICK_WALL) || state.is(Blocks.MOSSY_STONE_BRICK_WALL)
												|| state.is(Blocks.ANDESITE_WALL) || state.is(Blocks.CHISELED_STONE_BRICKS)) ? "headstones"
											: c.grounds[k] == ChunkTerrain.ALLOTMENT && state.is(net.minecraft.tags.BlockTags.CROPS) ? "crops"
											: c.grounds[k] == ChunkTerrain.FLOWERBED && state.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS) ? "bedded flowers" : null;
										if (what != null) {
											life.merge(what, 1, Integer::sum);
										}
									}
								}
							}
						}
					}
					log("street life in 13 x 13 chunks of Stockholm: %s; %d park columns", life, parkColumns);
					// A harbour from central Stockholm's map tile; its chunk generated, with its boats.
					try {
						for (var poi : field.map().tile(9014, 4818).get(60, java.util.concurrent.TimeUnit.SECONDS).pois()) {
							if (java.util.Set.of("marina", "dock", "ferry_terminal").contains(poi.subclass())) {
								double lat = se.itssimple.realearth.geo.Mercator.latitudeOfSampleY(poi.y());
								double lon = (poi.x() + 0.5) / se.itssimple.realearth.geo.Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0;
								int bx = (int) Math.floor(field.projection().blockX(lat, lon));
								int bz = (int) Math.floor(field.projection().blockZ(lat));
								earth.getChunk(bx >> 4, bz >> 4);
								ChunkTerrain c = field.chunkIfReady(bx >> 4, bz >> 4);
								if (c != null && c.pois.stream().anyMatch(q -> poi.subclass().equals(q.subclass()))) {
									harbourAt = new int[] {bx >> 4, bz >> 4};
									log("harbour: %s %s at %d,%d", poi.subclass(), poi.name(), bx, bz);
									break;
								}
							}
						}
					} catch (Exception e) {
						log("harbour: %s", e);
					}
					check(life.getOrDefault("bins", 0) >= 1 && life.getOrDefault("benches", 0) >= 3 && parkColumns > 200,
						"streets and parks have their furniture (%s)", life);
					check((life.getOrDefault("cemetery columns", 0) < 50 || life.getOrDefault("headstones", 0) > 0)
						&& (life.getOrDefault("allotment columns", 0) < 50 || life.getOrDefault("crops", 0) > 0)
						&& (life.getOrDefault("flower bed columns", 0) < 20 || life.getOrDefault("bedded flowers", 0) > 0),
						"cemeteries have headstones, allotments crops, flower beds flowers (%s)", life);
				}

				// No lava lakes in the parks: in town, lava at the ground was made a pond.
				{
					int lava = 0;
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							if (c == null) {
								continue;
							}
							LevelChunk chunk = earth.getChunk(cx, cz);
							for (int x = 0; x < 16; x++) {
								for (int z = 0; z < 16; z++) {
									int ground = c.topSolidY[ChunkTerrain.index(x, z)];
									for (int y = ground - 6; y <= ground + 4; y++) {
										if (chunk.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.LAVA)) {
											lava++;
										}
									}
								}
							}
						}
					}
					check(lava == 0, "no lava at the ground in the city (%d blocks)", lava);
				}

				// Names on signs: over shop and hall doors, and on posts at street corners.
				{
					int streetSigns = 0;
					int doorSigns = 0;
					List<String> seen = new ArrayList<>();
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							for (var be : earth.getChunk(cx, cz).getBlockEntities().values()) {
								if (!(be instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
									continue;
								}
								StringBuilder text = new StringBuilder();
								for (var line : sign.getFrontText().getMessages(false)) {
									if (!line.getString().isEmpty()) {
										text.append(text.length() > 0 ? " " : "").append(line.getString());
									}
								}
								if (text.length() == 0) {
									continue;
								}
								boolean wall = sign.getBlockState().getBlock() instanceof net.minecraft.world.level.block.WallSignBlock;
								if (wall) {
									doorSigns++;
								} else {
									streetSigns++;
								}
								if (seen.size() < 8) {
									seen.add((wall ? "door: " : "street: ") + text);
								}
							}
						}
					}
					log("signs in 13 x 13 chunks of Stockholm: %d street name posts, %d over doors; e.g. %s", streetSigns, doorSigns, seen);
					check(streetSigns >= 10 && doorSigns >= 1, "streets and shops have their names on signs (%d streets, %d doors)", streetSigns, doorSigns);
				}

				// Nothing floats: every block in the buildings touches another, and what needs a floor has one.
				{
					int floating = 0;
					int unsupported = 0;
					int skyDoors = 0;
					for (int cx = bestCx - 1; cx <= bestCx + 1; cx++) {
						for (int cz = bestCz - 1; cz <= bestCz + 1; cz++) {
							LevelChunk chunk = earth.getChunk(cx, cz);
							ChunkTerrain c = field.chunkIfReady(cx, cz);
							if (c == null) {
								continue;
							}
							for (int x = 0; x < 16; x++) {
								for (int z = 0; z < 16; z++) {
									int k = ChunkTerrain.index(x, z);
									if (c.building[k] == 0) {
										continue;
									}
									var plan = c.plans.get(c.building[k] - 1);
									for (int y = plan.floorY + 1; y <= plan.roofBaseY() + plan.roofRise + 1; y++) {
										BlockPos at = new BlockPos(cx * 16 + x, y, cz * 16 + z);
										BlockState s = earth.getBlockState(at);
										if (s.isAir()) {
											continue;
										}
										// Stray: nothing at all in the 3 x 3 x 3 round it. (A stepped roof's blocks touch at
										// their edges only, as every stair roof does, and look whole.)
										boolean alone = true;
										for (int dx = -1; dx <= 1 && alone; dx++) {
											for (int dy = -1; dy <= 1 && alone; dy++) {
												for (int dz = -1; dz <= 1 && alone; dz++) {
													alone = dx == 0 && dy == 0 && dz == 0 || earth.getBlockState(at.offset(dx, dy, dz)).isAir();
												}
											}
										}
										if (alone) {
											if (floating++ < 5) {
												StringBuilder around = new StringBuilder();
												for (int d = 0; d < 4; d++) {
													int nx = x + (d == 1 ? 1 : d == 3 ? -1 : 0);
													int nz = z + (d == 2 ? 1 : d == 0 ? -1 : 0);
													int nk = ChunkTerrain.index(nx, nz);
													around.append(" ").append(c.building[nk]).append("/").append(c.building[nk] == 0 ? "-" : String.valueOf(c.roofY[nk]));
												}
												log("  floating %s at %d,%d,%d: building %d (roof Y %d, %s, %d floors from Y %d); neighbours' buildings/roof Y:%s",
													s.getBlock().getDescriptionId(), at.getX(), at.getY(), at.getZ(), c.building[k], c.roofY[k], plan.use, plan.floors, plan.floorY, around);
											}
										}
										Block b = s.getBlock();
										// A door in an outer wall above the ground floor opens onto a fall.
										if (b instanceof net.minecraft.world.level.block.DoorBlock && y > plan.floorY + 3) {
											boolean outer = false;
											for (Direction d : Direction.Plane.HORIZONTAL) {
												int nk = ChunkTerrain.index(x + d.getStepX(), z + d.getStepZ());
												outer |= c.building[nk] != c.building[k];
											}
											if (outer && skyDoors++ < 3) {
												log("  door to nowhere at %d,%d,%d", at.getX(), at.getY(), at.getZ());
											}
										}
										boolean needsFloor = b instanceof net.minecraft.world.level.block.CarpetBlock || b instanceof net.minecraft.world.level.block.PressurePlateBlock
											|| b instanceof net.minecraft.world.level.block.FlowerPotBlock || b instanceof net.minecraft.world.level.block.BedBlock;
										if (needsFloor && earth.getBlockState(at.below()).isAir()) {
											if (unsupported++ < 5) {
												log("  unsupported %s at %d,%d,%d", b.getDescriptionId(), at.getX(), at.getY(), at.getZ());
											}
										}
									}
								}
							}
						}
					}
					log("in 3 x 3 chunks of buildings: %d floating blocks, %d with nothing under them, %d door blocks in outer walls above the ground floor",
						floating, unsupported, skyDoors);
					check(floating == 0 && unsupported == 0, "nothing in the buildings floats (%d floating, %d unsupported)", floating, unsupported);
					check(skyDoors == 0, "street doors are on the ground floor only (%d higher up)", skyDoors);
				}

				// Every room of a block of flats can be walked to: in at a street door, through the doors,
				// up the stairs, on every floor.
				{
					int buildings = 0;
					int reached = 0;
					int total = 0;
					// The same buildings every run: all candidates in 13 x 13 chunks, in a fixed order.
					java.util.Map<Long, se.itssimple.realearth.terrain.BuildingPlan> candidates = new java.util.TreeMap<>();
					for (int cx = bestCx - 6; cx <= bestCx + 6; cx++) {
						for (int cz = bestCz - 6; cz <= bestCz + 6; cz++) {
							ChunkTerrain c;
							try {
								c = field.chunkBlocking(cx, cz, 120);
							} catch (Exception e) {
								continue;
							}
							for (var plan : c.plans) {
								if (plan.floors >= 3 && (plan.use == se.itssimple.realearth.terrain.BuildingPlan.Use.FLATS
									|| plan.use == se.itssimple.realearth.terrain.BuildingPlan.Use.OFFICES)
									&& plan.maxX - plan.minX <= 48 && plan.maxZ - plan.minZ <= 48) {
									candidates.put(plan.id, plan);
								}
							}
						}
					}
					for (var plan : candidates.values()) {
						if (buildings >= 12) {
							break;
						}
						// Only a building that stands on its whole footprint: where mapped buildings overlap,
						// a column is the first one's, and the other is built in part or not at all.
						int mine = 0;
						int area = 0;
						for (int x = (int) Math.floor(plan.minX); x <= (int) Math.ceil(plan.maxX); x++) {
							for (int z = (int) Math.floor(plan.minZ); z <= (int) Math.ceil(plan.maxZ); z++) {
								if (!plan.contains(x, z)) {
									continue;
								}
								area++;
								ChunkTerrain c = field.chunkIfReady(x >> 4, z >> 4);
								int k = ChunkTerrain.index(x & 15, z & 15);
								if (c != null && c.building[k] != 0 && c.plans.get(c.building[k] - 1).id == plan.id) {
									mine++;
								}
							}
						}
						if (mine < area * 0.98) {
							continue;
						}
						int[] walk = walkRooms(earth, plan);
						buildings++;
						reached += walk[0];
						total += walk[1];
						log("  %d-storey %s at %d,%d: %d of %d rooms reached (%d stairwells, %d doors)", plan.floors, plan.use, (int) plan.minX, (int) plan.minZ,
							walk[0], walk[1], plan.interior().stairs.size(), plan.interior().doors.size());
					}
					log("rooms reached in %d blocks of flats and offices: %d of %d", buildings, reached, total);
					check(buildings >= 2 && reached == total, "every room of the flats and offices can be walked to (%d of %d)", reached, total);
				}
				var villagers = earth.getEntities(net.minecraft.world.entity.EntityType.VILLAGER,
					new net.minecraft.world.phys.AABB((bestCx - 2) * 16, VerticalScale.MIN_Y, (bestCz - 2) * 16, (bestCx + 3) * 16, VerticalScale.MAX_Y, (bestCz + 3) * 16),
					e -> true);
				log("villagers in 5 x 5 chunks of buildings: %d", villagers.size());
			}
		}));

		phases.add(Map.entry("Fagersjö", () -> {
			// Fagersjö: the map merges a tile's buildings of one height into one feature; each must still be
			// planned on its own, standing on its own ground.
			{
				TerrainField field = RealEarth.terrain();
				int mx = (int) Math.floor(Mercator.sampleX(18.0623) / (Mercator.TILE_SIZE * 2));
				int my = (int) Math.floor(Mercator.sampleY(59.2465) / (Mercator.TILE_SIZE * 2));
				int count = field.map().tile(mx, my).join().buildings().size();
				check(count > 200, "merged map buildings are split into single buildings: %d in Fagersjö's tile", count);
				int fx = (int) field.projection().blockX(59.2465, 18.0623) >> 4;
				int fz = (int) field.projection().blockZ(59.2465) >> 4;
				int columns = 0;
				int high = 0;
				int worst = 0;
				for (int cx = fx - 8; cx <= fx + 8; cx++) {
					for (int cz = fz - 8; cz <= fz + 8; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								if (c.building[k] != 0) {
									int lift = c.plans.get(c.building[k] - 1).floorY - c.topSolidY[k];
									columns++;
									worst = Math.max(worst, Math.abs(lift));
									if (Math.abs(lift) > 6) {
										high++;
									}
								}
							}
						}
					}
				}
				log("Fagersjö: %d building columns, %d more than 6 blocks off their ground, worst %d", columns, high, worst);
				check(columns > 1000 && high < columns / 10, "buildings stand on their own ground: %d of %d columns more than 6 blocks off", high, columns);
			}
		}));

		phases.add(Map.entry("Paris", () -> {
			// Paris: no building stands taller than its storeys allow, in chunks or in distant terrain.
			{
				TerrainField field = RealEarth.terrain();
				int px = (int) field.projection().blockX(48.8610, 2.3420) >> 4;
				int pz = (int) field.projection().blockZ(48.8610) >> 4;
				int worst = 0;
				String worstAt = "";
				for (int cx = px - 8; cx <= px + 8; cx++) {
					for (int cz = pz - 8; cz <= pz + 8; cz++) {
						ChunkTerrain c;
						try {
							c = field.chunkBlocking(cx, cz, 120);
						} catch (Exception e) {
							continue;
						}
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								int k = ChunkTerrain.index(x, z);
								if (c.building[k] != 0) {
									int tall = c.roofY[k] - c.topSolidY[k];
									if (tall > worst) {
										worst = tall;
										var plan = c.plans.get(c.building[k] - 1);
										worstAt = String.format(Locale.ROOT, "chunk %d,%d: roof Y %d over ground Y %d, floor Y %d, %d floors x %d, rise %d",
											cx, cz, c.roofY[k], c.topSolidY[k], plan.floorY, plan.floors, plan.storey, plan.roofRise);
									}
								}
							}
						}
					}
				}
				log("tallest building column in central Paris: %d blocks (%s)", worst, worstAt);
				check(worst <= RealEarth.config().maxFloors * 6 + 20, "Paris buildings stay within %d storeys", RealEarth.config().maxFloors);
				try {
					var lod = new se.itssimple.realearth.lod.LodSampler(field).patch(px * 16 - 128, pz * 16 - 128, 64, 4);
					int lodMax = Integer.MIN_VALUE;
					for (var col : lod) {
						lodMax = Math.max(lodMax, col.groundTop());
					}
					log("distant terrain over central Paris: highest column Y %d", lodMax);
					check(lodMax < VerticalScale.SEA_LEVEL_Y + 300, "distant Paris has no towering columns (highest Y %d)", lodMax);
				} catch (Exception e) {
					check(false, "distant Paris: %s", e);
				}
			}
		}));

		phases.add(Map.entry("Dry country", () -> {
			// Dry country: the Grand Canyon's walls are banded terracotta and its floor red earth, not grass.
			{
				TerrainField field = RealEarth.terrain();
				int gx = (int) field.projection().blockX(36.0870, -112.1150) >> 4;
				int gz = (int) field.projection().blockZ(36.0870) >> 4;
				java.util.Map<String, Integer> tops = new java.util.TreeMap<>();
				int desertTops = 0;
				int banded = 0;
				int all = 0;
				for (int cx = gx - 4; cx <= gx + 4; cx++) {
					for (int cz = gz - 4; cz <= gz + 4; cz++) {
						LevelChunk c = earth.getChunk(cx, cz);
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								BlockState s = c.getBlockState(new BlockPos(x, c.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), z));
								String name = s.getBlock().getDescriptionId().replace("block.minecraft.", "");
								tops.merge(name, 1, Integer::sum);
								all++;
								if (name.contains("terracotta") || name.contains("red_sand") || name.equals("coarse_dirt")) {
									desertTops++;
								}
								if (name.contains("terracotta")) {
									banded++;
								}
							}
						}
					}
				}
				int climate = se.itssimple.realearth.climate.Koppen.get().classAt(36.087, -112.115);
				log("Grand Canyon surface (%s): %s", se.itssimple.realearth.climate.Koppen.code(climate), tops);
				check(desertTops > all / 2 && banded > all / 20, "Grand Canyon is red rock and earth (%d of %d columns, %d banded)", desertTops, all, banded);
			}

			int ox = startCx * 16 + 8;
			int oz = startCz * 16 + 8;
			LevelChunk chunk = earth.getChunk(startCx, startCz);
			int surface = RealEarth.surfaceY(earth, Heightmap.Types.WORLD_SURFACE, ox, oz);
			int floor = RealEarth.surfaceY(earth, Heightmap.Types.OCEAN_FLOOR, ox, oz);
			BlockState top = earth.getBlockState(new BlockPos(ox, floor - 1, oz));
			check(surface > VerticalScale.SEA_LEVEL_Y - 200 && surface < VerticalScale.SEA_LEVEL_Y + 200,
				"start surface Y %d (%.0f m), floor block %s, biome %s", surface,
				RealEarth.terrain().toElevation(surface, RealEarth.terrain().projection().latitudeAt(oz)),
				top, earth.getBiome(new BlockPos(ox, surface, oz)).unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
			int deepSections = 0;
			for (var section : chunk.getSections()) {
				if (section.getStates().get(0, 0, 0).is(Blocks.DEEPSLATE) && section.maybeHas(s -> !s.is(Blocks.DEEPSLATE)) == false) {
					deepSections++;
				}
			}
			log("start chunk: %d sections are uniform deepslate (stored as single values)", deepSections);
			check(earth.getBlockState(new BlockPos(ox, VerticalScale.MIN_Y, oz)).is(Blocks.BEDROCK), "bedrock at the bottom of the world");

			if (RealEarth.earthIsOverworld(server)) {
				Entity probe = EntityType.ARMOR_STAND.create(earth, EntitySpawnReason.COMMAND);
				check(PortalTravel.destination(earth, probe, BlockPos.ZERO) == null, "Earth world type: portals lead nowhere");
				BlockPos spawn = earth.getRespawnData().globalPos().pos();
				check(spawn.getY() > VerticalScale.SEA_LEVEL_Y - 40, "Earth world type: spawn on the ground at Y %d", spawn.getY());
				check(Math.abs(spawn.getX() - ox) < 200 && Math.abs(spawn.getZ() - oz) < 200,
					"Earth world type: spawn at the start point (%d, %d; start %d, %d)", spawn.getX(), spawn.getZ(), ox, oz);
				return;
			}
		}));

		phases.add(Map.entry("Portal", () -> {
			// Portal: build a lapis frame in the Overworld, light it, send an entity through, and back.
			ServerLevel overworld = server.overworld();
			BlockPos base = new BlockPos(100, RealEarth.surfaceY(overworld, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 100, 100) + 5, 100);
			for (int w = -1; w <= 2; w++) {
				for (int h = -1; h <= 3; h++) {
					boolean edge = w == -1 || w == 2 || h == -1 || h == 3;
					overworld.setBlock(base.east(w).above(h), edge ? Blocks.LAPIS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
			Optional<PortalFrame.Rect> rect = PortalFrame.find(overworld, base.above(1));
			check(rect.isPresent() && rect.get().width() == 2 && rect.get().height() == 3 && rect.get().axis() == Direction.Axis.X,
				"lapis frame recognised: %s", rect);
			rect.ifPresent(r -> PortalFrame.light(overworld, r));
			check(overworld.getBlockState(base.above(2)).is(RealEarth.PORTAL), "frame lit");

			overworld.setBlock(base.east(-1).above(1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			check(!overworld.getBlockState(base.above(2)).is(RealEarth.PORTAL), "portal collapses when its frame is broken");
			overworld.setBlock(base.east(-1).above(1), Blocks.LAPIS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			PortalFrame.find(overworld, base).ifPresent(r -> PortalFrame.light(overworld, r));

			Entity traveller = EntityType.ARMOR_STAND.create(overworld, EntitySpawnReason.COMMAND);
			traveller.setPos(base.getX() + 1.0, base.getY(), base.getZ() + 0.5);
			overworld.addFreshEntity(traveller);

			long t1 = System.nanoTime();
			TeleportTransition there = PortalTravel.destination(overworld, traveller, base);
			check(there != null && there.newLevel() == earth, "portal leads to Earth (%d ms)", (System.nanoTime() - t1) / 1_000_000);
			if (there == null) {
				return;
			}
			Entity arrived = traveller.teleport(there);
			check(arrived != null && arrived.level() == earth, "entity arrived on Earth at %s", arrived == null ? null : arrived.blockPosition());
			if (arrived == null) {
				return;
			}
			BlockPos at = arrived.blockPosition();
			check(earth.getBlockState(at).is(RealEarth.PORTAL), "a return portal stands at the arrival point");
			double elevation = RealEarth.terrain().toElevation(at.getY(), RealEarth.terrain().projection().latitudeAt(at.getZ()));
			check(Math.abs(at.getX() - (base.getX() + RealEarth.terrain().startX())) < 40 && Math.abs(at.getZ() - (base.getZ() + RealEarth.terrain().startZ())) < 40,
				"portal leads to the same distance from Earth's start point");
			log("arrived %.0f m above sea level at X %d Z %d", elevation, at.getX(), at.getZ());

			// A portal into ground nobody has loaded yet must still land on the real surface.
			Entity explorer = EntityType.ARMOR_STAND.create(overworld, EntitySpawnReason.COMMAND);
			BlockPos far = new BlockPos(6000, 70, -4000);
			TeleportTransition farTrip = PortalTravel.destination(overworld, explorer, far);
			int farX = far.getX() + RealEarth.terrain().startX();
			int farZ = far.getZ() + RealEarth.terrain().startZ();
			ChunkTerrain farTerrain = RealEarth.terrain().chunkIfReady(farX >> 4, farZ >> 4);
			int expected = farTerrain == null ? Integer.MIN_VALUE : farTerrain.topSolidY[ChunkTerrain.index(farX & 15, farZ & 15)];
			check(farTrip != null && Math.abs(farTrip.position().y - expected) < 40,
				"portal into unloaded terrain lands on the ground: Y %.0f, terrain top Y %d", farTrip == null ? Double.NaN : farTrip.position().y, expected);

			TeleportTransition back = PortalTravel.destination(earth, arrived, at);
			check(back != null && back.newLevel() == overworld && back.position().distanceTo(base.getCenter()) < 32,
				"return portal leads back to the Overworld portal: %s", back == null ? null : back.position());
		}));
		return phases;
	}
}
