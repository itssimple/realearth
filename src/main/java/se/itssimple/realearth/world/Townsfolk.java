package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.terrain.ChunkTerrain;
import se.itssimple.realearth.terrain.TerrainField;

/**
 * People in the town around each player. A city has far too many beds for a villager in each, so only
 * a few move in as it is generated; this keeps the streets near a player busy as well. Every few
 * seconds, where the town round a player has fewer villagers than it has room for (one per four real
 * beds, so a village stays quiet and a city centre is busy, up to the config's most), a few come home
 * to beds out of sight; once every player is far away, those that came this way and that nobody has traded
 * with, named or leashed go again. Trade with one and it stays.
 */
public final class Townsfolk {
	/** Every this many ticks the town round each player is looked at. */
	private static final int EVERY = 100;
	/** The town round a player: this many chunks each way. */
	private static final int AROUND = 3;
	/** Room for one villager per this many beds in the town round a player. */
	private static final int BEDS_EACH = 4;
	/** At most this many come home to the town round a player each time it is looked at. */
	private static final int AT_A_TIME = 3;
	/** Nobody comes home to a bed this close to a player, in blocks: they'd appear before their eyes. */
	private static final double UNSEEN = 20;
	/** A villager that came this way goes once every player is this far away, in blocks. */
	private static final double GONE = 112;
	/** One in this many is a child. */
	private static final int CHILDREN = 5;
	/** Marks the villagers that came this way, so the ones that moved in for good are never sent off. */
	public static final String TAG = "realearth.townsfolk";

	private Townsfolk() {
	}

	/** For the server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY != 0) {
			return;
		}
		TerrainField field = RealEarth.terrain();
		ServerLevel earth = RealEarth.earthLevel(server);
		if (field == null || earth == null) {
			return;
		}
		List<ServerPlayer> players = new ArrayList<>(earth.players());
		players.removeIf(ServerPlayer::isSpectator);
		leave(earth, players);
		int most = RealEarth.config().townsfolk;
		if (most <= 0) {
			return;
		}
		for (ServerPlayer player : players) {
			for (int i = 0; i < AT_A_TIME && comeHome(earth, field, player.blockPosition(), most, players) != null; i++) {
			}
		}
	}

	/**
	 * Where the town round a spot has room for more villagers than are there, one comes home to a bed
	 * in it, out of the players' sight. Returns the villager, or null when none came.
	 *
	 * The beds are the level's own record of them (as villagers find theirs), so towns generated before
	 * a restart count too; only those in the map's buildings, not one a player has put in a house.
	 */
	public static Villager comeHome(ServerLevel level, TerrainField field, BlockPos at, int most, List<? extends Player> players) {
		int reachBlocks = AROUND * 16 + 8;
		List<BlockPos> beds = level.getPoiManager()
			.getInSquare(type -> type.is(PoiTypes.HOME), at, reachBlocks, PoiManager.Occupancy.ANY)
			.map(PoiRecord::getPos)
			.filter(pos -> inBuilding(field, pos))
			.toList();
		int room = Math.min(most, beds.size() / BEDS_EACH);
		if (room == 0) {
			return null;
		}
		double reach = (AROUND + 0.5) * 16;
		AABB town = new AABB(at.getX() - reach, level.getMinY(), at.getZ() - reach, at.getX() + reach, level.getMaxY(), at.getZ() + reach);
		int there = level.getEntitiesOfClass(Villager.class, town).size();
		if (there >= room) {
			return null;
		}
		for (int tries = 0; tries < 8; tries++) {
			BlockPos pos = beds.get(level.getRandom().nextInt(beds.size()));
			// Not in sight, not where the bed has gone, and only where entities tick: elsewhere they would
			// not be counted, and would gather unseen.
			if (near(pos, players, UNSEEN) || !level.isPositionEntityTicking(pos) || !level.getBlockState(pos).is(BlockTags.BEDS)) {
				continue;
			}
			Villager villager = EntityType.VILLAGER.create(level, EntitySpawnReason.EVENT);
			if (villager == null) {
				return null;
			}
			villager.snapTo(pos.getX() + 0.5, pos.getY() + 0.5625, pos.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
			villager.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
			if (level.getRandom().nextInt(CHILDREN) == 0) {
				villager.setAge(-24000);
			}
			villager.addTag(TAG);
			level.addFreshEntityWithPassengers(villager);
			return villager;
		}
		return null;
	}

	/**
	 * Those that came home this way and that every player is far from go again, unless someone has
	 * traded with them, named them or leashed them. Returns how many went.
	 */
	public static int leave(ServerLevel level, List<? extends Player> players) {
		int gone = 0;
		for (Villager villager : level.getEntities(EntityType.VILLAGER, v -> v.entityTags().contains(TAG))) {
			if (villager.getVillagerXp() > 0 || villager.hasCustomName() || villager.isLeashed() || villager.isTrading()
				|| villager.isPassenger() || near(villager.blockPosition(), players, GONE)) {
				continue;
			}
			villager.discard();
			gone++;
		}
		return gone;
	}

	/** Whether a block is in one of the map's buildings; where the chunk's plan isn't at hand, it is asked for. */
	private static boolean inBuilding(TerrainField field, BlockPos pos) {
		ChunkTerrain t = field.chunkIfReady(pos.getX() >> 4, pos.getZ() >> 4);
		if (t == null) {
			field.chunk(pos.getX() >> 4, pos.getZ() >> 4); // for next time
			return false;
		}
		return t.building[ChunkTerrain.index(pos.getX() & 15, pos.getZ() & 15)] != 0;
	}

	private static boolean near(BlockPos pos, List<? extends Player> players, double distance) {
		for (Player player : players) {
			double dx = player.getX() - pos.getX() - 0.5;
			double dz = player.getZ() - pos.getZ() - 0.5;
			if (dx * dx + dz * dz < distance * distance) {
				return true;
			}
		}
		return false;
	}
}
