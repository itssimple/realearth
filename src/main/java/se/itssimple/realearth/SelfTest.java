package se.itssimple.realearth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
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

			CompletableFuture<Void> onServer = new CompletableFuture<>();
			server.execute(() -> {
				try {
					inWorld(server);
					onServer.complete(null);
				} catch (Throwable e) {
					onServer.completeExceptionally(e);
				}
			});
			onServer.join();
		} catch (Throwable e) {
			RealEarth.LOGGER.error("[selftest] crashed", e);
			FAILURES.add("crash: " + e);
		}

		log(FAILURES.isEmpty() ? "PASS" : "FAIL (%d): %s", FAILURES.size(), FAILURES);
		server.execute(() -> server.halt(false));
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

	private static void inWorld(MinecraftServer server) {
		ServerLevel earth = RealEarth.earthLevel(server);
		check(earth != null, "Earth dimension is registered");
		if (earth == null) {
			return;
		}
		check(earth.getMinY() == VerticalScale.MIN_Y && earth.getMaxY() == VerticalScale.MAX_Y,
			"Earth spans Y %d to %d", earth.getMinY(), earth.getMaxY());

		// Everything below happens around the start point, which is where players are.
		int startCx = RealEarth.terrain().startX() >> 4;
		int startCz = RealEarth.terrain().startZ() >> 4;
		long t0 = System.nanoTime();
		int generated = 0;
		for (int cx = -2; cx <= 2; cx++) {
			for (int cz = -2; cz <= 2; cz++) {
				earth.getChunk(startCx + cx, startCz + cz);
				generated++;
			}
		}
		log("generated %d full chunks around the start in %d ms", generated, (System.nanoTime() - t0) / 1_000_000);

		// Throughput at a fresh place: terrain alone, then full chunks.
		{
			TerrainField field = RealEarth.terrain();
			int bx = (int) field.projection().blockX(62.10, 7.13) >> 4;
			int bz = (int) field.projection().blockZ(62.10) >> 4;
			field.chunk(bx, bz).join();
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
			log("perf: 100 chunk terrains in %d ms; 100 full chunks (sequential, server thread) in %d ms",
				(tb - ta) / 1_000_000, (tc - tb) / 1_000_000);
		}

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
				log("structure starts in 64 x 64 chunks at %d,%d (%d ms): %s", region[0], region[1], (System.nanoTime() - ts) / 1_000_000, counts);
				check(!counts.isEmpty(), "structures generate");
				int villages = counts.entrySet().stream().filter(e -> e.getKey().startsWith("village")).mapToInt(java.util.Map.Entry::getValue).sum();
				if (region[0] == startCx) {
					// 64 x 64 chunks of central Stockholm is about 1 km square of city.
					check(villages >= 15, "the city is covered with villages: %d in 1 km square", villages);
					check(!counts.containsKey("pillager_outpost"), "no pillager outposts in the city");
				} else {
					check(villages == 0, "no villages out at sea");
				}
			}
		}

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
		}

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
	}
}
