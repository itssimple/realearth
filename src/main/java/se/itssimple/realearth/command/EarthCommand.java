package se.itssimple.realearth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.data.DiskCache;
import se.itssimple.realearth.world.DegradedChunks;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * /earth where              -- latitude, longitude and real elevation here, with a map link
 * /earth origin             -- where this world is anchored
 * /earth tp <lat> <lon>     -- go to a real place (permission level from the config)
 * /earth download <radius> [<lat> <lon>] -- fetch an area's data ahead of time; /earth download cancel
 * /earth repair [all]       -- regenerate chunks that were generated offline from placeholder data
 * /earth cache [clear]      -- how big the download cache is; delete it
 * /earth credits            -- where Earth's data comes from, as its licences ask
 * /earth perf [reset]       -- time spent in each stage of generating Earth, for finding what is slow
 */
public final class EarthCommand {
	private EarthCommand() {
	}

	private static PermissionCheck permission(int level) {
		return switch (level) {
			case 0 -> Commands.LEVEL_ALL;
			case 1 -> Commands.LEVEL_MODERATORS;
			case 3 -> Commands.LEVEL_ADMINS;
			case 4 -> Commands.LEVEL_OWNERS;
			default -> Commands.LEVEL_GAMEMASTERS;
		};
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, int teleportPermissionLevel) {
		dispatcher.register(Commands.literal("earth")
			.then(Commands.literal("where").executes(ctx -> where(ctx.getSource())))
			.then(Commands.literal("perf")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal("Generation time by stage, since start or reset (ms/runs): "
						+ se.itssimple.realearth.Perf.report()), false);
					return 1;
				})
				.then(Commands.literal("reset").executes(ctx -> {
					se.itssimple.realearth.Perf.reset();
					ctx.getSource().sendSuccess(() -> Component.literal("Generation timers reset."), false);
					return 1;
				})))
			.then(Commands.literal("credits").executes(ctx -> {
				ctx.getSource().sendSuccess(() -> Component.literal("Real Earth is built from open data:"), false);
				for (Component line : se.itssimple.realearth.Credits.full()) {
					ctx.getSource().sendSuccess(() -> line, false);
				}
				return 1;
			}))
			.then(Commands.literal("origin").executes(ctx -> origin(ctx.getSource())))
			.then(Commands.literal("tp")
				.requires(Commands.hasPermission(permission(teleportPermissionLevel)))
				.then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-Mercator.MAX_LATITUDE, Mercator.MAX_LATITUDE))
					.then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
						.executes(ctx -> teleport(ctx.getSource(),
							DoubleArgumentType.getDouble(ctx, "latitude"),
							DoubleArgumentType.getDouble(ctx, "longitude"))))))
			.then(Commands.literal("download")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("cancel").executes(ctx -> cancelDownload(ctx.getSource())))
				.then(Commands.argument("radius", IntegerArgumentType.integer(16, MAX_DOWNLOAD_RADIUS))
					.executes(ctx -> download(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), Double.NaN, Double.NaN))
					.then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-Mercator.MAX_LATITUDE, Mercator.MAX_LATITUDE))
						.then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
							.executes(ctx -> download(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"),
								DoubleArgumentType.getDouble(ctx, "latitude"), DoubleArgumentType.getDouble(ctx, "longitude")))))))
			.then(Commands.literal("repair")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> repair(ctx.getSource(), false))
				.then(Commands.literal("all").executes(ctx -> repair(ctx.getSource(), true))))
			.then(Commands.literal("cache")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> cache(ctx.getSource()))
				.then(Commands.literal("clear")
					.requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
					.executes(ctx -> clearCache(ctx.getSource())))));
	}

	/** 50 km: beyond that a download is better split up (see AreaDownload.MAX_TILES). */
	private static final int MAX_DOWNLOAD_RADIUS = 50_000;

	private static int download(CommandSourceStack source, int radius, double lat, double lon) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			source.sendFailure(Component.literal("Real Earth is not running."));
			return 0;
		}
		double x;
		double z;
		String where;
		if (Double.isNaN(lat)) {
			if (!RealEarth.isEarth(source.getLevel())) {
				source.sendFailure(Component.literal("Stand on Earth, or give a latitude and longitude: /earth download <radius> <latitude> <longitude>"));
				return 0;
			}
			x = source.getPosition().x;
			z = source.getPosition().z;
			double here = field.projection().latitudeAt(z);
			where = String.format(Locale.ROOT, "%d m around %.4f, %.4f", radius, here, field.projection().longitudeAt(x, here));
		} else {
			x = field.projection().blockX(lat, lon);
			z = field.projection().blockZ(lat);
			where = String.format(Locale.ROOT, "%d m around %.4f, %.4f", radius, lat, lon);
		}
		if (!AreaDownload.start(source, () -> AreaDownload.plan(field, x, z, radius), where)) {
			source.sendFailure(Component.literal("A download is already running; /earth download cancel stops it."));
			return 0;
		}
		return 1;
	}

	private static int cancelDownload(CommandSourceStack source) {
		if (!AreaDownload.cancel()) {
			source.sendFailure(Component.literal("No download is running."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Cancelling the download..."), false);
		return 1;
	}

	private static int repair(CommandSourceStack source, boolean includeChanged) {
		int count = DegradedChunks.count();
		if (count == 0) {
			source.sendSuccess(() -> Component.literal("Nothing to repair: every chunk was generated from the real data."), false);
			return 1;
		}
		if (!ChunkRepair.start(source, includeChanged)) {
			source.sendFailure(Component.literal("A repair is already running."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Fetching the data for " + count + " chunk" + (count == 1 ? "" : "s")
			+ " that were generated from placeholders...").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int cache(CommandSourceStack source) {
		DiskCache cache = DiskCache.current();
		TerrainField field = RealEarth.terrain();
		if (cache == null || field == null) {
			source.sendFailure(Component.literal("Real Earth is not running."));
			return 0;
		}
		CompletableFuture.supplyAsync(cache::measure).thenAccept(usage -> source.getServer().execute(() -> {
			StringBuilder parts = new StringBuilder();
			for (DiskCache.Part p : usage.parts()) {
				if (!parts.isEmpty()) {
					parts.append(", ");
				}
				parts.append(partName(p.name())).append(' ').append(DiskCache.megabytes(p.bytes()));
			}
			String limit = cache.limitBytes() > 0 ? " of " + DiskCache.megabytes(cache.limitBytes()) : " (no limit)";
			source.sendSuccess(() -> Component.literal("Download cache: " + DiskCache.megabytes(usage.bytes()) + limit + " in "
				+ String.format(Locale.ROOT, "%,d", usage.files()) + " files" + (parts.isEmpty() ? "" : " - " + parts) + "."), false);
			source.sendSuccess(() -> Component.literal("Elevation this session: " + field.store().stats() + ". Network: "
				+ (field.store().offline() ? "unreachable, retrying shortly" : "fine") + ".").withStyle(ChatFormatting.GRAY), false);
			int degraded = DegradedChunks.count();
			if (degraded > 0) {
				source.sendSuccess(() -> Component.literal(degraded + " chunk" + (degraded == 1 ? " was" : "s were")
					+ " generated from placeholders while offline; /earth repair regenerates them.").withStyle(ChatFormatting.GOLD), false);
			}
		}));
		return 1;
	}

	private static String partName(String folder) {
		return switch (folder) {
			case "terrarium" -> "elevation";
			case "openfreemap" -> "map water";
			case "worldcover" -> "land cover";
			default -> folder;
		};
	}

	private static int clearCache(CommandSourceStack source) {
		DiskCache cache = DiskCache.current();
		if (cache == null) {
			source.sendFailure(Component.literal("Real Earth is not running."));
			return 0;
		}
		if (AreaDownload.isRunning()) {
			source.sendFailure(Component.literal("A download is running; cancel it first."));
			return 0;
		}
		CompletableFuture.supplyAsync(cache::clear).thenAccept(freed -> source.getServer().execute(() ->
			source.sendSuccess(() -> Component.literal("Deleted " + DiskCache.megabytes(freed)
				+ " of downloaded data. Everything will be downloaded again as it is needed."), true)));
		return 1;
	}

	private static Component mapLink(double lat, double lon) {
		String url = String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=15/%.6f/%.6f", lat, lon, lat, lon);
		return Component.literal("[map]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
	}

	private static int where(CommandSourceStack source) {
		TerrainField field = RealEarth.terrain();
		if (field == null || !RealEarth.isEarth(source.getLevel())) {
			source.sendFailure(Component.literal("You are not on Earth. Step through a lapis portal lit with a Globe Banner Pattern."));
			return 0;
		}
		Vec3 p = source.getPosition();
		double lat = field.projection().latitudeAt(p.z);
		double lon = field.projection().longitudeAt(p.x, lat);
		double elevation = field.toElevation(p.y, lat);
		double metersPerBlock = 1.0 / field.projection().blocksPerMeter(lat);
		String scale = Math.abs(metersPerBlock - 1.0) < 0.005 ? "" : String.format(Locale.ROOT, ", 1 block = %.2f m here", metersPerBlock);
		var here = se.itssimple.realearth.world.Places.at(field, p.x, p.z);
		String place = here == null || here.town() == null && here.district() == null ? ""
			: (here.district() == null ? here.town() : here.town() == null ? here.district() : here.district() + ", " + here.town()) + "  -  ";
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s%.5f, %.5f  -  %.0f m above sea level%s ", place, lat, lon, elevation, scale))
			.append(mapLink(lat, lon)), false);
		return 1;
	}

	private static int origin(CommandSourceStack source) {
		TerrainField field = RealEarth.terrain();
		if (field == null) {
			return 0;
		}
		double lat = field.projection().latitudeAt(field.startZ() + 0.5);
		double lon = field.projection().longitudeAt(field.startX() + 0.5, lat);
		String map;
		if (field.projection() instanceof se.itssimple.realearth.geo.Projection.WebMercator mercator) {
			map = String.format(Locale.ROOT, "Mercator map with 0, 0 at block 0, 0; one block is one metre at %.1f%s latitude, "
				+ "smaller further from the equator and larger nearer it. ", Math.abs(mercator.scaleLatitude()), mercator.scaleLatitude() >= 0 ? "N" : "S");
		} else {
			map = String.format(Locale.ROOT, "Sinusoidal map centred on %.4f, %.4f at one block per metre; it leans far from that meridian. ",
				field.projection().originLatitude(), field.projection().originLongitude());
		}
		source.sendSuccess(() -> Component.literal(map + String.format(Locale.ROOT,
				"Players start at %.4f, %.4f (block %d, %d), and Overworld portals lead to the same distance from there. ",
				lat, lon, field.startX(), field.startZ()))
			.append(mapLink(lat, lon)), false);
		return 1;
	}

	private static int teleport(CommandSourceStack source, double lat, double lon) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		TerrainField field = RealEarth.terrain();
		ServerLevel earth = RealEarth.earthLevel(source.getServer());
		if (field == null || earth == null) {
			source.sendFailure(Component.literal("The Earth dimension is not available."));
			return 0;
		}

		int x = (int) Math.floor(field.projection().blockX(lat, lon));
		int z = (int) Math.floor(field.projection().blockZ(lat));
		if (field.projection().shear(lat, lon) > 0.5) {
			source.sendSuccess(() -> Component.literal("Note: this world uses the older sinusoidal map, and this place is far from its centre, "
				+ "so north-south lines lean here and terrain looks sheared. New worlds use a Mercator map, which has no lean anywhere.")
				.withStyle(ChatFormatting.GRAY), false);
		}
		source.sendSuccess(() -> Component.literal("Fetching elevation for " + String.format(Locale.ROOT, "%.4f, %.4f", lat, lon) + "...")
			.withStyle(ChatFormatting.GRAY), false);

		// Fetch the ground (and its neighbours, for trees) off-thread; only then load the chunk.
		List<CompletableFuture<?>> pending = new ArrayList<>();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				pending.add(field.chunk((x >> 4) + dx, (z >> 4) + dz));
			}
		}
		CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((v, error) ->
			source.getServer().execute(() -> {
				if (error != null) {
					player.sendSystemMessage(Component.literal("Could not load elevation data: " + error.getMessage()).withStyle(ChatFormatting.RED));
					return;
				}
				// On the street beside a building, not on its roof.
				int[] open = field.openGround(x, z, 24);
				int y = RealEarth.surfaceY(earth, Heightmap.Types.MOTION_BLOCKING, open[0], open[1]);
				player.teleportTo(earth, open[0] + 0.5, y, open[1] + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
				double ground = field.groundMetersAt(open[0], open[1]);
				double elevation = Double.isNaN(ground) ? field.toElevation(y, lat) : ground;
				player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "Welcome to %.4f, %.4f, %.0f m above sea level. ", lat, lon, elevation))
					.append(mapLink(lat, lon)));
			}));
		return 1;
	}
}
