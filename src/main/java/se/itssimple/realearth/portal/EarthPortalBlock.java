package se.itssimple.realearth.portal;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** The swirling surface inside a lit frame. Behaves like a nether portal, but leads to Earth. */
public class EarthPortalBlock extends Block implements Portal {
	public static final MapCodec<EarthPortalBlock> CODEC = simpleCodec(EarthPortalBlock::new);
	public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
	private static final Map<Direction.Axis, VoxelShape> SHAPES = Shapes.rotateHorizontalAxis(Block.column(4.0, 16.0, 0.0, 16.0));

	public EarthPortalBlock(BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X));
	}

	@Override
	public MapCodec<EarthPortalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(AXIS));
	}

	/** Collapses when anything in its own plane stops being portal or frame -- e.g. a frame block is mined. */
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
		Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		Direction.Axis axis = state.getValue(AXIS);
		boolean inPlane = direction.getAxis() == axis || direction.getAxis() == Direction.Axis.Y;
		if (inPlane && !neighbourState.is(this) && !PortalFrame.isFrame(neighbourState)) {
			return Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean isPrecise) {
		if (entity.canUsePortal(false)) {
			entity.setAsInsidePortal(this, pos);
		}
	}

	@Override
	public int getPortalTransitionTime(ServerLevel level, Entity entity) {
		if (entity instanceof Player player) {
			return Math.max(0, level.getGameRules().get(player.getAbilities().invulnerable
				? GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY
				: GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY));
		}
		return 0;
	}

	@Override
	public @Nullable TeleportTransition getPortalDestination(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos) {
		return PortalTravel.destination(currentLevel, entity, portalEntryPos);
	}

	@Override
	public Portal.Transition getLocalTransition() {
		return Portal.Transition.CONFUSION;
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(100) == 0) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.PORTAL_AMBIENT,
				SoundSource.BLOCKS, 0.5F, random.nextFloat() * 0.4F + 1.1F, false);
		}
		for (int i = 0; i < 3; i++) {
			double x = pos.getX() + random.nextDouble();
			double y = pos.getY() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.PORTAL, x, y, z,
				(random.nextFloat() - 0.5) * 0.5, (random.nextFloat() - 0.5) * 0.5, (random.nextFloat() - 0.5) * 0.5);
		}
	}
}
