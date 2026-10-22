package se.itssimple.realearth.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads single tiles out of a remote PMTiles (v3) archive with HTTP range requests: the header, then
 * the directories down to the tile (kept in memory, they are shared by the tiles near each other), then
 * the tile. Blocking: call it from a download thread.
 */
public final class PmTiles {
	private static final int HEADER = 127;
	private static final int COMPRESSION_NONE = 1;
	private static final int COMPRESSION_GZIP = 2;

	private final String url;
	private long rootOffset;
	private long rootLength;
	private long leafOffset;
	private long dataOffset;
	private int maxZoom = -1;
	private Dir[] root;
	private final Map<Long, Dir[]> leaves = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Dir[]> eldest) {
			return size() > 48;
		}
	};

	private record Dir(long tileId, long offset, int length, int runLength) {
	}

	public PmTiles(String url) {
		this.url = url;
	}

	public String url() {
		return url;
	}

	/** The tile's bytes (decompressed), or null where the archive has no such tile. */
	public byte[] tile(int z, int x, int y) throws IOException {
		Dir[] dir = root();
		if (z > maxZoom) {
			return null;
		}
		long id = tileId(z, x, y);
		for (int depth = 0; depth < 4; depth++) {
			Dir e = find(dir, id);
			if (e == null) {
				return null;
			}
			if (e.runLength() > 0) {
				return Http.get(url, dataOffset + e.offset(), e.length()); // gzip is undone by Http
			}
			Dir[] leaf;
			synchronized (leaves) {
				leaf = leaves.get(e.offset());
			}
			if (leaf == null) {
				leaf = directory(Http.get(url, leafOffset + e.offset(), e.length()));
				synchronized (leaves) {
					leaves.put(e.offset(), leaf);
				}
			}
			dir = leaf;
		}
		return null;
	}

	private synchronized Dir[] root() throws IOException {
		if (root == null) {
			byte[] h = Http.get(url, 0, HEADER);
			if (h.length < HEADER || h[0] != 'P' || h[1] != 'M' || h[7] != 3) {
				throw new IOException("not a PMTiles v3 archive: " + url);
			}
			ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
			rootOffset = b.getLong(8);
			rootLength = b.getLong(16);
			leafOffset = b.getLong(40);
			dataOffset = b.getLong(56);
			int internal = h[97];
			int tiles = h[98];
			if (internal != COMPRESSION_GZIP && internal != COMPRESSION_NONE || tiles != COMPRESSION_GZIP && tiles != COMPRESSION_NONE) {
				throw new IOException("unsupported PMTiles compression in " + url);
			}
			maxZoom = h[101];
			root = directory(Http.get(url, rootOffset, (int) rootLength));
		}
		return root;
	}

	/** The entry covering a tile id: the last starting at or before it, if its run (or leaf) reaches it. */
	private static Dir find(Dir[] dir, long id) {
		int lo = 0;
		int hi = dir.length - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			long c = dir[mid].tileId();
			if (c < id) {
				lo = mid + 1;
			} else if (c > id) {
				hi = mid - 1;
			} else {
				return dir[mid];
			}
		}
		if (hi >= 0) {
			Dir e = dir[hi];
			if (e.runLength() == 0 || id - e.tileId() < e.runLength()) {
				return e;
			}
		}
		return null;
	}

	/** A directory: varint counts, delta-coded tile ids, run lengths, lengths, and offsets (0 = right after the last). */
	private static Dir[] directory(byte[] b) {
		int[] at = {0};
		int n = (int) varint(b, at);
		long[] ids = new long[n];
		long last = 0;
		for (int i = 0; i < n; i++) {
			last += varint(b, at);
			ids[i] = last;
		}
		int[] runs = new int[n];
		for (int i = 0; i < n; i++) {
			runs[i] = (int) varint(b, at);
		}
		int[] lengths = new int[n];
		for (int i = 0; i < n; i++) {
			lengths[i] = (int) varint(b, at);
		}
		Dir[] out = new Dir[n];
		long offset = 0;
		for (int i = 0; i < n; i++) {
			long v = varint(b, at);
			offset = v == 0 && i > 0 ? offset + lengths[i - 1] : v - 1;
			out[i] = new Dir(ids[i], offset, lengths[i], runs[i]);
		}
		return out;
	}

	private static long varint(byte[] b, int[] at) {
		long r = 0;
		int shift = 0;
		while (true) {
			int c = b[at[0]++] & 0xFF;
			r |= (long) (c & 0x7F) << shift;
			if (c < 0x80) {
				return r;
			}
			shift += 7;
		}
	}

	/** PMTiles' tile id: all tiles of lower zooms first, then the Hilbert curve position at this zoom. */
	static long tileId(int z, int x, int y) {
		long acc = 0;
		for (int t = 0; t < z; t++) {
			acc += 1L << (2 * t);
		}
		long n = 1L << z;
		long d = 0;
		long xx = x;
		long yy = y;
		for (long s = n / 2; s > 0; s /= 2) {
			long rx = (xx & s) > 0 ? 1 : 0;
			long ry = (yy & s) > 0 ? 1 : 0;
			d += s * s * ((3 * rx) ^ ry);
			if (ry == 0) {
				if (rx == 1) {
					xx = s - 1 - xx;
					yy = s - 1 - yy;
				}
				long t = xx;
				xx = yy;
				yy = t;
			}
		}
		return acc + d;
	}
}
