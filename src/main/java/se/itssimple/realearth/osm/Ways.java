package se.itssimple.realearth.osm;

/**
 * Kinds of way drawn on Earth, from OpenMapTiles' {@code transportation} classes, in drawing
 * priority: where two overlap, the higher kind wins (a railway crosses a road, a road a path).
 */
public final class Ways {
	public static final byte NONE = 0;
	/** Footways, cycleways, bridleways, steps: dirt paths. */
	public static final byte PATH = 1;
	/** Farm and forest tracks: coarse dirt and gravel. */
	public static final byte TRACK = 2;
	/** Driveways, parking aisles, alleys. */
	public static final byte SERVICE = 3;
	/** Residential streets and tertiary roads. */
	public static final byte MINOR = 4;
	/** Primary and secondary roads, trunk roads. */
	public static final byte MAJOR = 5;
	public static final byte MOTORWAY = 6;
	/** The ballast either side of a track. */
	public static final byte RAIL_BED = 7;
	/** The track itself: one line of rail blocks. */
	public static final byte RAIL = 8;

	private Ways() {
	}

	public static boolean isRail(byte kind) {
		return kind == RAIL || kind == RAIL_BED;
	}

	public static boolean isPaved(byte kind) {
		return kind >= SERVICE && kind <= MOTORWAY;
	}

	/** The kind for an OpenMapTiles class and subclass, or NONE for ways not drawn (ferries, lifts, construction). */
	public static byte kind(String cls, String subclass) {
		if (cls == null) {
			return NONE;
		}
		return switch (cls) {
			case "motorway" -> MOTORWAY;
			case "trunk", "primary", "secondary" -> MAJOR;
			case "tertiary", "minor", "busway" -> MINOR;
			case "service", "raceway" -> SERVICE;
			case "track" -> TRACK;
			case "path" -> PATH;
			case "rail" -> RAIL;
			// Trams and light rail run on the surface; subways and monorails don't (or not on the ground).
			case "transit" -> "tram".equals(subclass) || "light_rail".equals(subclass) ? RAIL : NONE;
			default -> NONE;
		};
	}

	/** Typical width of a way, in metres. Each carriageway of a motorway is its own line in the map. */
	public static double widthMeters(byte kind, String cls) {
		return switch (kind) {
			case MOTORWAY -> 10.0;
			case MAJOR -> "secondary".equals(cls) ? 7.0 : 8.0;
			case MINOR -> "tertiary".equals(cls) ? 6.5 : 5.0;
			case SERVICE -> 3.5;
			case TRACK -> 3.0;
			case PATH -> 1.6;
			case RAIL -> 3.5;
			default -> 0.0;
		};
	}
}
