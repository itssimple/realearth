package se.itssimple.realearth.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import se.itssimple.realearth.osm.Colours;

/**
 * The block a building's walls are made of, from its colour on the map: the nearest in colour of the
 * blocks a facade can be made of, by hue more than by lightness, and against the colour muted a little
 * (block textures are darker and greyer than the colours mappers give). Where the colour is OpenMapTiles' for a material, the material's own block:
 * brick for brick, mud brick for mud. Wood keeps the climate's timber.
 */
public final class Facades {
	/** Facade blocks and the average colour of their texture (measured from the 26.1 textures). */
	private static final Object[][] BLOCKS = {
		{Blocks.SMOOTH_QUARTZ, 0xece6df}, {Blocks.CALCITE, 0xdfe0dc}, {Blocks.WHITE_CONCRETE, 0xcfd5d6}, {Blocks.POLISHED_DIORITE, 0xc0c1c2},
		{Blocks.WHITE_TERRACOTTA, 0xd1b2a1}, {Blocks.SMOOTH_SANDSTONE, 0xdfd6aa}, {Blocks.END_STONE_BRICKS, 0xdae0a2},
		{Blocks.YELLOW_TERRACOTTA, 0xba8523}, {Blocks.ORANGE_TERRACOTTA, 0xa15325}, {Blocks.RED_TERRACOTTA, 0x8f3d2e}, {Blocks.BRICKS, 0x966153},
		{Blocks.TERRACOTTA, 0x985e43}, {Blocks.PINK_TERRACOTTA, 0xa14e4e}, {Blocks.LIGHT_GRAY_TERRACOTTA, 0x876a61}, {Blocks.MUD_BRICKS, 0x89674f},
		{Blocks.BROWN_TERRACOTTA, 0x4d3323}, {Blocks.STONE_BRICKS, 0x7a797a}, {Blocks.POLISHED_ANDESITE, 0x848685}, {Blocks.SMOOTH_STONE, 0x9e9e9e},
		{Blocks.LIGHT_GRAY_CONCRETE, 0x7d7d73}, {Blocks.DEEPSLATE_BRICKS, 0x464647}, {Blocks.POLISHED_BLACKSTONE_BRICKS, 0x302a31},
		{Blocks.CYAN_TERRACOTTA, 0x565b5b}, {Blocks.LIGHT_BLUE_TERRACOTTA, 0x716c89}, {Blocks.BLUE_TERRACOTTA, 0x4a3b5b}, {Blocks.GREEN_TERRACOTTA, 0x4c532a},
		{Blocks.LIME_TERRACOTTA, 0x677534}, {Blocks.PRISMARINE_BRICKS, 0x63ab9e}, {Blocks.TUFF_BRICKS, 0x62665f}, {Blocks.PACKED_MUD, 0x8e6a4f}, {Blocks.POLISHED_GRANITE, 0x9a6a59},
		{Blocks.PURPLE_TERRACOTTA, 0x764656}, {Blocks.GRAY_TERRACOTTA, 0x392a23}, {Blocks.BLACK_TERRACOTTA, 0x251610}};

	/** How much lightness counts against hue and saturation in matching. */
	private static final double LIGHTNESS_WEIGHT = 0.5;
	/** How much of a map colour's saturation is matched against: textures are greyer. */
	private static final double MUTE = 0.6;

	private Facades() {
	}

	/** Whether the building is glass, by its material's colour: a glass facade. */
	public static boolean glass(int colour) {
		return colour == Colours.GLASS;
	}

	/** The wall block for a colour, or null to keep the climate's (no colour, wood, timber framing). */
	public static Block wall(int colour) {
		return switch (colour) {
			case Colours.NONE, Colours.WOOD, Colours.TIMBER_FRAMING -> null;
			case Colours.BRICK -> Blocks.BRICKS;
			case Colours.CONCRETE -> Blocks.POLISHED_DIORITE;
			case Colours.METAL -> Blocks.SMOOTH_STONE;
			case Colours.STONE -> Blocks.STONE_BRICKS;
			case Colours.MUD -> Blocks.MUD_BRICKS;
			case Colours.CEMENT_BLOCK -> Blocks.POLISHED_ANDESITE;
			// Plain names, where the nearest is a shade off: Falu red, orange, brown.
			case 0xff0000 -> Blocks.RED_TERRACOTTA;
			case 0xffa500 -> Blocks.ORANGE_TERRACOTTA;
			case 0xa52a2a -> Blocks.TERRACOTTA;
			default -> nearest(colour);
		};
	}

	/** Trim for corners and the bands between storeys: white on a coloured wall, stone on a white one. */
	static Block trim(Block wall, int colour) {
		if (wall == Blocks.BRICKS || wall == Blocks.MUD_BRICKS) {
			return Blocks.STONE_BRICKS;
		}
		return lab(colour)[0] > 85 ? Blocks.STONE_BRICKS : Blocks.SMOOTH_QUARTZ;
	}

	private static Block nearest(int colour) {
		double[] want = lab(colour);
		want[1] *= MUTE;
		want[2] *= MUTE;
		Block best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Object[] candidate : BLOCKS) {
			double[] have = lab((Integer) candidate[1]);
			double dl = (want[0] - have[0]) * LIGHTNESS_WEIGHT;
			double da = want[1] - have[1];
			double db = want[2] - have[2];
			double d = dl * dl + da * da + db * db;
			if (d < bestDistance) {
				bestDistance = d;
				best = (Block) candidate[0];
			}
		}
		return best;
	}

	/** sRGB to CIE L*a*b* (D65). */
	static double[] lab(int rgb) {
		double r = linear((rgb >> 16) & 0xFF);
		double g = linear((rgb >> 8) & 0xFF);
		double b = linear(rgb & 0xFF);
		double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
		double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
		double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
		double fx = f(x);
		double fy = f(y);
		double fz = f(z);
		return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
	}

	private static double linear(int c) {
		double v = c / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}

	private static double f(double t) {
		return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
	}
}
