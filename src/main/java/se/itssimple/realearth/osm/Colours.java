package se.itssimple.realearth.osm;

import java.util.Locale;
import java.util.Map;

/**
 * A map colour (OpenStreetMap's {@code building:colour}, as OpenMapTiles passes it on) as 0xRRGGBB: a hex
 * code ({@code #bd8161}, {@code #fff}) or a CSS colour name. Where a building has no colour of its own,
 * OpenMapTiles gives the colour of its material instead; those are listed here too, so a brick building
 * can be told from one painted brick red.
 */
public final class Colours {
	/** No colour given, or one this doesn't know. */
	public static final int NONE = -1;

	/** OpenMapTiles' colours for building:material, where a building has no colour of its own. */
	public static final int BRICK = 0xbd8161;
	public static final int PLASTER = 0xdadbdb;
	public static final int WOOD = 0xd48741;
	public static final int CONCRETE = 0xd3c2b0;
	public static final int METAL = 0xb7b1a6;
	public static final int STONE = 0xb4a995;
	public static final int MUD = 0x9d8b75;
	public static final int GLASS = 0x5a81a0;
	public static final int CEMENT_BLOCK = 0x6a7880;
	public static final int TIMBER_FRAMING = 0xb3b0a9;

	private static final Map<String, Integer> NAMES = Map.ofEntries(
		Map.entry("white", 0xffffff), Map.entry("snow", 0xfffafa), Map.entry("ivory", 0xfffff0), Map.entry("whitesmoke", 0xf5f5f5),
		Map.entry("linen", 0xfaf0e6), Map.entry("antiquewhite", 0xfaebd7), Map.entry("cream", 0xfffdd0), Map.entry("beige", 0xf5f5dc),
		Map.entry("cornsilk", 0xfff8dc), Map.entry("lemonchiffon", 0xfffacd), Map.entry("lightyellow", 0xffffe0), Map.entry("yellow", 0xffff00),
		Map.entry("gold", 0xffd700), Map.entry("khaki", 0xf0e68c), Map.entry("darkkhaki", 0xbdb76b), Map.entry("palegoldenrod", 0xeee8aa),
		Map.entry("goldenrod", 0xdaa520), Map.entry("darkgoldenrod", 0xb8860b), Map.entry("wheat", 0xf5deb3), Map.entry("navajowhite", 0xffdead),
		Map.entry("moccasin", 0xffe4b5), Map.entry("bisque", 0xffe4c4), Map.entry("papayawhip", 0xffefd5), Map.entry("blanchedalmond", 0xffebcd),
		Map.entry("burlywood", 0xdeb887), Map.entry("tan", 0xd2b48c), Map.entry("sandybrown", 0xf4a460), Map.entry("peru", 0xcd853f),
		Map.entry("chocolate", 0xd2691e), Map.entry("sienna", 0xa0522d), Map.entry("saddlebrown", 0x8b4513), Map.entry("brown", 0xa52a2a),
		Map.entry("maroon", 0x800000), Map.entry("darkred", 0x8b0000), Map.entry("firebrick", 0xb22222), Map.entry("red", 0xff0000),
		Map.entry("crimson", 0xdc143c), Map.entry("indianred", 0xcd5c5c), Map.entry("rosybrown", 0xbc8f8f), Map.entry("salmon", 0xfa8072),
		Map.entry("darksalmon", 0xe9967a), Map.entry("lightsalmon", 0xffa07a), Map.entry("coral", 0xff7f50), Map.entry("tomato", 0xff6347),
		Map.entry("orangered", 0xff4500), Map.entry("orange", 0xffa500), Map.entry("darkorange", 0xff8c00), Map.entry("pink", 0xffc0cb),
		Map.entry("lightpink", 0xffb6c1), Map.entry("hotpink", 0xff69b4), Map.entry("purple", 0x800080), Map.entry("violet", 0xee82ee),
		Map.entry("lavender", 0xe6e6fa), Map.entry("blue", 0x0000ff), Map.entry("navy", 0x000080), Map.entry("darkblue", 0x00008b),
		Map.entry("lightblue", 0xadd8e6), Map.entry("skyblue", 0x87ceeb), Map.entry("steelblue", 0x4682b4), Map.entry("lightsteelblue", 0xb0c4de),
		Map.entry("cadetblue", 0x5f9ea0), Map.entry("teal", 0x008080), Map.entry("turquoise", 0x40e0d0), Map.entry("cyan", 0x00ffff),
		Map.entry("green", 0x008000), Map.entry("darkgreen", 0x006400), Map.entry("lightgreen", 0x90ee90), Map.entry("olive", 0x808000),
		Map.entry("olivedrab", 0x6b8e23), Map.entry("darkolivegreen", 0x556b2f), Map.entry("seagreen", 0x2e8b57), Map.entry("lime", 0x00ff00),
		Map.entry("black", 0x000000), Map.entry("dimgray", 0x696969), Map.entry("dimgrey", 0x696969), Map.entry("gray", 0x808080),
		Map.entry("grey", 0x808080), Map.entry("darkgray", 0xa9a9a9), Map.entry("darkgrey", 0xa9a9a9), Map.entry("silver", 0xc0c0c0),
		Map.entry("lightgray", 0xd3d3d3), Map.entry("lightgrey", 0xd3d3d3), Map.entry("gainsboro", 0xdcdcdc), Map.entry("slategray", 0x708090),
		Map.entry("slategrey", 0x708090), Map.entry("darkslategray", 0x2f4f4f), Map.entry("darkslategrey", 0x2f4f4f));

	private Colours() {
	}

	/** The colour as 0xRRGGBB, or NONE. */
	public static int parse(String colour) {
		if (colour == null) {
			return NONE;
		}
		String c = colour.strip().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
		if (c.startsWith("#")) {
			String hex = c.substring(1);
			if (hex.length() == 3) {
				hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
			}
			if (hex.length() != 6) {
				return NONE;
			}
			try {
				return Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				return NONE;
			}
		}
		return NAMES.getOrDefault(c, NONE);
	}
}
