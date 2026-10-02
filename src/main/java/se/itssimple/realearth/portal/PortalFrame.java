package se.itssimple.realearth.portal;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import se.itssimple.realearth.RealEarth;

/**
 * The Earth portal's frame: a nether-portal-shaped ring of blocks (lapis by default -- the blue
 * planet), lit by using a Globe Banner Pattern on it.
 *
 * Both are tags, so a datapack can change them: realearth:portal_frame (blocks) and
 * realearth:portal_activators (items).
 */
public final class PortalFrame {
	public static final TagKey<Block> FRAME = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(RealEarth.MOD_ID, "portal_frame"));
	public static final TagKey<Item> ACTIVATORS = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(RealEarth.MOD_ID, "portal_activators"));

	private static final int MAX_SIZE = 21;

	/** Interior of a portal: bottom-left corner, which way it faces, and its size. */
	public record Rect(BlockPos bottomLeft, Direction.Axis axis, int width, int height) {
		public Direction right() {
			return axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		}

		public BlockPos at(int w, int h) {
			return bottomLeft.relative(right(), w).above(h);
		}
	}

	private PortalFrame() {
	}

	public static boolean isFrame(BlockState state) {
		return state.is(FRAME);
	}

	private static boolean isEmpty(BlockState state) {
		return state.isAir() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
	}

	/** Finds a complete, empty frame around {@code inside}, facing either way. */
	public static Optional<Rect> find(LevelAccessor level, BlockPos inside) {
		Optional<Rect> x = find(level, inside, Direction.Axis.X);
		return x.isPresent() ? x : find(level, inside, Direction.Axis.Z);
	}

	public static Optional<Rect> find(LevelAccessor level, BlockPos inside, Direction.Axis axis) {
		if (!isEmpty(level.getBlockState(inside))) {
			return Optional.empty();
		}
		Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		Direction left = right.getOpposite();

		BlockPos p = inside;
		for (int n = 0; n < MAX_SIZE && isEmpty(level.getBlockState(p.below())); n++) {
			p = p.below();
		}
		if (!isFrame(level.getBlockState(p.below()))) {
			return Optional.empty();
		}
		for (int n = 0; n < MAX_SIZE && isEmpty(level.getBlockState(p.relative(left))); n++) {
			p = p.relative(left);
		}
		if (!isFrame(level.getBlockState(p.relative(left)))) {
			return Optional.empty();
		}
		BlockPos bottomLeft = p;

		int width = 0;
		while (width <= MAX_SIZE
			&& isEmpty(level.getBlockState(bottomLeft.relative(right, width)))
			&& isFrame(level.getBlockState(bottomLeft.relative(right, width).below()))) {
			width++;
		}
		if (width < 2 || width > MAX_SIZE || !isFrame(level.getBlockState(bottomLeft.relative(right, width)))) {
			return Optional.empty();
		}

		int height = 0;
		rows:
		while (height <= MAX_SIZE) {
			if (!isFrame(level.getBlockState(bottomLeft.relative(left).above(height)))
				|| !isFrame(level.getBlockState(bottomLeft.relative(right, width).above(height)))) {
				break;
			}
			for (int w = 0; w < width; w++) {
				if (!isEmpty(level.getBlockState(bottomLeft.relative(right, w).above(height)))) {
					break rows;
				}
			}
			height++;
		}
		if (height < 3 || height > MAX_SIZE) {
			return Optional.empty();
		}
		for (int w = 0; w < width; w++) {
			if (!isFrame(level.getBlockState(bottomLeft.relative(right, w).above(height)))) {
				return Optional.empty();
			}
		}
		return Optional.of(new Rect(bottomLeft, axis, width, height));
	}

	/** Fills a found frame with portal. */
	public static void light(LevelAccessor level, Rect rect) {
		BlockState portal = RealEarth.PORTAL.defaultBlockState().setValue(EarthPortalBlock.AXIS, rect.axis());
		for (int h = 0; h < rect.height(); h++) {
			for (int w = 0; w < rect.width(); w++) {
				level.setBlock(rect.at(w, h), portal, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}

	/**
	 * Builds a lit 2x3 portal standing on {@code floor} (the first free block above the ground), with
	 * a small landing so the player does not step out into a lake or off a cliff.
	 */
	public static Rect build(LevelAccessor level, BlockPos floor, Direction.Axis axis) {
		BlockState frame = Blocks.LAPIS_BLOCK.defaultBlockState();
		BlockState landing = Blocks.STONE_BRICKS.defaultBlockState();
		Rect rect = new Rect(floor, axis, 2, 3);
		Direction right = rect.right();
		Direction front = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;

		for (int w = -1; w <= 2; w++) {
			for (int depth = -1; depth <= 1; depth++) {
				if (depth == 0) {
					continue;
				}
				BlockPos base = floor.relative(right, w).relative(front, depth);
				BlockState under = level.getBlockState(base.below());
				if (under.isAir() || !under.getFluidState().isEmpty() || under.canBeReplaced()) {
					level.setBlock(base.below(), landing, Block.UPDATE_ALL);
				}
				for (int h = 0; h < 3; h++) {
					BlockPos clear = base.above(h);
					if (!level.getBlockState(clear).is(Blocks.BEDROCK)) {
						level.setBlock(clear, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					}
				}
			}
		}

		for (int w = -1; w <= 2; w++) {
			for (int h = -1; h <= 3; h++) {
				BlockPos pos = rect.at(w, h);
				boolean edge = w == -1 || w == 2 || h == -1 || h == 3;
				level.setBlock(pos, edge ? frame : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		light(level, rect);
		return rect;
	}
}
