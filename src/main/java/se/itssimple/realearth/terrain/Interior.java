package se.itssimple.realearth.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a building is divided inside: stairwells, flats, rooms, shops, and the doors and stairs that join
 * them. Worked out once per building, from its plan alone, as a grid over its footprint, so every
 * chunk the building touches builds its share of the same rooms.
 *
 * <ul>
 * <li><b>Sections.</b> Flats and offices are cut into stairwell sections no longer than
 * {@link #SECTION_MAX} blocks, each with its own street door. Its core -- a landing and a turning stair
 * that climbs a storey in two flights round a half landing -- stands against the street side.</li>
 * <li><b>Flats</b> open off the landing, two to a floor (more where the section's shape makes more), and
 * are divided into rooms by straight walls, each wall with a door, so every room can be reached: a hall
 * by the front door, a living room, a kitchen, bedrooms, sometimes a bathroom.</li>
 * <li><b>Shops</b> take the ground-floor flat nearest their point on the map: one open room with its own
 * street door, walled off from the stairwell; the flats above are as on every other floor.</li>
 * <li><b>Halls</b> (church, library, school, market) and warehouses are one open room a floor, with a
 * stairwell where there is more than one floor. Houses are one home, with stairs when they have
 * room for them, else a ladder.</li>
 * </ul>
 *
 * Ground floor and upper floors are two layers of the same grid: they differ only where a shop is.
 */
public final class Interior {
	/** Outside the footprint. */
	public static final byte OUT = 0;
	/** The outer wall. */
	public static final byte SHELL = 1;
	/** A wall inside: between sections, flats or rooms, round a stairwell. */
	public static final byte WALL = 2;
	/** Floor space of a room. */
	public static final byte ROOM = 3;
	/** The stair shaft: flights, half landings, and holes in the floors above them. */
	public static final byte SHAFT = 4;
	/** A stairwell's landing, in front of the flats' doors. */
	public static final byte LANDING = 5;

	public enum Kind { HALL, LIVING, KITCHEN, BED, BATH, STUDIO, OFFICE, SHOP, HALLROOM, LANDING, STORE }

	/** Things that stand in a room. What they are made of is the painter's business. */
	public enum Item { BED, CHEST, BARREL, STOCK, FURNACE, SMOKER, CRAFTING, BOOKSHELF, JOB, FLOWER, TABLE, RUG, CAULDRON, PEW, ALTAR, CRATE }

	/** One room, on the ground floor or the floors above (or both, when they are the same). */
	public static final class Room {
		public final int id;
		public Kind kind;
		/** The flat or unit it belongs to. */
		public final int unit;
		/** For a shop or hall, its trade; else null. */
		public BuildingPlan.Trade trade;
		/** Shop name from the map, or null. */
		public String name;
		final List<Integer> cells = new ArrayList<>();

		Room(int id, Kind kind, int unit) {
			this.id = id;
			this.kind = kind;
			this.unit = unit;
		}

		public int size() {
			return cells.size();
		}
	}

	/** A door: in an inner wall, or (entrance) in the outer wall; facing is the way out, as BuildingPlan's. */
	public record Door(int x, int z, int facing, int layers, boolean entrance) {
		public boolean on(int floor) {
			return (layers & (floor == 0 ? 1 : 2)) != 0;
		}
	}

	/**
	 * A turning stair: cell (r, s) is at origin + r * (rdx, rdz) + s * (sdx, sdz). Flight A climbs along
	 * s = 0 from r = 1 to r = a; the half landing is r = a + 1; flight B climbs back along s = 1 for b
	 * steps, onto the floor above at r = a - b. The landing is the row r = 0 and the column s = 2.
	 */
	public record Stairs(int ox, int oz, int rdx, int rdz, int sdx, int sdz, int a, int b) {
		int r(int x, int z) {
			return (x - ox) * rdx + (z - oz) * rdz;
		}

		int s(int x, int z) {
			return (x - ox) * sdx + (z - oz) * sdz;
		}

		int x(int r, int s) {
			return ox + r * rdx + s * sdx;
		}

		int z(int r, int s) {
			return oz + r * rdz + s * sdz;
		}

		/** Direction index (0 north, 1 east, 2 south, 3 west) of the r axis. */
		public int rFacing() {
			return facing(rdx, rdz);
		}
	}

	/** What a stair shaft holds at one block: nothing, a step (climbing toward facing), a full block. */
	public record Step(int kind, int facing) {
		public static final int AIR = 0;
		public static final int STAIR = 1;
		public static final int SOLID = 2;
	}

	/**
	 * Something placed in a room: what, the way it faces (direction index), and a number in [0, 1) that
	 * picks among looks. A bed's foot has -1 - its head's number.
	 */
	public record Placed(Item item, int facing, double roll) {
	}

	/**
	 * Beds, chests and barrels are block entities, and a city has tens of thousands of rooms: only about
	 * one bed in this many is a real bed (villagers move into those); the rest are made up of wool and a
	 * blanket. Chests, pantries and stoves are kept rare the same way.
	 */
	public static final double REAL_BED = 0.1;

	/** Longest a stairwell section is before it is cut in two, in blocks. */
	static final int SECTION_MAX = 24;
	/** Fewest floor cells a section has to stand alone. */
	private static final int SECTION_MIN = 40;
	/** A room larger than this (cells) is divided; flats, offices, houses. */
	private static final int ROOM_MAX_FLAT = 30;
	private static final int ROOM_MAX_OFFICE = 90;
	private static final int ROOM_MAX_SCHOOL = 70;
	/** Smallest a room may be cut to, and its narrowest side. */
	private static final int ROOM_MIN = 6;
	private static final int ROOM_MIN_SIDE = 2;
	/** A house needs this many floor cells to give a stairwell room; smaller, it has a ladder. */
	private static final int HOUSE_STAIRS_MIN = 50;
	/** Longest hallway from a stairwell's landing out to its street door, in blocks. */
	private static final int HALLWAY_MAX = 6;

	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};

	public final int x0;
	public final int z0;
	public final int w;
	public final int h;
	/** Per layer (0 ground, 1 upper): what each cell is. */
	private final byte[][] code = new byte[2][];
	/** Per layer: the room of each ROOM or LANDING cell, or -1. */
	private final int[][] roomOf = new int[2][];
	public final List<Room> rooms = new ArrayList<>();
	public final List<Door> doors = new ArrayList<>();
	public final List<Stairs> stairs = new ArrayList<>();
	/** Cells with a ladder up through every floor, and the wall it hangs on (direction index). */
	final Map<Integer, Integer> ladders = new HashMap<>();
	private final Map<Long, Door> doorAt = new HashMap<>();
	private final BuildingPlan plan;
	private final Map<Long, Map<Integer, Placed>> furnished = new ConcurrentHashMap<>();
	/** The walls each unit was divided into rooms by: on a shop's ground floor, they go. */
	private final Map<Integer, List<Integer>> unitWalls = new HashMap<>();
	private int units;

	private Interior(BuildingPlan plan, int x0, int z0, int w, int h) {
		this.plan = plan;
		this.x0 = x0;
		this.z0 = z0;
		this.w = w;
		this.h = h;
		code[0] = new byte[w * h];
		code[1] = new byte[w * h];
		roomOf[0] = new int[w * h];
		roomOf[1] = new int[w * h];
		Arrays.fill(roomOf[0], -1);
		Arrays.fill(roomOf[1], -1);
	}

	// ---- Queries -------------------------------------------------------------------------------

	private int cell(int x, int z) {
		int i = x - x0;
		int j = z - z0;
		return i < 0 || j < 0 || i >= w || j >= h ? -1 : j * w + i;
	}

	private static int layer(int floor) {
		return floor == 0 ? 0 : 1;
	}

	/** What the cell at a world column is on a floor. */
	public byte code(int x, int z, int floor) {
		int c = cell(x, z);
		return c < 0 ? OUT : code[layer(floor)][c];
	}

	public Room roomAt(int x, int z, int floor) {
		int c = cell(x, z);
		int r = c < 0 ? -1 : roomOf[layer(floor)][c];
		return r < 0 ? null : rooms.get(r);
	}

	/** The door at a column on a floor, or null. */
	public Door doorAt(int x, int z, int floor) {
		Door d = doorAt.get(cellKey(x, z));
		return d != null && d.on(floor) ? d : null;
	}

	private static long cellKey(int x, int z) {
		return (long) x << 32 ^ z & 0xFFFFFFFFL;
	}

	/** The wall a ladder at a column hangs on, or -1 where there is no ladder. */
	public int ladderAt(int x, int z) {
		int c = cell(x, z);
		return c < 0 ? -1 : ladders.getOrDefault(c, -1);
	}

	/** True within two blocks of a door on that floor (outside a wall too), where nothing may stand. */
	public boolean nearDoor(int x, int z, int floor) {
		for (Door d : doors) {
			if (d.on(floor) && Math.abs(x - d.x) + Math.abs(z - d.z) <= 1 + (d.entrance ? 1 : 0)) {
				return true;
			}
		}
		return false;
	}

	/** The stairs whose shaft a column is in, or null. */
	public Stairs shaftAt(int x, int z) {
		for (Stairs s : stairs) {
			int r = s.r(x, z);
			int q = s.s(x, z);
			if (q >= 0 && q <= 1 && r >= 1 && r <= s.a + 1) {
				return s;
			}
		}
		return null;
	}

	/**
	 * The block at Y in a stair shaft column. {@code floorY}, {@code storey} and {@code floors} are the
	 * building's. Floors are slabs only where no flight below needs the headroom.
	 */
	public Step stepAt(Stairs st, int x, int z, int y, int floorY, int storey, int floors) {
		int r = st.r(x, z);
		int s = st.s(x, z);
		Step here = flight(st, r, s, y, floorY, storey, floors);
		if (here != null) {
			return here;
		}
		int rel = y - floorY;
		if (rel > 0 && rel % storey == 0 && rel / storey < floors) {
			// A floor slab, where no step stands within three blocks below: climbing from a step to the
			// next, your head passes through the third block over the one you stand on.
			if (flight(st, r, s, y - 1, floorY, storey, floors) == null && flight(st, r, s, y - 2, floorY, storey, floors) == null
				&& flight(st, r, s, y - 3, floorY, storey, floors) == null) {
				return new Step(Step.SOLID, 0);
			}
		}
		return new Step(Step.AIR, 0);
	}

	private static Step flight(Stairs st, int r, int s, int y, int floorY, int storey, int floors) {
		int rel = y - floorY;
		if (rel <= 0) {
			return null;
		}
		int f = (rel - 1) / storey;
		// Steps of the flight starting on floor f reach up to the floor above; f - 1's reach into f.
		for (int ff = Math.max(0, f - 1); ff <= f; ff++) {
			if (ff > floors - 2) {
				continue;
			}
			int base = floorY + ff * storey;
			if (s == 0 && r >= 1 && r <= st.a && y == base + r) {
				return new Step(Step.STAIR, st.rFacing());
			}
			if (r == st.a + 1 && (s == 0 || s == 1) && y == base + st.a) {
				return new Step(Step.SOLID, 0);
			}
			if (s == 1 && r <= st.a && r >= st.a - st.b + 1 && y == base + 1 + st.a + (st.a - r)) {
				return new Step(Step.STAIR, (st.rFacing() + 2) % 4);
			}
			if (s == 1 && r == st.a - st.b && r >= 1 && y == base + storey) {
				return new Step(Step.SOLID, 0);
			}
		}
		return null;
	}

	/** What stands at a column on a floor, or null. */
	public Placed placedAt(int x, int z, int floor) {
		Room room = roomAt(x, z, floor);
		if (room == null) {
			return null;
		}
		long key = (long) room.id << 8 | floor;
		Map<Integer, Placed> m = furnished.computeIfAbsent(key, k -> furnish(room, floor));
		return m.get(cell(x, z));
	}

	/** A room's floor cells, as world {x, z}. */
	public List<int[]> cellsOf(Room room) {
		List<int[]> out = new ArrayList<>();
		for (int c : room.cells) {
			out.add(new int[] {x0 + c % w, z0 + c / w});
		}
		return out;
	}

	/**
	 * A plan of one floor, a text row per block row: '#' outer wall, '+' wall, 'D' door, 'E' street
	 * door, 'S' stair shaft, 'L' landing, and each room by the last digit of its number.
	 */
	public String sketch(int floor) {
		StringBuilder b = new StringBuilder();
		int layer = layer(floor);
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int c = j * w + i;
				Door d = doorAt(x0 + i, z0 + j, floor);
				char ch = switch (code[layer][c]) {
					case SHELL -> d != null && d.entrance ? 'E' : '#';
					case WALL -> d != null ? 'D' : '+';
					case SHAFT -> 'S';
					case LANDING -> 'L';
					case ROOM -> roomOf[layer][c] < 0 ? '?' : (char) ('0' + roomOf[layer][c] % 10);
					default -> ' ';
				};
				b.append(ch);
			}
			b.append('\n');
		}
		return b.toString();
	}

	// ---- Layout --------------------------------------------------------------------------------

	static Interior of(BuildingPlan p) {
		int x0 = (int) Math.floor(p.minX) - 1;
		int z0 = (int) Math.floor(p.minZ) - 1;
		int w = (int) Math.ceil(p.maxX) + 2 - x0;
		int h = (int) Math.ceil(p.maxZ) + 2 - z0;
		Interior in = new Interior(p, x0, z0, w, h);
		boolean[] inside = new boolean[w * h];
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				inside[j * w + i] = p.contains(x0 + i, z0 + j);
			}
		}
		List<Integer> floor = new ArrayList<>();
		for (int c = 0; c < w * h; c++) {
			if (!inside[c]) {
				continue;
			}
			boolean edge = false;
			for (int d = 0; d < 4; d++) {
				int n = in.step(c, d);
				edge |= n < 0 || !inside[n];
			}
			in.set(c, edge ? SHELL : ROOM);
			if (!edge) {
				floor.add(c);
			}
		}
		if (floor.isEmpty()) {
			return in;
		}
		int front = p.doors.isEmpty() ? 2 : p.doors.get(0)[2];
		switch (p.use) {
			case FLATS, OFFICES -> in.sectioned(floor, front, p.use == BuildingPlan.Use.OFFICES ? ROOM_MAX_OFFICE : ROOM_MAX_FLAT);
			case HALL -> in.hall(floor, front, p.hall == BuildingPlan.Trade.SCHOOL ? ROOM_MAX_SCHOOL : Integer.MAX_VALUE);
			case WAREHOUSE -> in.hall(floor, front, Integer.MAX_VALUE);
			case HOUSE -> in.house(floor, front);
		}
		if (p.use != BuildingPlan.Use.FLATS && p.use != BuildingPlan.Use.OFFICES) {
			in.connectAll(front);
		}
		return in;
	}

	private void set(int c, byte v) {
		code[0][c] = v;
		code[1][c] = v;
	}

	private int step(int c, int d) {
		int i = c % w + DX[d];
		int j = c / w + DZ[d];
		return i < 0 || j < 0 || i >= w || j >= h ? -1 : j * w + i;
	}

	private int at(int x, int z) {
		return cell(x, z);
	}

	private static int facing(int dx, int dz) {
		return dz < 0 ? 0 : dx > 0 ? 1 : dz > 0 ? 2 : 3;
	}

	/** Flats or offices: sections, a core in each, units round it, rooms in the units, shops below. */
	private void sectioned(List<Integer> floor, int front, int roomMax) {
		List<List<Integer>> sections = new ArrayList<>();
		split(floor, sections);
		List<List<Integer>> annexes = new ArrayList<>();
		List<List<Integer>> allUnits = new ArrayList<>();
		for (List<Integer> section : sections) {
			Stairs st = core(section, front, true);
			if (st == null) {
				annexes.add(section);
				continue;
			}
			allUnits.addAll(unitsAround(section, st, true));
		}
		if (stairs.isEmpty()) {
			// No section has room for a stairwell: one home, the house way.
			for (int c : floor) {
				set(c, ROOM);
			}
			house(floor, front);
			return;
		}
		// A section too odd for a stairwell joins the flat next door, through a door in the wall between.
		allUnits.addAll(annexes);
		// Doors off the landings first, so no room wall is cut in front of one; then the rooms; then a
		// way in for any unit the landings don't reach.
		int[] ids = new int[allUnits.size()];
		boolean[] reached = new boolean[allUnits.size()];
		for (int u = 0; u < allUnits.size(); u++) {
			ids[u] = units++;
			reached[u] = unitDoor(allUnits.get(u)) >= 0;
		}
		for (int u = 0; u < allUnits.size(); u++) {
			rooms(allUnits.get(u), ids[u], roomMax, plan.use == BuildingPlan.Use.OFFICES ? Kind.OFFICE : null);
		}
		for (int u = 0; u < allUnits.size(); u++) {
			if (!reached[u] && !connectThroughWall(allUnits.get(u))) {
				// Its own street door, and a ladder up from it to its floors above.
				entranceFor(allUnits.get(u), front, 1);
				if (plan.floors > 1) {
					ladder(allUnits.get(u));
				}
			}
		}
		shops(allUnits, ids, front);
		connectAll(front);
	}

	/** A hall: one open room a floor (or classrooms), a stairwell when there are floors above. */
	private void hall(List<Integer> floor, int front, int roomMax) {
		Stairs st = plan.floors > 1 ? core(floor, front, true) : null;
		List<Integer> rest = new ArrayList<>();
		if (st != null) {
			rest.addAll(available(floor));
		} else {
			rest.addAll(floor);
		}
		for (int[] d : plan.doors) {
			addEntrance(d[0], d[1], d[2], 1);
		}
		int id = units++;
		for (List<Integer> part : st != null ? roomy(components(rest)) : components(rest)) {
			if (st != null && unitDoor(part) < 0 && !connectThroughWall(part) && plan.floors > 1) {
				ladder(part);
			}
			if (roomMax == Integer.MAX_VALUE) {
				Room r = newRoom(Kind.HALLROOM, id);
				r.trade = plan.hall;
				r.name = plan.shops.isEmpty() ? null : plan.shops.get(0).name();
				for (int c : part) {
					claim(r, c, 3);
				}
			} else {
				rooms(part, id, roomMax, Kind.HALLROOM);
			}
		}
	}

	/** A house: one home; a stairwell when it has floors above and room for one, else a ladder. */
	private void house(List<Integer> floor, int front) {
		Stairs st = plan.floors > 1 && floor.size() >= HOUSE_STAIRS_MIN ? core(floor, front, true) : null;
		List<Integer> rest = st != null ? available(floor) : new ArrayList<>(floor);
		if (st == null) {
			for (int[] d : plan.doors) {
				addEntrance(d[0], d[1], d[2], 1);
			}
		}
		int id = units++;
		for (List<Integer> part : st != null ? roomy(components(rest)) : components(rest)) {
			if (st != null ? unitDoor(part) < 0 && !connectThroughWall(part) : !touchesEntrance(part) && !connectThroughWall(part)) {
				entranceFor(part, front, 1);
				if (st != null && plan.floors > 1) {
					ladder(part);
				}
			}
			rooms(part, id, ROOM_MAX_FLAT, null);
		}
		if (st == null && plan.floors > 1) {
			ladder(floor);
		}
	}

	/** Cuts a region into sections along its longer side until each is at most SECTION_MAX long. */
	private void split(List<Integer> region, List<List<Integer>> out) {
		int[] box = box(region);
		int ex = box[2] - box[0] + 1;
		int ez = box[3] - box[1] + 1;
		if (Math.max(ex, ez) <= SECTION_MAX) {
			out.add(region);
			return;
		}
		boolean alongX = ex >= ez;
		int lo = alongX ? box[0] : box[1];
		int len = alongX ? ex : ez;
		// Near the middle; a few blocks either way where the middle leaves a piece too small.
		for (int off : new int[] {0, 2, -2, 4, -4, 6, -6}) {
			int cut = lo + len / 2 + off;
			List<Integer> rest = new ArrayList<>();
			List<Integer> line = new ArrayList<>();
			for (int c : region) {
				int v = alongX ? c % w : c / w;
				(v == cut ? line : rest).add(c);
			}
			List<List<Integer>> parts = components(rest);
			boolean ok = parts.size() >= 2;
			for (List<Integer> part : parts) {
				ok &= part.size() >= SECTION_MIN;
			}
			if (ok) {
				for (int c : line) {
					set(c, WALL);
				}
				for (List<Integer> part : parts) {
					split(part, out);
				}
				return;
			}
		}
		out.add(region);
	}

	/**
	 * Places a stairwell core in a region: against its street side if it can, else any outer side.
	 * Returns the stairs, or null where none fits. With {@code entrance}, a street door opens into the
	 * landing.
	 */
	private Stairs core(List<Integer> region, int front, boolean entrance) {
		int a = (plan.storey + 1) / 2;
		int b = plan.storey - a;
		java.util.Set<Integer> mine = new java.util.HashSet<>(region);
		int[] box = box(region);
		double cx = (box[0] + box[2]) / 2.0;
		double cz = (box[1] + box[3]) / 2.0;
		for (int f : new int[] {front, (front + 1) % 4, (front + 3) % 4, (front + 2) % 4}) {
			Stairs best = null;
			double bestScore = Double.MAX_VALUE;
			int fx = DX[f];
			int fz = DZ[f];
			// Two shapes: the landing row against the street (r points in), or the landing column against
			// it (s points out); each either hand.
			int[][] frames = {
				{-fx, -fz, -fz, fx}, {-fx, -fz, fz, -fx},
				{-fz, fx, fx, fz}, {fz, -fx, fx, fz}};
			int bestHall = 0;
			for (int[] fr : frames) {
				for (int c : region) {
					int ox = x0 + c % w;
					int oz = z0 + c / w;
					Stairs st = new Stairs(ox, oz, fr[0], fr[1], fr[2], fr[3], a, b);
					int hall = fits(st, mine, fr == frames[0] || fr == frames[1], f);
					if (hall < 0) {
						continue;
					}
					double mx = st.x(a / 2 + 1, 1) - x0;
					double mz = st.z(a / 2 + 1, 1) - z0;
					// Near the middle, and against the wall rather than down a hallway.
					double score = Math.hypot(mx - cx, mz - cz) + 4 * hall;
					if (score < bestScore) {
						bestScore = score;
						best = st;
						bestHall = hall;
					}
				}
			}
			if (best != null) {
				build(best, mine, best.rdx == -fx && best.rdz == -fz, entrance, f, bestHall);
				return best;
			}
		}
		return null;
	}

	/**
	 * Whether a core fits: its cells in the region, its ring in the region or walls, and on its street side
	 * a way out to the street: its door in the outer wall, or where the wall slants away from the core, a
	 * straight hallway of floor out to it. Returns the hallway's length (0 for a door in the core's own
	 * wall), or -1 where it doesn't fit. Ring corners may be outside: a slanting wall cuts them off.
	 */
	private int fits(Stairs st, java.util.Set<Integer> mine, boolean rowFront, int f) {
		int doorR = rowFront ? -1 : Math.max(1, (st.a + 1) / 2);
		int doorS = rowFront ? 1 : 3;
		for (int r = -1; r <= st.a + 2; r++) {
			for (int s = -1; s <= 3; s++) {
				int c = at(st.x(r, s), st.z(r, s));
				boolean inner = r >= 0 && r <= st.a + 1 && s >= 0 && s <= 2;
				boolean corner = (r == -1 || r == st.a + 2) && (s == -1 || s == 3);
				if (c < 0) {
					return -1;
				}
				byte v = code[1][c];
				if (inner) {
					if (!mine.contains(c) || v != ROOM) {
						return -1;
					}
				} else if (!(r == doorR && s == doorS) && !(mine.contains(c) && v == ROOM || v == SHELL || v == WALL || corner && v == OUT)) {
					return -1;
				}
			}
		}
		return hallway(st.x(doorR, doorS), st.z(doorR, doorS), f, mine);
	}

	/**
	 * From a stairwell's door cell straight out toward f: the number of floor cells before the outer
	 * wall, where the street door goes with the street beyond, or -1 where that is not within
	 * HALLWAY_MAX. The hallway's sides have to be floor of the section (they are walled) or walls.
	 */
	private int hallway(int x, int z, int f, java.util.Set<Integer> mine) {
		for (int k = 0; k <= HALLWAY_MAX; k++) {
			int c = at(x + k * DX[f], z + k * DZ[f]);
			if (c < 0) {
				return -1;
			}
			byte v = code[1][c];
			if (v == SHELL) {
				int beyond = step(c, f);
				return beyond >= 0 && code[1][beyond] == OUT ? k : -1;
			}
			if (!mine.contains(c) || v != ROOM) {
				return -1;
			}
			for (int side : new int[] {(f + 1) % 4, (f + 3) % 4}) {
				int n = step(c, side);
				byte nv = n < 0 ? OUT : code[1][n];
				if (!(mine.contains(n) && nv == ROOM || nv == SHELL || nv == WALL)) {
					return -1;
				}
			}
		}
		return -1;
	}

	private void build(Stairs st, java.util.Set<Integer> mine, boolean rowFront, boolean entrance, int f, int hall) {
		stairs.add(st);
		Room landing = newRoom(Kind.LANDING, -1);
		for (int r = -1; r <= st.a + 2; r++) {
			for (int s = -1; s <= 3; s++) {
				int c = at(st.x(r, s), st.z(r, s));
				boolean inner = r >= 0 && r <= st.a + 1 && s >= 0 && s <= 2;
				if (inner) {
					boolean shaft = s <= 1 && r >= 1;
					set(c, shaft ? SHAFT : LANDING);
					if (!shaft) {
						claim(landing, c, 3);
					}
				} else if (mine.contains(c) && code[1][c] == ROOM) {
					set(c, WALL);
				}
			}
		}
		// The divider, from the ring on to the back: the flats either side of it are two.
		if (rowFront) {
			for (int r = st.a + 3; ; r++) {
				int c = at(st.x(r, -1), st.z(r, -1));
				if (c < 0 || !mine.contains(c) || code[1][c] != ROOM) {
					break;
				}
				set(c, WALL);
			}
		} else {
			for (int s = -2; ; s--) {
				int c = at(st.x(-1, s), st.z(-1, s));
				if (c < 0 || !mine.contains(c) || code[1][c] != ROOM) {
					break;
				}
				set(c, WALL);
			}
		}
		int ex = rowFront ? st.x(-1, 1) : st.x(Math.max(1, (st.a + 1) / 2), 3);
		int ez = rowFront ? st.z(-1, 1) : st.z(Math.max(1, (st.a + 1) / 2), 3);
		// The hallway out to the street door, walled both sides: part of the landing, on every floor.
		for (int k = 0; k < hall; k++) {
			int c = at(ex + k * DX[f], ez + k * DZ[f]);
			set(c, LANDING);
			claim(landing, c, 3);
			for (int side : new int[] {(f + 1) % 4, (f + 3) % 4}) {
				int n = step(c, side);
				if (n >= 0 && mine.contains(n) && code[1][n] == ROOM) {
					set(n, WALL);
				}
			}
		}
		if (entrance) {
			addEntrance(ex + hall * DX[f], ez + hall * DZ[f], f, 1);
		}
	}

	/** The cells of a region not taken by a core or walls, as connected units. */
	private List<List<Integer>> unitsAround(List<Integer> section, Stairs st, boolean divide) {
		return roomy(components(available(section)));
	}

	/**
	 * The pieces with room in them; the rest, slivers a stairwell left between itself and a slanting
	 * wall, too narrow for a room or a door, are walled up.
	 */
	private List<List<Integer>> roomy(List<List<Integer>> parts) {
		List<List<Integer>> out = new ArrayList<>();
		for (List<Integer> part : parts) {
			java.util.Set<Integer> mine = new java.util.HashSet<>(part);
			boolean wide = false;
			for (int c : part) {
				int e = step(c, 1);
				int s = step(c, 2);
				int se = s < 0 ? -1 : step(s, 1);
				if (e >= 0 && s >= 0 && se >= 0 && mine.contains(e) && mine.contains(s) && mine.contains(se)) {
					wide = true;
					break;
				}
			}
			if (wide && part.size() >= ROOM_MIN) {
				out.add(part);
			} else {
				for (int c : part) {
					set(c, WALL);
				}
			}
		}
		return out;
	}

	private List<Integer> available(List<Integer> region) {
		List<Integer> out = new ArrayList<>();
		for (int c : region) {
			if (code[1][c] == ROOM && roomOf[1][c] < 0) {
				out.add(c);
			}
		}
		return out;
	}

	/** A door from a landing into a unit: a wall cell between them, framed by walls. Returns it, or -1. */
	private int unitDoor(List<Integer> unit) {
		java.util.Set<Integer> mine = new java.util.HashSet<>(unit);
		int best = -1;
		for (int c : unit) {
			if (code[1][c] != ROOM) {
				continue;
			}
			for (int d = 0; d < 4; d++) {
				int wall = step(c, d);
				int beyond = wall < 0 ? -1 : step(wall, d);
				if (beyond < 0 || code[1][wall] != WALL || code[1][beyond] != LANDING || !framed(wall, d)) {
					continue;
				}
				if (best < 0 || wall < best) {
					best = wall;
					bestDir = d;
				}
			}
		}
		if (best >= 0) {
			addDoor(best, bestDir, 3, false);
		}
		return best;
	}

	private int bestDir;

	/** A wall cell whose neighbours across the way through are walls (or the outer wall): a proper door frame. */
	private boolean framed(int c, int through) {
		for (int side : new int[] {(through + 1) % 4, (through + 3) % 4}) {
			int n = step(c, side);
			if (n < 0 || code[1][n] != WALL && code[1][n] != SHELL) {
				return false;
			}
		}
		return true;
	}

	/** Joins a unit with no way in to a neighbour that has one, by a door in the wall between. */
	private boolean connectThroughWall(List<Integer> unit) {
		java.util.Set<Integer> mine = new java.util.HashSet<>(unit);
		for (int c : unit) {
			if (code[1][c] != ROOM) {
				continue;
			}
			for (int d = 0; d < 4; d++) {
				int wall = step(c, d);
				int beyond = wall < 0 ? -1 : step(wall, d);
				if (beyond < 0 || code[1][wall] != WALL || mine.contains(beyond) || !framed(wall, d)) {
					continue;
				}
				byte v = code[1][beyond];
				if (v == ROOM || v == LANDING) {
					addDoor(wall, d, 3, false);
					return true;
				}
			}
		}
		return false;
	}

	private boolean touchesEntrance(List<Integer> unit) {
		java.util.Set<Integer> mine = new java.util.HashSet<>(unit);
		for (Door d : doors) {
			if (d.entrance) {
				int c = at(d.x, d.z);
				int inner = c < 0 ? -1 : step(c, (d.facing + 2) % 4);
				if (inner >= 0 && mine.contains(inner)) {
					return true;
				}
			}
		}
		return false;
	}

	/** A street door into a unit, on the side nearest the street if it can. */
	private void entranceFor(List<Integer> unit, int front, int layers) {
		int best = -1;
		int bestDir = 0;
		int bestScore = Integer.MAX_VALUE;
		for (int c : unit) {
			if (code[1][c] != ROOM) {
				continue;
			}
			for (int d = 0; d < 4; d++) {
				int wall = step(c, d);
				int beyond = wall < 0 ? -1 : step(wall, d);
				if (beyond < 0 || code[1][wall] != SHELL || code[1][beyond] != OUT || !framed(wall, d)) {
					continue;
				}
				int score = (d == front ? 0 : d == (front + 2) % 4 ? 2000 : 1000) + wall % 997;
				if (score < bestScore) {
					bestScore = score;
					best = wall;
					bestDir = d;
				}
			}
		}
		if (best >= 0) {
			addEntrance(x0 + best % w, z0 + best / w, bestDir, layers);
		}
	}

	private void addEntrance(int x, int z, int facing, int layers) {
		int c = at(x, z);
		if (c < 0 || doorAt.containsKey(cellKey(x, z))) {
			return;
		}
		// Only in the outer wall, with the street the other side: a planned door can fall where this
		// copy of the building doesn't reach, or inside it.
		int beyond = at(x + DX[facing], z + DZ[facing]);
		if (code[1][c] != SHELL || beyond < 0 || code[1][beyond] != OUT) {
			return;
		}
		Door d = new Door(x, z, facing, layers, true);
		doors.add(d);
		doorAt.put(cellKey(x, z), d);
	}

	/** A door through wall cell c, opening toward direction d (facing the way out of the room it leads from). */
	private void addDoor(int c, int d, int layers, boolean entrance) {
		int x = x0 + c % w;
		int z = z0 + c / w;
		Door old = doorAt.get(cellKey(x, z));
		if (old != null) {
			return;
		}
		// Way out: from the room side (opposite d) toward d.
		Door door = new Door(x, z, d, layers, entrance);
		doors.add(door);
		doorAt.put(cellKey(x, z), door);
	}

	/**
	 * Divides a unit into rooms by straight walls, each with a door, and names them: the room by the
	 * way in is the hall, then the living room, kitchen, bedrooms and bathroom by size.
	 */
	private void rooms(List<Integer> unit, int id, int roomMax, Kind only) {
		List<List<Integer>> leaves = new ArrayList<>();
		List<Integer> walls = new ArrayList<>();
		divide(unit, roomMax, leaves, walls);
		unitWalls.put(id, walls);
		List<Room> made = new ArrayList<>();
		for (List<Integer> leaf : leaves) {
			Room r = newRoom(only != null ? only : Kind.BED, id);
			if (only == Kind.HALLROOM) {
				r.trade = plan.hall;
			}
			for (int c : leaf) {
				claim(r, c, 3);
			}
			made.add(r);
		}
		if (only != null) {
			return;
		}
		// Which room the way in opens into.
		Room hall = null;
		for (Door d : doors) {
			int c = at(d.x, d.z);
			for (int dir = 0; dir < 4 && hall == null && c >= 0; dir++) {
				int n = step(c, dir);
				if (n >= 0 && roomOf[1][n] >= 0 && made.contains(rooms.get(roomOf[1][n])) && (dir == (d.facing + 2) % 4 || dir == d.facing)) {
					hall = rooms.get(roomOf[1][n]);
				}
			}
		}
		if (made.size() == 1) {
			made.get(0).kind = Kind.STUDIO;
			return;
		}
		List<Room> rest = new ArrayList<>(made);
		if (hall == null) {
			hall = made.get(0);
		}
		rest.remove(hall);
		rest.sort((p, q) -> q.size() != p.size() ? q.size() - p.size() : p.id - q.id);
		if (made.size() == 2) {
			hall.kind = Kind.KITCHEN;
			rest.get(0).kind = Kind.BED;
			return;
		}
		hall.kind = made.size() >= 4 ? Kind.HALL : Kind.KITCHEN;
		rest.get(0).kind = Kind.LIVING;
		int next = 1;
		if (hall.kind != Kind.KITCHEN && next < rest.size()) {
			rest.get(next++).kind = Kind.KITCHEN;
		}
		Room smallest = rest.get(rest.size() - 1);
		if (rest.size() - next >= 2 && smallest.size() <= 12) {
			smallest.kind = Kind.BATH;
		}
		for (int i = next; i < rest.size(); i++) {
			if (rest.get(i).kind != Kind.BATH) {
				rest.get(i).kind = Kind.BED;
			}
		}
	}

	/** Recursively halves a region by a wall across its longer side, with a door in each wall. */
	private void divide(List<Integer> region, int roomMax, List<List<Integer>> out, List<Integer> walls) {
		if (region.size() <= roomMax) {
			out.add(region);
			return;
		}
		int[] box = box(region);
		int ex = box[2] - box[0] + 1;
		int ez = box[3] - box[1] + 1;
		boolean alongX = ex >= ez;
		int lo = alongX ? box[0] : box[1];
		int len = alongX ? ex : ez;
		if (len < 2 * ROOM_MIN_SIDE + 1) {
			out.add(region);
			return;
		}
		for (int off : new int[] {0, 1, -1, 2, -2, 3, -3}) {
			int cut = lo + len / 2 + off;
			if (cut - lo < ROOM_MIN_SIDE || lo + len - 1 - cut < ROOM_MIN_SIDE) {
				continue;
			}
			List<Integer> rest = new ArrayList<>();
			List<Integer> line = new ArrayList<>();
			for (int c : region) {
				int v = alongX ? c % w : c / w;
				(v == cut ? line : rest).add(c);
			}
			List<List<Integer>> parts = components(rest);
			boolean ok = parts.size() >= 2;
			for (List<Integer> part : parts) {
				ok &= part.size() >= ROOM_MIN;
			}
			// Never across the way in through a door.
			for (int c : line) {
				for (int d = 0; d < 4 && ok; d++) {
					int n = step(c, d);
					ok = n < 0 || doorAt.get(cellKey(x0 + n % w, z0 + n / w)) == null;
				}
			}
			if (!ok) {
				continue;
			}
			for (int c : line) {
				set(c, WALL);
			}
			walls.addAll(line);
			for (List<Integer> part : parts) {
				divide(part, roomMax, out, walls);
			}
			// A door in the cut for every pair of parts it separates, where both sides are floor.
			doorsIn(line, alongX);
			return;
		}
		out.add(region);
	}

	/** Doors through a dividing wall: one for each stretch of it with floor on both sides. */
	private void doorsIn(List<Integer> line, boolean alongX) {
		int across = alongX ? 1 : 2; // east-west cut walls run along z: you go through them east
		int back = (across + 2) % 4;
		List<Integer> sorted = new ArrayList<>(line);
		sorted.sort(null);
		int along = alongX ? w : 1;
		List<Integer> stretch = new ArrayList<>();
		for (int i = 0; i <= sorted.size(); i++) {
			int c = i < sorted.size() ? sorted.get(i) : -1;
			boolean open = c >= 0 && isFloor(step(c, across)) && isFloor(step(c, back));
			boolean joined = !stretch.isEmpty() && c == stretch.get(stretch.size() - 1) + along;
			if (!stretch.isEmpty() && (!open || !joined)) {
				// The framed cell nearest the stretch's middle; else the middle itself.
				int mid = stretch.size() / 2;
				int door = stretch.get(mid);
				for (int k = 0; k <= stretch.size(); k++) {
					int a = mid - k;
					int b = mid + k;
					if (a >= 0 && framed(stretch.get(a), across)) {
						door = stretch.get(a);
						break;
					}
					if (b < stretch.size() && framed(stretch.get(b), across)) {
						door = stretch.get(b);
						break;
					}
				}
				addDoor(door, across, 3, false);
				stretch.clear();
			}
			if (open) {
				stretch.add(c);
			}
		}
	}

	private boolean isFloor(int c) {
		return c >= 0 && code[1][c] == ROOM;
	}

	/**
	 * Makes every room reachable. Flood from the landings (which the stairs join floor to floor) and,
	 * on the ground floor, from the street doors, through every door; a part not reached gets a door
	 * through its wall into a reached room, until all are joined. A part with no reached neighbour at
	 * all gets a street door, and a ladder up to its floors above.
	 */
	private void connectAll(int front) {
		for (int layer = 1; layer >= 0; layer--) {
			for (int guard = 0; guard < 64; guard++) {
				boolean[] reached = reachedCells(layer);
				List<Integer> left = new ArrayList<>();
				for (int c = 0; c < w * h; c++) {
					if (!reached[c] && (code[layer][c] == ROOM || code[layer][c] == LANDING)) {
						left.add(c);
					}
				}
				if (left.isEmpty()) {
					break;
				}
				boolean joined = false;
				for (List<Integer> part : components(left)) {
					if (joinToReached(part, layer, reached)) {
						joined = true;
						break; // flood again: what it opened onto may reach more
					}
				}
				if (!joined) {
					// Nothing reached next door: in from the street, and up by ladder.
					List<Integer> part = components(left).get(0);
					int before = doors.size();
					entranceFor(part, front, 1);
					if (plan.floors > 1) {
						ladder(part);
					}
					if (doors.size() == before) {
						break; // no outer wall to put a door in: leave it
					}
				}
			}
		}
	}

	/** The floor cells of a layer reachable from its landings (and, on the ground floor, its street doors). */
	private boolean[] reachedCells(int layer) {
		int floor = layer == 0 ? 0 : 1;
		boolean[] seen = new boolean[w * h];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int c = 0; c < w * h; c++) {
			if (code[layer][c] == LANDING || ladders.containsKey(c) && layer == 1) {
				seen[c] = true;
				queue.add(c);
			}
		}
		if (layer == 0) {
			for (Door d : doors) {
				if (d.entrance && d.on(0)) {
					int c = at(d.x, d.z);
					int in = c < 0 ? -1 : step(c, (d.facing + 2) % 4);
					if (in >= 0 && !seen[in]) {
						seen[in] = true;
						queue.add(in);
					}
				}
			}
		}
		while (!queue.isEmpty()) {
			int c = queue.poll();
			for (int d = 0; d < 4; d++) {
				int n = step(c, d);
				if (n < 0 || seen[n]) {
					continue;
				}
				byte v = code[layer][n];
				if (v == ROOM || v == LANDING) {
					seen[n] = true;
					queue.add(n);
				} else if (v == WALL && doorAt(x0 + n % w, z0 + n / w, floor) != null) {
					// Through the door to the other side.
					int beyond = step(n, d);
					if (beyond >= 0 && !seen[beyond] && (code[layer][beyond] == ROOM || code[layer][beyond] == LANDING)) {
						seen[n] = true;
						seen[beyond] = true;
						queue.add(beyond);
					}
				}
			}
		}
		return seen;
	}

	/** A door from a part not reached into a reached room or landing, through a wall between; true when made. */
	private boolean joinToReached(List<Integer> part, int layer, boolean[] reached) {
		int fallback = -1;
		int fallbackDir = 0;
		for (int c : part) {
			for (int d = 0; d < 4; d++) {
				int wall = step(c, d);
				int beyond = wall < 0 ? -1 : step(wall, d);
				if (beyond < 0 || code[layer][wall] != WALL || !reached[beyond] || code[layer][beyond] != ROOM && code[layer][beyond] != LANDING) {
					continue;
				}
				if (doorAt.containsKey(cellKey(x0 + wall % w, z0 + wall / w))) {
					continue;
				}
				if (framed(wall, d)) {
					addDoor(wall, d, layer == 1 ? 3 : 1, false);
					return true;
				}
				if (fallback < 0) {
					fallback = wall;
					fallbackDir = d;
				}
			}
		}
		if (fallback >= 0) {
			addDoor(fallback, fallbackDir, layer == 1 ? 3 : 1, false);
			return true;
		}
		return false;
	}

	/** Shops: each takes the ground floor of the unit nearest its point, as one room with its own street door. */
	private void shops(List<List<Integer>> allUnits, int[] ids, int front) {
		java.util.Set<Integer> taken = new java.util.HashSet<>();
		for (BuildingPlan.Shop shop : plan.shops) {
			int best = -1;
			double bestDist = Double.MAX_VALUE;
			for (int u = 0; u < allUnits.size(); u++) {
				if (taken.contains(u) || !touchesShell(allUnits.get(u))) {
					continue;
				}
				for (int c : allUnits.get(u)) {
					double d = Math.hypot(x0 + c % w - shop.x(), z0 + c / w - shop.z());
					if (d < bestDist) {
						bestDist = d;
						best = u;
					}
				}
			}
			if (best < 0) {
				continue;
			}
			taken.add(best);
			List<Integer> unit = allUnits.get(best);
			java.util.Set<Integer> mine = new java.util.HashSet<>(unit);
			Room r = newRoom(Kind.SHOP, units++);
			r.trade = shop.trade();
			r.name = shop.name();
			// On the ground floor the unit's inner walls and doors go: one open room.
			for (int c : unit) {
				roomOf[0][c] = -1;
			}
			List<Integer> walls = unitWalls.getOrDefault(ids[best], List.of());
			mine.addAll(walls);
			for (int c : walls) {
				code[0][c] = ROOM;
			}
			for (int c : unit) {
				if (code[1][c] == ROOM || walls.contains(c)) {
					claim(r, c, 1);
				}
			}
			// Its door to the landing is upstairs only, and so are the doors between its rooms (their
			// walls are gone down here); down here, a street door. A door through to another unit
			// stays: that unit may have no other way in.
			for (int i = 0; i < doors.size(); i++) {
				Door d = doors.get(i);
				int c = at(d.x, d.z);
				if (c < 0 || d.entrance) {
					continue;
				}
				int a = step(c, d.facing);
				int b = step(c, (d.facing + 2) % 4);
				boolean ownSide = a >= 0 && mine.contains(a) || b >= 0 && mine.contains(b);
				boolean otherSide = a >= 0 && !mine.contains(a) && code[1][a] != LANDING || b >= 0 && !mine.contains(b) && code[1][b] != LANDING;
				if (ownSide && !otherSide) {
					Door upper = new Door(d.x, d.z, d.facing, d.layers & 2, false);
					doors.set(i, upper);
					doorAt.put(cellKey(d.x, d.z), upper);
				}
			}
			entranceFor(unit, front, 1);
		}
	}

	private boolean touchesShell(List<Integer> unit) {
		for (int c : unit) {
			for (int d = 0; d < 4; d++) {
				int n = step(c, d);
				if (n >= 0 && code[1][n] == SHELL) {
					return true;
				}
			}
		}
		return false;
	}

	/** A ladder through all floors of a house too small for stairs: against an outer wall, away from doors. */
	private void ladder(List<Integer> floor) {
		for (int c : floor) {
			for (int d = 0; d < 4; d++) {
				int n = step(c, d);
				if (n >= 0 && code[1][n] == SHELL && code[0][c] == ROOM && code[1][c] == ROOM && !nearDoor(x0 + c % w, z0 + c / w, 0)
					&& !nearDoor(x0 + c % w, z0 + c / w, 1)) {
					ladders.put(c, d);
					return;
				}
			}
		}
	}

	private Room newRoom(Kind kind, int unit) {
		Room r = new Room(rooms.size(), kind, unit);
		rooms.add(r);
		return r;
	}

	private void claim(Room r, int c, int layers) {
		if ((layers & 1) != 0) {
			roomOf[0][c] = r.id;
		}
		if ((layers & 2) != 0) {
			roomOf[1][c] = r.id;
		}
		r.cells.add(c);
	}

	private int[] box(List<Integer> region) {
		int i0 = Integer.MAX_VALUE;
		int j0 = Integer.MAX_VALUE;
		int i1 = Integer.MIN_VALUE;
		int j1 = Integer.MIN_VALUE;
		for (int c : region) {
			i0 = Math.min(i0, c % w);
			i1 = Math.max(i1, c % w);
			j0 = Math.min(j0, c / w);
			j1 = Math.max(j1, c / w);
		}
		return new int[] {i0, j0, i1, j1};
	}

	/** Connected pieces of a set of cells, each in a fixed order. */
	private List<List<Integer>> components(List<Integer> cells) {
		java.util.Set<Integer> left = new java.util.TreeSet<>(cells);
		List<List<Integer>> out = new ArrayList<>();
		while (!left.isEmpty()) {
			int start = ((java.util.TreeSet<Integer>) left).first();
			left.remove(start);
			List<Integer> part = new ArrayList<>();
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				int c = queue.poll();
				part.add(c);
				for (int d = 0; d < 4; d++) {
					int n = step(c, d);
					if (n >= 0 && left.remove(n)) {
						queue.add(n);
					}
				}
			}
			part.sort(null);
			out.add(part);
		}
		return out;
	}

	// ---- Furniture -----------------------------------------------------------------------------

	/**
	 * What stands where in one room on one floor: things against the walls (a bed in every bedroom,
	 * a stove and a pantry in every kitchen), tables and rugs in the middle, shop goods in rows.
	 */
	private Map<Integer, Placed> furnish(Room room, int floor) {
		int layer = layer(floor);
		Map<Integer, Placed> out = new HashMap<>();
		List<int[]> wallSpots = new ArrayList<>();
		List<Integer> middle = new ArrayList<>();
		for (int c : room.cells) {
			if (roomOf[layer][c] != room.id || code[layer][c] != ROOM && code[layer][c] != LANDING) {
				continue;
			}
			int x = x0 + c % w;
			int z = z0 + c / w;
			if (nearDoor(x, z, floor) || ladders.containsKey(c)) {
				continue;
			}
			int side = -1;
			for (int d = 0; d < 4 && side < 0; d++) {
				int n = step(c, d);
				byte v = n < 0 ? SHELL : code[layer][n];
				if ((v == WALL || v == SHELL) && doorAt(x0 + n % w, z0 + n / w, floor) == null) {
					side = d;
				}
			}
			if (side >= 0) {
				wallSpots.add(new int[] {c, side});
			} else {
				middle.add(c);
			}
		}
		// A fixed shuffle for this room and floor.
		wallSpots.sort((p, q) -> Double.compare(plan.hash(p[0], floor, room.id, 31), plan.hash(q[0], floor, room.id, 31)));
		middle.sort((p, q) -> Double.compare(plan.hash(p, floor, room.id, 32), plan.hash(q, floor, room.id, 32)));
		double roll = plan.hash(room.id, floor, 0, 33);
		List<Item> want = new ArrayList<>();
		switch (room.kind) {
			case BED -> {
				want.add(Item.BED);
				if (roll < 0.08) {
					want.add(Item.CHEST);
				}
				want.add(Item.FLOWER);
				if (roll > 0.6) {
					want.add(Item.BOOKSHELF);
				}
			}
			case STUDIO -> {
				want.add(Item.BED);
				want.add(roll < 0.3 ? Item.FURNACE : Item.CAULDRON);
				want.add(Item.CRAFTING);
				if (roll > 0.7) {
					want.add(Item.BARREL);
				}
			}
			case KITCHEN -> {
				// A stove (sometimes), the sink, the worktop, now and then a pantry.
				if (roll < 0.3) {
					want.add(roll < 0.15 ? Item.FURNACE : Item.SMOKER);
				}
				want.add(Item.CAULDRON);
				want.add(Item.CRAFTING);
				if (roll > 0.65) {
					want.add(Item.BARREL);
				}
			}
			case LIVING -> {
				want.add(Item.BOOKSHELF);
				want.add(Item.FLOWER);
				if (roll < 0.6) {
					want.add(Item.BOOKSHELF);
				}
				if (roll < 0.35) {
					want.add(Item.JOB);
				}
				if (roll > 0.95) {
					want.add(Item.CHEST);
				}
			}
			case HALL -> {
				if (roll < 0.5) {
					want.add(Item.FLOWER);
				}
			}
			case BATH -> want.add(Item.CAULDRON);
			case OFFICE -> {
				want.add(Item.BOOKSHELF);
				if (roll < 0.15) {
					want.add(Item.BARREL);
				}
				if (roll < 0.4) {
					want.add(Item.JOB);
				}
			}
			case SHOP -> {
				want.add(Item.JOB);
				for (int i = 0; i < Math.max(2, wallSpots.size() / 3); i++) {
					want.add(Item.STOCK);
				}
			}
			case HALLROOM -> {
				if (room.trade == BuildingPlan.Trade.CHURCH) {
					want.add(Item.ALTAR);
					want.add(Item.CHEST);
				} else {
					want.add(Item.JOB);
					for (int i = 0; i < wallSpots.size() / 4; i++) {
						want.add(room.trade == null ? Item.CRATE : Item.STOCK);
					}
				}
			}
			default -> {
			}
		}
		java.util.Set<Integer> used = new java.util.HashSet<>();
		// Cells something stands in that you can't walk or step over; none may cut the room in two.
		java.util.Set<Integer> blocked = new java.util.HashSet<>();
		boolean stocked = false;
		for (Item item : want) {
			for (int[] spot : wallSpots) {
				int c = spot[0];
				if (used.contains(c)) {
					continue;
				}
				int away = (spot[1] + 2) % 4;
				int foot = item == Item.BED ? step(c, away) : -1;
				if (item == Item.BED) {
					// The foot is the next cell out from the wall: free floor of the same room.
					if (foot < 0 || used.contains(foot) || roomOf[layer][foot] != room.id || code[layer][foot] != ROOM
						|| nearDoor(x0 + foot % w, z0 + foot / w, floor)) {
						continue;
					}
				}
				if (blocks(item) && !keepsWay(room, layer, blocked, c, foot)) {
					continue;
				}
				if (blocks(item)) {
					blocked.add(c);
					if (foot >= 0) {
						blocked.add(foot);
					}
				}
				if (item == Item.BED) {
					used.add(foot);
					out.put(foot, new Placed(Item.BED, spot[1], -1 - plan.hash(c, floor, 7, 34)));
				}
				used.add(c);
				// A shop's first stock is always a barrel of it.
				double look = item == Item.STOCK && !stocked ? 0 : plan.hash(c, floor, 7, 34);
				stocked |= item == Item.STOCK;
				out.put(c, new Placed(item, away, look));
				break;
			}
		}
		// The middle: tables in kitchens and living rooms and offices, a rug in living rooms and
		// bedrooms; rows in shops and halls.
		Item centre = switch (room.kind) {
			case KITCHEN, OFFICE -> Item.TABLE;
			case LIVING, BED -> Item.RUG;
			default -> null;
		};
		if (centre == Item.TABLE && !middle.isEmpty()) {
			int c = middle.get(0);
			if (!used.contains(c) && keepsWay(room, layer, blocked, c, -1)) {
				blocked.add(c);
				out.put(c, new Placed(Item.TABLE, 0, roll));
			}
		} else if (centre == Item.RUG) {
			for (int c : middle) {
				if (!used.contains(c) && !out.containsKey(c)) {
					out.put(c, new Placed(Item.RUG, 0, roll));
				}
			}
		}
		if (room.kind == Kind.HALLROOM || room.kind == Kind.SHOP && room.size() >= 40) {
			for (int c : middle) {
				if (used.contains(c)) {
					continue;
				}
				int x = x0 + c % w;
				int z = z0 + c / w;
				// Rows a block apart, with aisles round the walls (middle cells are off the walls already).
				boolean row = room.trade == BuildingPlan.Trade.CHURCH ? Math.floorMod(z + x / 1000, 2) == 0 && Math.floorMod(x, 4) != 0
					: Math.floorMod(x, 3) == 0 && Math.floorMod(z, 6) != 0;
				if (!row || !openAround(c, layer, room.id)) {
					continue;
				}
				Item it = switch (room.trade == null ? BuildingPlan.Trade.GENERAL : room.trade) {
					case CHURCH -> Item.PEW;
					case LIBRARY, BOOKS -> Item.BOOKSHELF;
					case SCHOOL, OFFICE, FOOD -> Item.TABLE;
					default -> room.kind == Kind.HALLROOM && room.trade == null ? Item.CRATE : Item.STOCK;
				};
				if (blocks(it)) {
					if (!keepsWay(room, layer, blocked, c, -1)) {
						continue;
					}
					blocked.add(c);
				}
				out.put(c, new Placed(it, plan.doors.isEmpty() ? 2 : plan.doors.get(0)[2], plan.hash(c, floor, 9, 35)));
			}
		}
		return out;
	}

	/**
	 * Whether a thing stops you walking, so it must not stand in the way: everything but rugs, flower
	 * pots and pews (you step over those). Beds count, the made-up ones being a full block high.
	 */
	private static boolean blocks(Item item) {
		return item != Item.RUG && item != Item.FLOWER && item != Item.PEW;
	}

	/** Whether the room's free floor stays in one piece with cells a and b taken too (b may be -1). */
	private boolean keepsWay(Room room, int layer, java.util.Set<Integer> blocked, int a, int b) {
		int free = 0;
		int start = -1;
		for (int c : room.cells) {
			if (roomOf[layer][c] == room.id && c != a && c != b && !blocked.contains(c) && (code[layer][c] == ROOM || code[layer][c] == LANDING)) {
				free++;
				start = start < 0 ? c : start;
			}
		}
		if (start < 0) {
			return false;
		}
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			int c = queue.poll();
			for (int d = 0; d < 4; d++) {
				int n = step(c, d);
				if (n >= 0 && n != a && n != b && !blocked.contains(n) && roomOf[layer][n] == room.id && !seen.contains(n)
					&& (code[layer][n] == ROOM || code[layer][n] == LANDING)) {
					seen.add(n);
					queue.add(n);
				}
			}
		}
		return seen.size() == free;
	}

	/** A middle cell whose neighbours are all floor of the same room: a row there leaves the way past it free. */
	private boolean openAround(int c, int layer, int room) {
		for (int d = 0; d < 4; d++) {
			int n = step(c, d);
			if (n < 0 || roomOf[layer][n] != room) {
				return false;
			}
		}
		return true;
	}
}
