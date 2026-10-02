package se.itssimple.realearth.portal;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * Where a portal leads.
 *
 * Into Earth: the same X/Z, so the Overworld's origin is the Earth origin (Stockholm by default)
 * and walking 1 km east in the Overworld before building a portal puts you 1 km east of it on Earth.
 * Back out: to the Overworld portal the player came in through, remembered per player; failing that,
 * the same X/Z in the Overworld.
 *
 * A portal already within 16 blocks of the arrival point is reused, as the nether does; otherwise one
 * is built standing on the real ground.
 */
public final class PortalTravel {
	private static final int SEARCH_RADIUS = 16;
	private static final Gson GSON = new Gson();
	/** Where each traveller entered Earth from. Bounded, so thrown items and wandering mobs cannot grow it forever. */
	private static final Map<UUID, int[]> RETURN_POINTS = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, int[]> eldest) {
			return size() > 4096;
		}
	});
	private static Path storage;

	private PortalTravel() {
	}

	public static void load(Path file) {
		storage = file;
		RETURN_POINTS.clear();
		try {
			if (Files.exists(file)) {
				Map<UUID, int[]> read = GSON.fromJson(Files.readString(file), new TypeToken<Map<UUID, int[]>>() {}.getType());
				if (read != null) {
					RETURN_POINTS.putAll(read);
				}
			}
		} catch (IOException | RuntimeException e) {
			RealEarth.LOGGER.warn("Could not read portal return points from {}", file, e);
		}
	}

	private static void save() {
		if (storage == null) {
			return;
		}
		try {
			String json;
			synchronized (RETURN_POINTS) {
				json = GSON.toJson(RETURN_POINTS);
			}
			Files.writeString(storage, json);
		} catch (IOException e) {
			RealEarth.LOGGER.warn("Could not save portal return points to {}", storage, e);
		}
	}

	public static @Nullable TeleportTransition destination(ServerLevel from, Entity entity, BlockPos entry) {
		ServerLevel earth = from.getServer().getLevel(RealEarth.EARTH);
		if (earth == null || RealEarth.earthIsOverworld(from.getServer())) {
			return null;
		}

		if (from.dimension() != RealEarth.EARTH) {
			RETURN_POINTS.put(entity.getUUID(), new int[] {entry.getX(), entry.getY(), entry.getZ()});
			save();
			TerrainField field = RealEarth.terrain();
			if (field == null) {
				return null;
			}
			// Overworld positions map onto Earth relative to the start point.
			int ex = clampToBorder(entry.getX() + field.startX());
			int ez = clampToBorder(entry.getZ() + field.startZ());
			try {
				// Make sure the ground is known before the chunk is generated on the server thread.
				field.chunkBlocking(ex >> 4, ez >> 4, 45);
			} catch (Exception e) {
				if (entity instanceof ServerPlayer player) {
					player.sendSystemMessage(Component.literal("The elevation data for this place could not be downloaded yet. Try again in a moment."));
				}
				RealEarth.LOGGER.warn("Portal to Earth at {} postponed: {}", entry, e.toString());
				return null;
			}
			return arrive(earth, ex, ez, Integer.MIN_VALUE, entity);
		}

		ServerLevel overworld = from.getServer().overworld();
		int[] back = RETURN_POINTS.get(entity.getUUID());
		TerrainField field = RealEarth.terrain();
		int sx = field == null ? 0 : field.startX();
		int sz = field == null ? 0 : field.startZ();
		int x = back != null ? back[0] : clampToBorder(entry.getX() - sx);
		int z = back != null ? back[2] : clampToBorder(entry.getZ() - sz);
		return arrive(overworld, x, z, back != null ? back[1] : Integer.MIN_VALUE, entity);
	}

	private static int clampToBorder(int v) {
		return Math.max(-29_999_000, Math.min(29_999_000, v));
	}

	/** @param hintY where a portal is expected to be, besides near the ground; MIN_VALUE for none */
	private static TeleportTransition arrive(ServerLevel level, int x, int z, int hintY, Entity entity) {
		Optional<BlockPos> existing = findPortal(level, x, z, hintY);
		BlockPos bottomLeft;
		Direction.Axis axis;
		if (existing.isPresent()) {
			BlockPos p = existing.get();
			BlockState state = level.getBlockState(p);
			axis = state.getValue(EarthPortalBlock.AXIS);
			Direction left = axis == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
			while (level.getBlockState(p.below()).is(RealEarth.PORTAL)) {
				p = p.below();
			}
			while (level.getBlockState(p.relative(left)).is(RealEarth.PORTAL)) {
				p = p.relative(left);
			}
			bottomLeft = p;
		} else {
			int y = RealEarth.surfaceY(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			y = Math.max(level.getMinY() + 8, Math.min(level.getMaxY() - 8, y));
			axis = Direction.Axis.X;
			bottomLeft = PortalFrame.build(level, new BlockPos(x, y, z), axis).bottomLeft();
		}

		Vec3 target = axis == Direction.Axis.X
			? new Vec3(bottomLeft.getX() + 1.0, bottomLeft.getY(), bottomLeft.getZ() + 0.5)
			: new Vec3(bottomLeft.getX() + 0.5, bottomLeft.getY(), bottomLeft.getZ() + 1.0);
		return new TeleportTransition(level, target, Vec3.ZERO, entity.getYRot(), entity.getXRot(),
			TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
	}

	/**
	 * Nearest portal block to (x, z), searching outwards in rings, near the local ground and near
	 * hintY -- the height the player entered at, which matters for portals built underground.
	 */
	private static Optional<BlockPos> findPortal(ServerLevel level, int x, int z, int hintY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int r = 0; r <= SEARCH_RADIUS; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int ground = RealEarth.surfaceY(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
					for (int centre : hintY == Integer.MIN_VALUE ? new int[] {ground} : new int[] {hintY, ground}) {
						int top = Math.min(level.getMaxY(), centre + 24);
						int bottom = Math.max(level.getMinY(), centre - 24);
						for (int y = top; y >= bottom; y--) {
							pos.set(x + dx, y, z + dz);
							if (level.getBlockState(pos).is(RealEarth.PORTAL)) {
								return Optional.of(pos.immutable());
							}
						}
					}
				}
			}
		}
		return Optional.empty();
	}
}
