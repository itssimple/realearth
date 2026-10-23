package se.itssimple.realearth.terrain;

import java.util.ArrayList;
import java.util.List;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;
import se.itssimple.realearth.osm.Ways;

/**
 * Railway track through the block grid, laid so a minecart can ride it: each line of the map walked
 * from one end to the other as a chain of cells, each sharing a side with the next, and the chain's
 * own links recorded (ChunkTerrain.railLinks) so the rails are shaped by the line they belong to, not
 * by whatever track lies alongside.
 *
 * <ul>
 * <li><b>Runs of two.</b> A rail can only slope where it runs straight, and a diagonal traced cell by
 * cell is all curves. So the walk keeps on in its direction at least two cells before it turns: a
 * diagonal becomes a staircase of two-cell steps, a straight rail between every two curves, and it
 * can climb.</li>
 * <li><b>One copy per map tile.</b> A line crossing a map tile's edge comes in both tiles, each copy
 * reaching a little way into the other; each copy is laid only in its own tile, and track ends that
 * meet at the edge are joined.</li>
 * </ul>
 *
 * Everything depends only on the map, so each chunk lays the same track at its edges as the next.
 */
final class RailTracks {
	/** Link bits by direction index: north, east, south, west. */
	static final int[] BIT = {1, 2, 4, 8};
	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};
	/** Cells further than this from the chunk are walked but not looked at. */
	private static final int MARGIN = 3;

	private RailTracks() {
	}

	/** The height of a line, in metres, at a point of it given in sample coordinates; NaN where unknown. */
	interface Heights {
		double meters(double sx, double sy);
	}

	/** Cells this far beyond the chunk get heights, so the levels at its edge are worked out as the next chunk's. */
	private static final int LEVEL_MARGIN = 4;

	/**
	 * Lays a line's track into the chunk's columns (and its one-column border): on the ground or a
	 * bridge, or in a tunnel (a run of the map's tunnel pieces, joined across map tiles, so not cut to
	 * one tile) into the tunnel's own links, under whatever track is on the ground.
	 *
	 * Each rail's Y comes from the line itself -- its ground, deck or tunnel floor where the rail is --
	 * then is set so the track can be ridden: a curve stands as high as the higher of its neighbours
	 * (rails only slope where they run straight), and a straight rail sunk between two higher ones, or
	 * standing proud of two lower ones, is brought level with the nearer.
	 */
	static void trace(TerrainField field, ChunkTerrain t, OsmWater.Way way, int chunkX, int chunkZ, double[] deck, boolean tunnel, Heights heights) {
		Projection projection = field.projection();
		List<int[]> cells = walk(projection, way, chunkX * 16 + 8);
		int baseX = chunkX * 16;
		int baseZ = chunkZ * 16;
		int n = cells.size();
		int[] level = new int[n];
		boolean[] known = new boolean[n];
		for (int i = 0; i < n; i++) {
			int x = cells.get(i)[0] - baseX;
			int z = cells.get(i)[1] - baseZ;
			if (x < -LEVEL_MARGIN || x > 15 + LEVEL_MARGIN || z < -LEVEL_MARGIN || z > 15 + LEVEL_MARGIN) {
				continue;
			}
			double lat = projection.latitudeAt(cells.get(i)[1] + 0.5);
			double lon = Projection.wrapLongitude(projection.originLongitude() + projection.longitudeOffsetAt(cells.get(i)[0] + 0.5, lat));
			double m = heights.meters(Mercator.sampleX(lon), Mercator.sampleY(lat));
			if (!Double.isNaN(m)) {
				level[i] = (int) Math.round(field.toY(m, lat));
				known[i] = true;
			}
		}
		// Curves as high as the higher neighbour; then straights out of a dip or off a peak.
		int[] curved = level.clone();
		for (int i = 1; i + 1 < n; i++) {
			if (known[i] && curve(cells, i)) {
				curved[i] = Math.max(level[i], Math.max(known[i - 1] ? level[i - 1] : level[i], known[i + 1] ? level[i + 1] : level[i]));
			}
		}
		// Curves side by side (a turn the walk couldn't spread over two cells) stand at one height.
		for (int sweep = 0; sweep < 2; sweep++) {
			for (int i = 1; i + 2 < n; i++) {
				if (known[i] && known[i + 1] && curve(cells, i) && curve(cells, i + 1) && curved[i] != curved[i + 1]) {
					curved[i] = curved[i + 1] = Math.max(curved[i], curved[i + 1]);
				}
			}
			for (int i = n - 3; i >= 1; i--) {
				if (known[i] && known[i + 1] && curve(cells, i) && curve(cells, i + 1) && curved[i] != curved[i + 1]) {
					curved[i] = curved[i + 1] = Math.max(curved[i], curved[i + 1]);
				}
			}
		}
		int[] ridden = curved.clone();
		for (int i = 1; i + 1 < n; i++) {
			if (known[i] && known[i - 1] && known[i + 1] && !curve(cells, i)) {
				int a = curved[i - 1];
				int b = curved[i + 1];
				if (a > curved[i] && b > curved[i]) {
					ridden[i] = Math.min(a, b);
				} else if (a < curved[i] && b < curved[i]) {
					ridden[i] = Math.max(a, b);
				}
			}
		}
		int[] prev = null;
		boolean prevKept = false;
		int[] levels = tunnel ? t.tunnelRailY : t.railY;
		for (int i = 0; i < n; i++) {
			int[] c = cells.get(i);
			int x = c[0] - baseX;
			int z = c[1] - baseZ;
			boolean near = x >= -MARGIN && x <= 15 + MARGIN && z >= -MARGIN && z <= 15 + MARGIN;
			boolean kept = near && (tunnel || ownTile(projection, way, c[0], c[1]));
			if (kept) {
				if (tunnel) {
					if (in(x, z) && t.tunnel[ChunkTerrain.index(x, z)] == ChunkTerrain.TUNNEL_BORE) {
						t.tunnelKind[ChunkTerrain.index(x, z)] = Ways.RAIL;
					}
				} else {
					mark(t, x, z, way.bridge(), deck);
				}
				if (in(x, z) && known[i]) {
					levels[ChunkTerrain.index(x, z)] = ridden[i];
				}
				if (prev != null && prevKept) {
					link(tunnel ? t.tunnelLinks : t.railLinks, prev[0] - baseX, prev[1] - baseZ, x, z);
				}
			}
			prev = c;
			prevKept = kept;
		}
	}

	/** Whether the line turns at cell i: it comes in one way and goes on another. */
	private static boolean curve(List<int[]> cells, int i) {
		int[] a = cells.get(i - 1);
		int[] b = cells.get(i);
		int[] c = cells.get(i + 1);
		return b[0] - a[0] != c[0] - b[0] || b[1] - a[1] != c[1] - b[1];
	}

	/**
	 * Where track comes out of a tunnel next to where the track on the ground begins, at about the same
	 * height: the two ends joined across.
	 */
	static void joinPortals(ChunkTerrain t) {
		for (int z = -1; z <= 16; z++) {
			for (int x = -1; x <= 16; x++) {
				int k = ChunkTerrain.index(x, z);
				if (Integer.bitCount(t.tunnelLinks[k]) != 1 || t.tunnelRailY[k] == Integer.MIN_VALUE) {
					continue;
				}
				for (int d = 0; d < 4; d++) {
					int nx = x + DX[d];
					int nz = z + DZ[d];
					if (!in(nx, nz) || (t.tunnelLinks[k] & BIT[d]) != 0) {
						continue;
					}
					int nk = ChunkTerrain.index(nx, nz);
					if (isTrack(t, nx, nz) && Integer.bitCount(t.railLinks[nk]) == 1 && t.railY[nk] != Integer.MIN_VALUE
						&& Math.abs(t.railY[nk] - t.tunnelRailY[k]) <= 1) {
						t.tunnelLinks[k] |= (byte) BIT[d];
						t.railLinks[nk] |= (byte) BIT[(d + 2) % 4];
						break;
					}
				}
			}
		}
	}

	/**
	 * After all lines are laid: track ends that meet -- one line handing over to the next at a map
	 * tile's edge -- are joined, through a corner where they meet only diagonally.
	 */
	static void joinEnds(ChunkTerrain t) {
		for (int z = -1; z <= 16; z++) {
			for (int x = -1; x <= 16; x++) {
				if (!isEnd(t, x, z)) {
					continue;
				}
				// Side by side: join them.
				boolean joined = false;
				for (int d = 0; d < 4 && !joined; d++) {
					int nx = x + DX[d];
					int nz = z + DZ[d];
					if (isEnd(t, nx, nz) && (t.railLinks[ChunkTerrain.index(x, z)] & BIT[d]) == 0) {
						link(t.railLinks, x, z, nx, nz);
						joined = true;
					}
				}
				// Diagonally, or one cell between: through a cell that is no track yet.
				for (int dz = -2; dz <= 2 && !joined; dz++) {
					for (int dx = -2; dx <= 2 && !joined; dx++) {
						int nx = x + dx;
						int nz = z + dz;
						if (Math.abs(dx) + Math.abs(dz) != 2 || !isEnd(t, nx, nz)) {
							continue;
						}
						int[][] via = Math.abs(dx) == 1 ? new int[][] {{x + dx, z}, {x, z + dz}} : new int[][] {{x + dx / 2, z + dz / 2}};
						for (int[] v : via) {
							if (in(v[0], v[1]) && !isTrack(t, v[0], v[1]) && Float.isNaN(t.waterMeters[ChunkTerrain.index(v[0], v[1])])) {
								t.way[ChunkTerrain.index(v[0], v[1])] = Ways.RAIL;
								t.railY[ChunkTerrain.index(v[0], v[1])] = Math.max(t.railY[ChunkTerrain.index(x, z)], t.railY[ChunkTerrain.index(nx, nz)]);
								link(t.railLinks, x, z, v[0], v[1]);
								link(t.railLinks, v[0], v[1], nx, nz);
								joined = true;
								break;
							}
						}
					}
				}
			}
		}
	}

	/** A track's end: a rail column the track runs on from one way only. */
	private static boolean isEnd(ChunkTerrain t, int x, int z) {
		return in(x, z) && isTrack(t, x, z) && Integer.bitCount(t.railLinks[ChunkTerrain.index(x, z)]) == 1;
	}

	private static boolean in(int x, int z) {
		return x >= -1 && x <= 16 && z >= -1 && z <= 16;
	}

	private static boolean isTrack(ChunkTerrain t, int x, int z) {
		int k = ChunkTerrain.index(x, z);
		return t.way[k] == Ways.RAIL || t.deckWay[k] == Ways.RAIL;
	}

	private static void link(byte[] links, int x0, int z0, int x1, int z1) {
		int dx = x1 - x0;
		int dz = z1 - z0;
		int d = dz < 0 ? 0 : dx > 0 ? 1 : dz > 0 ? 2 : 3;
		if (Math.abs(dx) + Math.abs(dz) != 1) {
			return;
		}
		if (in(x0, z0)) {
			links[ChunkTerrain.index(x0, z0)] |= (byte) BIT[d];
		}
		if (in(x1, z1)) {
			links[ChunkTerrain.index(x1, z1)] |= (byte) BIT[(d + 2) % 4];
		}
	}

	private static void mark(ChunkTerrain t, int x, int z, boolean bridge, double[] deck) {
		if (!in(x, z)) {
			return;
		}
		int k = ChunkTerrain.index(x, z);
		if (bridge && !Double.isNaN(deck[k])) {
			t.deckWay[k] = Ways.RAIL;
		} else if (!bridge && Float.isNaN(t.waterMeters[k])) {
			t.way[k] = Ways.RAIL;
		}
	}

	/** Whether a block column lies in the map tile the line was read from. */
	private static boolean ownTile(Projection projection, OsmWater.Way way, int x, int z) {
		double lat = projection.latitudeAt(z + 0.5);
		double lon = Projection.wrapLongitude(projection.originLongitude() + projection.longitudeOffsetAt(x + 0.5, lat));
		double perTile = Mercator.TILE_SIZE * (double) (1 << (Mercator.ZOOM - OsmWater.ZOOM));
		int tx = Math.floorMod((int) Math.floor((Mercator.sampleX(lon) + 0.5) / perTile), 1 << OsmWater.ZOOM);
		int ty = (int) Math.floor((Mercator.sampleY(lat) + 0.5) / perTile);
		return tx == way.tileX() && ty == way.tileY();
	}

	/** How far ahead a lone turn looks for the next turn the same way, to pair with it (moves). */
	private static final int PAIR_WINDOW = 32;

	/**
	 * The line's cells from end to end, each sharing a side with the next, in runs of at least two
	 * where it can: the exact cells the line passes through, then each turn that stands alone (a single
	 * step sideways) paired with the next one the same way, which is pulled back beside it. The ends
	 * stay where they are, and the track keeps within a couple of cells of the line.
	 */
	static List<int[]> walk(Projection projection, OsmWater.Way way, double nearX) {
		double[] p = way.points();
		int n = p.length / 2;
		double[] bx = new double[n];
		double[] bz = new double[n];
		double period = projection.periodX();
		for (int i = 0; i < n; i++) {
			double lat = Mercator.latitudeOfSampleY(p[2 * i + 1]);
			double lon = Projection.wrapLongitude((p[2 * i] + 0.5) / Mercator.SAMPLES_PER_AXIS * 360.0 - 180.0);
			bx[i] = projection.blockX(lat, lon);
			bz[i] = projection.blockZ(lat);
			if (period > 0) {
				bx[i] += Math.rint((nearX - bx[i]) / period) * period;
			}
		}
		// The cells the line passes through (Amanatides and Woo), segment after segment.
		List<Integer> moves = new ArrayList<>();
		int cx = (int) Math.floor(bx[0]);
		int cz = (int) Math.floor(bz[0]);
		int startX = cx;
		int startZ = cz;
		for (int i = 0; i + 1 < n; i++) {
			double x0 = bx[i];
			double z0 = bz[i];
			double dx = bx[i + 1] - x0;
			double dz = bz[i + 1] - z0;
			int ex = (int) Math.floor(bx[i + 1]);
			int ez = (int) Math.floor(bz[i + 1]);
			int stepX = dx > 0 ? 1 : -1;
			int stepZ = dz > 0 ? 1 : -1;
			double tDeltaX = dx != 0 ? Math.abs(1 / dx) : Double.MAX_VALUE;
			double tDeltaZ = dz != 0 ? Math.abs(1 / dz) : Double.MAX_VALUE;
			double tMaxX = dx != 0 ? ((stepX > 0 ? cx + 1 : cx) - x0) / dx : Double.MAX_VALUE;
			double tMaxZ = dz != 0 ? ((stepZ > 0 ? cz + 1 : cz) - z0) / dz : Double.MAX_VALUE;
			for (int guard = 0; guard < 1 << 16 && (cx != ex || cz != ez); guard++) {
				if (tMaxX < tMaxZ) {
					tMaxX += tDeltaX;
					cx += stepX;
					moves.add(stepX > 0 ? 1 : 3);
				} else {
					tMaxZ += tDeltaZ;
					cz += stepZ;
					moves.add(stepZ > 0 ? 2 : 0);
				}
			}
		}
		// Lone turns paired up.
		for (int i = 1; i + 1 < moves.size(); i++) {
			int m = moves.get(i);
			if (m != moves.get(i - 1) && m != moves.get(i + 1)) {
				for (int j = i + 2; j < Math.min(moves.size(), i + PAIR_WINDOW); j++) {
					if (moves.get(j) == m) {
						moves.add(i + 1, moves.remove(j));
						break;
					}
				}
			}
		}
		List<int[]> cells = new ArrayList<>(moves.size() + 1);
		cx = startX;
		cz = startZ;
		cells.add(new int[] {cx, cz});
		for (int m : moves) {
			cx += DX[m];
			cz += DZ[m];
			cells.add(new int[] {cx, cz});
		}
		return cells;
	}
}
