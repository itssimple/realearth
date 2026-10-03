package se.itssimple.realearth.osm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A minimal Mapbox Vector Tile (MVT 2.1) decoder: just enough protobuf to read named layers, feature
 * tags and geometry, and nothing else. Layers not asked for are skipped without being parsed, which
 * matters in cities, where the building layer is most of the tile.
 */
public final class VectorTile {
	public static final int POINT = 1;
	public static final int LINE = 2;
	public static final int POLYGON = 3;

	/** One feature: tags, type, and geometry as rings or lines in tile units (0..extent). */
	public record Feature(String layer, Map<String, Object> tags, int type, List<int[]> parts, int extent) {
		public String string(String key) {
			Object v = tags.get(key);
			return v == null ? null : v.toString();
		}

		/** A numeric tag, or NaN when absent or not a number. */
		public double number(String key) {
			Object v = tags.get(key);
			return v instanceof Number n ? n.doubleValue() : Double.NaN;
		}

		public boolean flag(String key) {
			Object v = tags.get(key);
			return v instanceof Number n ? n.longValue() != 0 : v instanceof Boolean b ? b : v != null && "1".equals(v.toString());
		}
	}

	private VectorTile() {
	}

	public static List<Feature> decode(byte[] data, Set<String> layers) throws IOException {
		List<Feature> out = new ArrayList<>();
		Reader r = new Reader(data, 0, data.length);
		while (r.more()) {
			int key = (int) r.varint();
			if (key >>> 3 == 3 && (key & 7) == 2) {
				int len = (int) r.varint();
				readLayer(new Reader(data, r.pos, r.pos + len), layers, out);
				r.pos += len;
			} else {
				r.skip(key & 7);
			}
		}
		return out;
	}

	private static void readLayer(Reader r, Set<String> wanted, List<Feature> out) throws IOException {
		String name = null;
		int extent = 4096;
		List<String> keys = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		List<int[]> featureSpans = new ArrayList<>();
		while (r.more()) {
			int key = (int) r.varint();
			int field = key >>> 3;
			int wire = key & 7;
			if (wire == 2) {
				int len = (int) r.varint();
				int start = r.pos;
				switch (field) {
					case 1 -> {
						name = new String(r.data, start, len, java.nio.charset.StandardCharsets.UTF_8);
						if (!wanted.contains(name)) {
							return;
						}
					}
					case 2 -> featureSpans.add(new int[] {start, start + len});
					case 3 -> keys.add(new String(r.data, start, len, java.nio.charset.StandardCharsets.UTF_8));
					case 4 -> values.add(readValue(new Reader(r.data, start, start + len)));
					default -> {
					}
				}
				r.pos = start + len;
			} else if (field == 5 && wire == 0) {
				extent = (int) r.varint();
			} else {
				r.skip(wire);
			}
		}
		if (name == null || !wanted.contains(name)) {
			return;
		}
		for (int[] span : featureSpans) {
			Feature f = readFeature(new Reader(r.data, span[0], span[1]), name, keys, values, extent);
			if (f != null) {
				out.add(f);
			}
		}
	}

	private static Object readValue(Reader r) throws IOException {
		Object v = null;
		while (r.more()) {
			int key = (int) r.varint();
			switch (key >>> 3) {
				case 1 -> {
					int len = (int) r.varint();
					v = new String(r.data, r.pos, len, java.nio.charset.StandardCharsets.UTF_8);
					r.pos += len;
				}
				case 2 -> v = Float.intBitsToFloat((int) r.fixed(4));
				case 3 -> v = Double.longBitsToDouble(r.fixed(8));
				case 4, 5 -> v = r.varint();
				case 6 -> {
					long z = r.varint();
					v = (z >>> 1) ^ -(z & 1);
				}
				case 7 -> v = r.varint() != 0;
				default -> r.skip(key & 7);
			}
		}
		return v;
	}

	private static Feature readFeature(Reader r, String layer, List<String> keys, List<Object> values, int extent) throws IOException {
		int type = 0;
		int[] tagIds = new int[0];
		int[] geometry = new int[0];
		while (r.more()) {
			int key = (int) r.varint();
			int field = key >>> 3;
			int wire = key & 7;
			if (field == 2 && wire == 2) {
				tagIds = r.packed();
			} else if (field == 3 && wire == 0) {
				type = (int) r.varint();
			} else if (field == 4 && wire == 2) {
				geometry = r.packed();
			} else {
				r.skip(wire);
			}
		}
		Map<String, Object> tags = new HashMap<>();
		for (int i = 0; i + 1 < tagIds.length; i += 2) {
			if (tagIds[i] < keys.size() && tagIds[i + 1] < values.size()) {
				tags.put(keys.get(tagIds[i]), values.get(tagIds[i + 1]));
			}
		}
		return new Feature(layer, tags, type, geometry(geometry), extent);
	}

	/** Decodes MoveTo/LineTo/ClosePath into parts of interleaved x,y coordinates. */
	private static List<int[]> geometry(int[] g) {
		List<int[]> parts = new ArrayList<>();
		int x = 0;
		int y = 0;
		int i = 0;
		int[] current = null;
		int n = 0;
		while (i < g.length) {
			int command = g[i] & 7;
			int count = g[i] >>> 3;
			i++;
			if (command == 1 || command == 2) {
				for (int c = 0; c < count && i + 1 < g.length; c++) {
					x += zigzag(g[i++]);
					y += zigzag(g[i++]);
					if (command == 1) {
						if (current != null && n > 0) {
							parts.add(java.util.Arrays.copyOf(current, n));
						}
						current = new int[64];
						n = 0;
					}
					if (current == null) {
						current = new int[64];
						n = 0;
					}
					if (n + 2 > current.length) {
						current = java.util.Arrays.copyOf(current, current.length * 2);
					}
					current[n++] = x;
					current[n++] = y;
				}
			} else if (command == 7) {
				// Rings are closed implicitly when filled.
			} else {
				break;
			}
		}
		if (current != null && n > 0) {
			parts.add(java.util.Arrays.copyOf(current, n));
		}
		return parts;
	}

	private static int zigzag(int v) {
		return (v >>> 1) ^ -(v & 1);
	}

	private static final class Reader {
		final byte[] data;
		int pos;
		final int end;

		Reader(byte[] data, int pos, int end) {
			this.data = data;
			this.pos = pos;
			this.end = end;
		}

		boolean more() {
			return pos < end;
		}

		long varint() throws IOException {
			long result = 0;
			int shift = 0;
			while (pos < end) {
				int b = data[pos++] & 0xFF;
				result |= (long) (b & 0x7F) << shift;
				if (b < 0x80) {
					return result;
				}
				shift += 7;
				if (shift > 63) {
					break;
				}
			}
			throw new IOException("bad varint");
		}

		long fixed(int bytes) {
			long v = 0;
			for (int i = 0; i < bytes; i++) {
				v |= (long) (data[pos++] & 0xFF) << (8 * i);
			}
			return v;
		}

		int[] packed() throws IOException {
			int len = (int) varint();
			int stop = pos + len;
			int[] out = new int[Math.max(4, len)];
			int n = 0;
			while (pos < stop) {
				out[n++] = (int) varint();
			}
			return java.util.Arrays.copyOf(out, n);
		}

		void skip(int wire) throws IOException {
			switch (wire) {
				case 0 -> varint();
				case 1 -> pos += 8;
				case 2 -> pos += (int) varint();
				case 5 -> pos += 4;
				default -> throw new IOException("unsupported wire type " + wire);
			}
		}
	}
}
