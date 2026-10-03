package se.itssimple.realearth.terrain;

import java.util.List;
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
	/** Hipped roof rising this many blocks; 0 for a flat roof with a parapet. */
	public final int roofRise;
	public final Style style;
	/** Which of the building palettes of its style it uses. */
	public final int palette;
	/**
	 * Doors: {x, z, facing} with facing the way out (0 north, 1 east, 2 south, 3 west). The first is
	 * the front door, towards the nearest street; long walls get one more each.
	 */
	public final List<int[]> doors;

	BuildingPlan(long id, List<double[]> rings, int floorY, int floors, int storey, int roofRise, Style style, int palette, List<int[]> doors) {
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
		this.style = style;
		this.palette = palette;
		this.doors = doors;
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

	/** Even-odd test of a block column's centre. */
	public boolean contains(int x, int z) {
		return contains(rings, x + 0.5, z + 0.5);
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
	static BuildingPlan of(long id, List<double[]> rings, double floorMeters, double latitude, double heightMeters, double area,
		TerrainField field, Style style, List<int[]> doors, double storeyMeters, int maxFloors) {
		double perMeter = field.projection().blocksPerMeter(latitude);
		int storey = Math.max(5, (int) Math.ceil(storeyMeters * perMeter));
		double realHeight = !Double.isNaN(heightMeters) && heightMeters > 0 ? heightMeters : area < 40 ? 3 : 6;
		// Storeys counted from the real height at about 3 m each; each built at the taller game storey.
		int floors = Math.max(1, Math.min(maxFloors, (int) Math.round(realHeight / 3.0)));
		int floorY = (int) Math.round(field.toY(floorMeters, latitude)) - 1;
		while (floors > 1 && floorY + floors * storey + 8 > VerticalScale.MAX_Y) {
			floors--;
		}
		double shortSide = Math.min(boundsX(rings), boundsZ(rings));
		boolean pitched = style != Style.DESERT && floors <= 3 && shortSide <= 18 && shortSide >= 4;
		int rise = pitched ? (int) Math.max(2, Math.min(6, Math.floor(shortSide / 2))) : 0;
		int palette = (int) ((id >>> 7) & 0xFF);
		return new BuildingPlan(id, rings, floorY, floors, storey, rise, style, palette, doors);
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
