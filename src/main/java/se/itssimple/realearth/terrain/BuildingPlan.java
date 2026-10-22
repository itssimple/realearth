package se.itssimple.realearth.terrain;

import java.util.List;
import se.itssimple.realearth.osm.OsmDetail;
import se.itssimple.realearth.osm.OsmWater;

/**
 * One real building, as it will be built: footprint in block coordinates, ground floor level, number
 * of storeys and their height, roof, style and front door.
 *
 * Every chunk the building touches works the plan out again from the same map data and gets the same
 * answer, so each can build its own share of the building and the pieces meet. (A building that
 * crosses a map tile is split there by the map itself, and each half is planned on its own.)
 */
public final class BuildingPlan {
	public enum Style { PLAINS, TAIGA, SNOWY, DESERT, SAVANNA }

	/** What a building is used for, which decides how it is laid out inside (see {@link Interior}). */
	public enum Use {
		/** A small home: one household, its rooms off a hall. */
		HOUSE,
		/** Stairwells with flats off each landing; shops in ground-floor units where the map has them. */
		FLATS,
		/** Stairwells with open office floors. */
		OFFICES,
		/** One trade fills the building: a church, library, school, market. */
		HALL,
		/** One tall open floor: industry, storage. */
		WAREHOUSE
	}

	/**
	 * What a shop sells, or what a hall is: its furniture, its loot, and the trade of whoever works
	 * there. The last four fill a whole building when it is big enough.
	 */
	public enum Trade { BAKERY, GROCERY, BUTCHER, CLOTHES, BOOKS, HARDWARE, FOOD, PHARMACY, FLOWERS, GENERAL, OFFICE, CHURCH, LIBRARY, SCHOOL, MARKET }

	/** A shop from the map inside the building: where its point is (block coordinates), what it is, its name or null. */
	public record Shop(int x, int z, Trade trade, String name) {
	}

	/** Stable identity: map tile and index, for everything chosen "at random" in this building. */
	public final long id;
	/** Rings of interleaved x,z in world block coordinates, even-odd. */
	public final List<double[]> rings;
	public final double minX;
	public final double minZ;
	public final double maxX;
	public final double maxZ;
	/** Y of the ground floor's floor blocks. */
	public final int floorY;
	public final int floors;
	/** Blocks from one floor to the next, the floor itself included. */
	public final int storey;
	/** Roof rising this many blocks; 0 for a flat roof with a parapet. */
	public final int roofRise;
	/** The roof's shape as mapped (OsmDetail.Roof.NONE where it isn't: hipped if it rises at all). */
	public final OsmDetail.Roof roof;
	/** The roof's colour as mapped, 0xRRGGBB, or Colours.NONE. */
	public final int roofColour;
	/** Half the depth of its widest wing (see halfDepth). */
	private final double halfDepth;
	/** Direction of a gabled roof's ridge: along the footprint's longest wall. */
	private final double ridgeX;
	private final double ridgeZ;
	public final Style style;
	/** Which of the building palettes of its style it uses. */
	public final int palette;
	/** Its colour on the map, 0xRRGGBB, or Colours.NONE: its walls are made to match. */
	public final int colour;
	/** Towers and other raised parts standing on its roof. */
	public final List<Tower> towers;

	/** A raised part as the map gives it, in block coordinates: where it is and how high, in metres; and its roof. */
	record Raised(List<double[]> rings, double minMeters, double heightMeters, Detail detail) {
	}

	/**
	 * What OpenStreetMap says of a building or part beyond its outline (OsmDetail): its roof's shape,
	 * height in metres (NaN where not given) and colour, and its own colour. NONE where nothing is known.
	 */
	record Detail(OsmDetail.Roof roof, double roofMeters, int roofColour, int colour) {
		static final Detail NONE = new Detail(OsmDetail.Roof.NONE, Double.NaN, se.itssimple.realearth.osm.Colours.NONE,
			se.itssimple.realearth.osm.Colours.NONE);
	}

	/**
	 * A tower on the roof: its outline in block coordinates, the Y its top reaches, and how much of
	 * that is a pointed cap (0 for a flat top).
	 */
	public record Tower(List<double[]> rings, int topY, int capRise, double halfDepth, OsmDetail.Roof roof, int roofColour, double minX, double minZ,
		double maxX, double maxZ) {
		/** How high the cap stands over a column, in blocks above its foot: a point, a dome or an onion. */
		public double capHeight(int x, int z) {
			OsmDetail.Roof shape = roof == OsmDetail.Roof.NONE ? OsmDetail.Roof.PYRAMIDAL : roof;
			double e = edgeDistance(x, z);
			return profile(shape, capRise, e, e, halfDepth);
		}

		/** Whether the cap is curved (a dome, an onion): full blocks, no stairs. */
		public boolean curved() {
			return roof == OsmDetail.Roof.DOME || roof == OsmDetail.Roof.ONION;
		}

		public boolean contains(int x, int z) {
			return x + 1 > minX && x < maxX && z + 1 > minZ && z < maxZ && BuildingPlan.contains(rings, x + 0.5, z + 0.5);
		}

		/** Distance from a column's centre to the tower's nearest wall, in blocks. */
		public double edgeDistance(int x, int z) {
			double best = Double.MAX_VALUE;
			for (double[] r : rings) {
				int n = r.length / 2;
				for (int i = 0, j = n - 1; i < n; j = i++) {
					best = Math.min(best, segment(x + 0.5, z + 0.5, r[j * 2], r[j * 2 + 1], r[i * 2], r[i * 2 + 1]));
				}
			}
			return best;
		}
	}

	/** The tower standing on a column (the tallest, where a tower's stages stand one on another), or null. */
	public Tower towerAt(int x, int z) {
		Tower best = null;
		for (Tower t : towers) {
			if ((best == null || t.topY() > best.topY()) && t.contains(x, z)) {
				best = t;
			}
		}
		return best;
	}
	/**
	 * Doors: {x, z, facing} with facing the way out (0 north, 1 east, 2 south, 3 west). The first is
	 * the front door, towards the nearest street; long walls get one more each.
	 */
	public final List<int[]> doors;
	public final Use use;
	/** For a hall, what it is; else null. */
	public final Trade hall;
	/** Shops in ground-floor units (flats and offices); for a hall, the hall itself, for its name. */
	public final List<Shop> shops;
	private volatile Interior interior;
	/**
	 * The map tile this copy of the building is from. A building across a tile edge comes once in each
	 * tile, each copy claiming the columns of its own tile only (see Buildings.plan): this one is
	 * those columns and no more.
	 */
	private final se.itssimple.realearth.geo.Projection projection;
	private final int tileX;
	private final int tileY;

	BuildingPlan(long id, List<double[]> rings, int floorY, int floors, int storey, int roofRise, OsmDetail.Roof roof, int roofColour, Style style,
		int palette, int colour, List<Tower> towers, List<int[]> doors, Use use, Trade hall, List<Shop> shops, se.itssimple.realearth.geo.Projection projection,
		int tileX, int tileY) {
		this.id = id;
		this.rings = rings;
		double x0 = Double.MAX_VALUE;
		double z0 = Double.MAX_VALUE;
		double x1 = -Double.MAX_VALUE;
		double z1 = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 0; i < r.length; i += 2) {
				x0 = Math.min(x0, r[i]);
				x1 = Math.max(x1, r[i]);
				z0 = Math.min(z0, r[i + 1]);
				z1 = Math.max(z1, r[i + 1]);
			}
		}
		this.minX = x0;
		this.minZ = z0;
		this.maxX = x1;
		this.maxZ = z1;
		this.floorY = floorY;
		this.floors = floors;
		this.storey = storey;
		this.roofRise = roofRise;
		this.roof = roof;
		this.roofColour = roofColour;
		this.halfDepth = halfDepth(rings);
		double[] ridge = longestWall(rings);
		this.ridgeX = ridge[0];
		this.ridgeZ = ridge[1];
		this.style = style;
		this.palette = palette;
		this.colour = colour;
		this.towers = towers;
		this.doors = doors;
		this.use = use;
		this.hall = hall;
		this.shops = shops;
		this.projection = projection;
		this.tileX = tileX;
		this.tileY = tileY;
	}

	/** The building's rooms, stairs and doors, worked out the first time they are asked for. */
	public Interior interior() {
		Interior i = interior;
		if (i == null) {
			synchronized (this) {
				i = interior;
				if (i == null) {
					i = Interior.of(this);
					interior = i;
				}
			}
		}
		return i;
	}

	/** Which way the door at a column opens, or -1 when there is no door there. */
	public int doorAt(int x, int z) {
		for (int[] d : doors) {
			if (d[0] == x && d[1] == z) {
				return d[2];
			}
		}
		return -1;
	}

	/** True within two blocks of a door, where nothing may stand in the way. */
	public boolean nearDoor(int x, int z) {
		for (int[] d : doors) {
			if (Math.abs(x - d[0]) + Math.abs(z - d[1]) <= 2) {
				return true;
			}
		}
		return false;
	}

	/** Y of the top floor's ceiling: the roof starts here. */
	public int roofBaseY() {
		return floorY + floors * storey;
	}

	/** Whether a block column is this building's: its centre inside the footprint, and in this copy's map tile. */
	public boolean contains(int x, int z) {
		return contains(rings, x + 0.5, z + 0.5) && ownTile(x, z);
	}

	/** The map tile a column is in, worked out as the terrain does for the column's own samples. */
	private boolean ownTile(int x, int z) {
		if (projection == null) {
			return true;
		}
		double lat = projection.latitudeAt(z + 0.5);
		double lon = se.itssimple.realearth.geo.Projection.wrapLongitude(projection.originLongitude() + projection.longitudeOffsetAt(x + 0.5, lat));
		double perTile = se.itssimple.realearth.geo.Mercator.TILE_SIZE * (double) (1 << (se.itssimple.realearth.geo.Mercator.ZOOM - OsmWater.ZOOM));
		int tx = Math.floorMod((int) Math.floor((se.itssimple.realearth.geo.Mercator.sampleX(lon) + 0.5) / perTile), 1 << OsmWater.ZOOM);
		int ty = (int) Math.floor((se.itssimple.realearth.geo.Mercator.sampleY(lat) + 0.5) / perTile);
		return tx == tileX && ty == tileY;
	}

	static boolean contains(List<double[]> rings, double px, double pz) {
		boolean in = false;
		for (double[] r : rings) {
			int n = r.length / 2;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				double xi = r[i * 2];
				double zi = r[i * 2 + 1];
				double xj = r[j * 2];
				double zj = r[j * 2 + 1];
				if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
					in = !in;
				}
			}
		}
		return in;
	}

	/** Distance from a column's centre to the nearest edge of the footprint, in blocks. */
	public double edgeDistance(int x, int z) {
		double px = x + 0.5;
		double pz = z + 0.5;
		double best = Double.MAX_VALUE;
		for (double[] r : rings) {
			int n = r.length / 2;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				best = Math.min(best, segment(px, pz, r[j * 2], r[j * 2 + 1], r[i * 2], r[i * 2 + 1]));
			}
		}
		return best;
	}

	/** How high the roof stands over a column, in blocks above the roof base: 0 at the eaves, roofRise at most. */
	public double roofHeight(int x, int z) {
		return profile(roof, roofRise, edgeDistance(x, z), ridgeDistance(x, z), halfDepth);
	}

	/** Whether the roof is curved (a dome, an onion, a barrel): built of full blocks, no stairs. */
	public boolean curvedRoof() {
		return roof == OsmDetail.Roof.DOME || roof == OsmDetail.Roof.ONION || roof == OsmDetail.Roof.ROUND;
	}

	/**
	 * A roof's height at a point, from its distance to the nearest wall ({@code edge}) and to the
	 * nearest wall along the ridge ({@code ridge}: a gabled roof slopes only from its long sides, so
	 * its ends stand as gables), out of {@code halfDepth} at the middle.
	 */
	static double profile(OsmDetail.Roof roof, double rise, double edge, double ridge, double halfDepth) {
		double r = Math.max(0.5, halfDepth);
		return switch (roof) {
			case GABLED, MANSARD, SKILLION -> Math.min(rise, ridge);
			case ROUND -> rise * quarter(Math.min(1, ridge / r));
			case DOME -> rise * quarter(Math.min(1, edge / r));
			// A bulb: a dome to most of the height, then drawn up to a point.
			case ONION -> {
				double u = Math.min(1, edge / r);
				yield rise * (u < 0.75 ? 0.8 * quarter(u / 0.75) : 0.8 + 0.2 * (u - 0.75) / 0.25);
			}
			case PYRAMIDAL, CONE -> rise * Math.min(1, edge / r);
			default -> Math.min(rise, edge);
		};
	}

	/** A quarter circle: 0 at 0, rising steeply, 1 at 1. */
	private static double quarter(double u) {
		return Math.sqrt(Math.max(0, 1 - (1 - u) * (1 - u)));
	}

	/** Distance to the nearest wall running along the ridge (within 30 degrees of it); else to any wall. */
	private double ridgeDistance(int x, int z) {
		double px = x + 0.5;
		double pz = z + 0.5;
		double best = Double.MAX_VALUE;
		for (double[] r : rings) {
			int n = r.length / 2;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				double dx = r[i * 2] - r[j * 2];
				double dz = r[i * 2 + 1] - r[j * 2 + 1];
				double len = Math.hypot(dx, dz);
				if (len > 0 && Math.abs(dx * ridgeZ - dz * ridgeX) / len < 0.5) {
					best = Math.min(best, segment(px, pz, r[j * 2], r[j * 2 + 1], r[i * 2], r[i * 2 + 1]));
				}
			}
		}
		return best == Double.MAX_VALUE ? edgeDistance(x, z) : best;
	}

	/** The direction of a footprint's longest wall, as a unit vector. */
	private static double[] longestWall(List<double[]> rings) {
		double best = 0;
		double[] dir = {1, 0};
		double[] r = rings.get(0);
		int n = r.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double dx = r[i * 2] - r[j * 2];
			double dz = r[i * 2 + 1] - r[j * 2 + 1];
			double len = Math.hypot(dx, dz);
			if (len > best) {
				best = len;
				dir = new double[] {dx / len, dz / len};
			}
		}
		return dir;
	}

	static double segment(double px, double pz, double ax, double az, double bx, double bz) {
		double dx = bx - ax;
		double dz = bz - az;
		double len = dx * dx + dz * dz;
		double u = len > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len)) : 0;
		return Math.hypot(px - (ax + u * dx), pz - (az + u * dz));
	}

	/** A number in [0, 1) fixed for this building and the given position and purpose. */
	public double hash(int x, int y, int z, int salt) {
		long h = id * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L + z * 0x27D4EB2F165667C5L + salt * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}

	/** Builds plans for a map tile's building, or null when it can't stand (too small, in the water). */
	static BuildingPlan of(long id, List<double[]> rings, double floorMeters, double latitude, double heightMeters, int colour, Detail detail,
		List<Raised> raised, double area, TerrainField field, Style style, List<int[]> doors, double storeyMeters, int maxFloors, Use use, Trade hall, List<Shop> shops, int tileX, int tileY) {
		double perMeter = field.projection().blocksPerMeter(latitude);
		int storey = Math.max(5, (int) Math.ceil(storeyMeters * perMeter));
		double realHeight = !Double.isNaN(heightMeters) && heightMeters > 0 ? heightMeters : area < 40 ? 3 : 6;
		// A mapped roof's height is part of the building's: the walls are the rest.
		boolean roofMapped = detail.roof() != OsmDetail.Roof.NONE && detail.roof() != OsmDetail.Roof.FLAT && !Double.isNaN(detail.roofMeters());
		double wallHeight = roofMapped ? Math.max(3, realHeight - detail.roofMeters()) : realHeight;
		// Storeys counted from the real height at about 3 m each; each built at the taller game storey.
		int floors = Math.max(1, Math.min(maxFloors, (int) Math.round(wallHeight / 3.0)));
		// A church's nave and a warehouse are one tall room, as high as the building (within reason).
		if (use == Use.WAREHOUSE || hall == Trade.CHURCH) {
			storey = Math.max(storey, Math.min(16, (int) Math.round(wallHeight * perMeter)));
			floors = 1;
		}
		int floorY = (int) Math.round(field.toY(floorMeters, latitude)) - 1;
		while (floors > 1 && floorY + floors * storey + 8 > VerticalScale.MAX_Y) {
			floors--;
		}
		// Pitched roofs up to six storeys on wings up to 30 blocks deep -- measured as the farthest any
		// point inside is from a wall, so a block round a courtyard counts by its wings, not its outline.
		// Wider, taller, and in desert towns: flat roofs.
		double halfDepth = halfDepth(rings);
		boolean pitched = style != Style.DESERT && floors <= 6 && halfDepth <= 15 && halfDepth >= 2;
		int rise = pitched ? (int) Math.max(2, Math.min(8, Math.floor(halfDepth))) : 0;
		// Heights stretched as the building's are: its storeys are taller than real ones.
		double stretch = floors * storey / wallHeight;
		// A roof as mapped, where it is: its shape, and its height where given.
		OsmDetail.Roof roof = detail.roof();
		if (roof == OsmDetail.Roof.FLAT) {
			rise = 0;
		} else if (roof != OsmDetail.Roof.NONE) {
			rise = roofRise(roof, detail.roofMeters() * stretch, halfDepth, rise);
		}
		rise = Math.max(0, Math.min(rise, VerticalScale.MAX_Y - 2 - (floorY + floors * storey)));
		int palette = (int) ((id >>> 7) & 0xFF);
		int wallColour = colour != se.itssimple.realearth.osm.Colours.NONE ? colour : detail.colour();
		// Towers: on the roof at the least; a pointed cap on a narrow one, or the cap as mapped.
		int roofTop = floorY + floors * storey + rise;
		List<Tower> towers = new java.util.ArrayList<>();
		for (Raised r : raised) {
			int top = Math.min(VerticalScale.MAX_Y - 4, floorY + (int) Math.round(r.heightMeters() * stretch));
			if (top < roofTop + 2) {
				continue;
			}
			double depth = halfDepth(r.rings());
			Detail d = r.detail();
			int cap;
			if (d.roof() == OsmDetail.Roof.FLAT) {
				cap = 0;
			} else if (d.roof() != OsmDetail.Roof.NONE) {
				cap = Math.min(roofRise(d.roof(), d.roofMeters() * stretch, depth, (int) Math.round(depth)), top - roofTop - 1);
			} else {
				cap = depth <= 4 ? (int) Math.max(2, Math.min(12, Math.min(depth * 3, (top - roofTop) / 2.0))) : 0;
			}
			double x0 = Double.MAX_VALUE;
			double z0 = Double.MAX_VALUE;
			double x1 = -Double.MAX_VALUE;
			double z1 = -Double.MAX_VALUE;
			for (double[] ring : r.rings()) {
				for (int i = 0; i < ring.length; i += 2) {
					x0 = Math.min(x0, ring[i]);
					x1 = Math.max(x1, ring[i]);
					z0 = Math.min(z0, ring[i + 1]);
					z1 = Math.max(z1, ring[i + 1]);
				}
			}
			towers.add(new Tower(r.rings(), top, Math.max(0, cap), depth, d.roof(), d.roofColour(), x0, z0, x1, z1));
		}
		return new BuildingPlan(id, rings, floorY, floors, storey, rise, roof, detail.roofColour(), style, palette, wallColour, List.copyOf(towers), doors,
			use, hall, shops, field.projection(), tileX, tileY);
	}

	/**
	 * A mapped roof's rise in blocks: its height where given ({@code blocks}, NaN where not), else
	 * what its shape usually has; kept within what its footprint can carry.
	 */
	private static int roofRise(OsmDetail.Roof roof, double blocks, double halfDepth, int usual) {
		double steepest = switch (roof) {
			case PYRAMIDAL, CONE -> Math.min(32, halfDepth * 2.5);
			case DOME -> Math.min(32, halfDepth * 1.3);
			case ONION -> Math.min(36, halfDepth * 2);
			default -> Math.min(16, halfDepth * 1.5);
		};
		double fallback = switch (roof) {
			case PYRAMIDAL -> halfDepth;
			case CONE -> halfDepth * 2;
			case DOME -> halfDepth;
			case ONION -> halfDepth * 1.6;
			default -> Math.max(2, Math.min(8, usual > 0 ? usual : Math.floor(halfDepth)));
		};
		double rise = Double.isNaN(blocks) || blocks <= 0 ? fallback : blocks;
		return (int) Math.max(1, Math.round(Math.min(steepest, rise)));
	}

	/** The farthest any point inside the footprint is from its edge, in blocks: half the depth of its widest wing. */
	private static double halfDepth(List<double[]> rings) {
		double x0 = Double.MAX_VALUE;
		double x1 = -Double.MAX_VALUE;
		double z0 = Double.MAX_VALUE;
		double z1 = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 0; i < r.length; i += 2) {
				x0 = Math.min(x0, r[i]);
				x1 = Math.max(x1, r[i]);
				z0 = Math.min(z0, r[i + 1]);
				z1 = Math.max(z1, r[i + 1]);
			}
		}
		double step = Math.max(1.0, Math.sqrt((x1 - x0) * (z1 - z0) / 400.0));
		double best = 0;
		for (double z = z0 + step / 2; z < z1; z += step) {
			for (double x = x0 + step / 2; x < x1; x += step) {
				if (contains(rings, x, z)) {
					double d = Double.MAX_VALUE;
					for (double[] r : rings) {
						int n = r.length / 2;
						for (int i = 0, j = n - 1; i < n; j = i++) {
							d = Math.min(d, segment(x, z, r[j * 2], r[j * 2 + 1], r[i * 2], r[i * 2 + 1]));
						}
					}
					best = Math.max(best, d);
				}
			}
		}
		return best;
	}

	private static double boundsX(List<double[]> rings) {
		double a = Double.MAX_VALUE;
		double b = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 0; i < r.length; i += 2) {
				a = Math.min(a, r[i]);
				b = Math.max(b, r[i]);
			}
		}
		return b - a;
	}

	private static double boundsZ(List<double[]> rings) {
		double a = Double.MAX_VALUE;
		double b = -Double.MAX_VALUE;
		for (double[] r : rings) {
			for (int i = 1; i < r.length; i += 2) {
				a = Math.min(a, r[i]);
				b = Math.max(b, r[i]);
			}
		}
		return b - a;
	}

	/** Shoelace area of the rings, in square blocks (outer minus holes, whatever their order). */
	static double area(List<double[]> rings) {
		double total = 0;
		for (double[] r : rings) {
			double a = 0;
			int n = r.length / 2;
			for (int i = 0, j = n - 1; i < n; j = i++) {
				a += (r[j * 2] + r[i * 2]) * (r[j * 2 + 1] - r[i * 2 + 1]);
			}
			total += a / 2;
		}
		return Math.abs(total);
	}

	/** For OsmWater buildings: id from the map tile and index. */
	static long idOf(int tileX, int tileY, OsmWater.Building b) {
		return ((long) tileX << 40) ^ ((long) tileY << 20) ^ b.index();
	}
}
