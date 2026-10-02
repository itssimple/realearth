package se.itssimple.realearth.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes a Terrarium PNG straight into heights in metres.
 *
 *   height = (R * 256 + G + B / 256) - 32768
 *
 * A small decoder of our own rather than ImageIO, so a dedicated server never touches AWT and the
 * byte order is known exactly: decoding BGR as RGB yields mountains where there is farmland. The
 * live service sends 8-bit RGB, non-interlaced (verified); grey, grey+alpha, RGBA and palette images
 * are accepted too, in case a mirror re-encodes them.
 */
public final class TerrariumPng {
	private static final long SIGNATURE = 0x89504E470D0A1A0AL;

	private TerrariumPng() {
	}

	public static float[] decode(byte[] png, int expectedSize) throws IOException {
		ByteBuffer in = ByteBuffer.wrap(png);
		if (png.length < 8 || in.getLong() != SIGNATURE) {
			throw new IOException("not a PNG");
		}

		int width = 0;
		int height = 0;
		int colorType = -1;
		byte[] palette = null;
		ByteArrayOutputStream idat = new ByteArrayOutputStream(png.length);

		while (in.remaining() >= 12) {
			int length = in.getInt();
			int type = in.getInt();
			if (length < 0 || length > in.remaining() - 4) {
				throw new IOException("truncated chunk");
			}
			byte[] data = new byte[length];
			in.get(data);
			in.getInt(); // CRC; the inflater catches corruption that matters

			switch (type) {
				case 0x49484452 -> { // IHDR
					ByteBuffer h = ByteBuffer.wrap(data);
					width = h.getInt();
					height = h.getInt();
					int bitDepth = h.get() & 0xFF;
					colorType = h.get() & 0xFF;
					h.get(); // compression
					h.get(); // filter method
					int interlace = h.get() & 0xFF;
					if (bitDepth != 8 || interlace != 0) {
						throw new IOException("unsupported PNG: depth " + bitDepth + ", interlace " + interlace);
					}
				}
				case 0x504C5445 -> palette = data; // PLTE
				case 0x49444154 -> idat.write(data); // IDAT
				case 0x49454E44 -> in.position(in.limit()); // IEND
				default -> {
				}
			}
		}

		if (width != expectedSize || height != expectedSize) {
			throw new IOException("tile is " + width + "x" + height + ", expected " + expectedSize);
		}

		int channels = switch (colorType) {
			case 0 -> 1;
			case 2 -> 3;
			case 3 -> 1;
			case 4 -> 2;
			case 6 -> 4;
			default -> throw new IOException("unsupported colour type " + colorType);
		};
		if (colorType == 3 && palette == null) {
			throw new IOException("palette image without PLTE");
		}

		int stride = width * channels;
		byte[] raw = inflate(idat.toByteArray(), (stride + 1) * height);
		byte[] prev = new byte[stride];
		byte[] cur = new byte[stride];
		float[] heights = new float[width * height];

		for (int y = 0; y < height; y++) {
			int rowStart = y * (stride + 1);
			int filter = raw[rowStart] & 0xFF;
			System.arraycopy(raw, rowStart + 1, cur, 0, stride);
			unfilter(filter, cur, prev, channels);

			for (int x = 0; x < width; x++) {
				int r;
				int g;
				int b;
				int o = x * channels;
				switch (colorType) {
					case 2, 6 -> {
						r = cur[o] & 0xFF;
						g = cur[o + 1] & 0xFF;
						b = cur[o + 2] & 0xFF;
					}
					case 3 -> {
						int p = (cur[o] & 0xFF) * 3;
						r = palette[p] & 0xFF;
						g = palette[p + 1] & 0xFF;
						b = palette[p + 2] & 0xFF;
					}
					default -> {
						r = cur[o] & 0xFF;
						g = r;
						b = r;
					}
				}
				heights[y * width + x] = (float) (r * 256.0 + g + b / 256.0 - 32768.0);
			}

			byte[] swap = prev;
			prev = cur;
			cur = swap;
		}
		return heights;
	}

	private static byte[] inflate(byte[] data, int expected) throws IOException {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(data);
			byte[] out = new byte[expected];
			int n = 0;
			while (n < expected && !inflater.finished()) {
				int got = inflater.inflate(out, n, expected - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				n += got;
			}
			if (n != expected) {
				throw new IOException("image data is " + n + " bytes, expected " + expected);
			}
			return out;
		} catch (DataFormatException e) {
			throw new IOException("corrupt image data", e);
		} finally {
			inflater.end();
		}
	}

	private static void unfilter(int filter, byte[] cur, byte[] prev, int bpp) throws IOException {
		int n = cur.length;
		switch (filter) {
			case 0 -> {
			}
			case 1 -> {
				for (int i = bpp; i < n; i++) {
					cur[i] = (byte) (cur[i] + cur[i - bpp]);
				}
			}
			case 2 -> {
				for (int i = 0; i < n; i++) {
					cur[i] = (byte) (cur[i] + prev[i]);
				}
			}
			case 3 -> {
				for (int i = 0; i < n; i++) {
					int left = i >= bpp ? cur[i - bpp] & 0xFF : 0;
					cur[i] = (byte) (cur[i] + ((left + (prev[i] & 0xFF)) >> 1));
				}
			}
			case 4 -> {
				for (int i = 0; i < n; i++) {
					int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
					int b = prev[i] & 0xFF;
					int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
					int p = a + b - c;
					int pa = Math.abs(p - a);
					int pb = Math.abs(p - b);
					int pc = Math.abs(p - c);
					int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
					cur[i] = (byte) (cur[i] + pred);
				}
			}
			default -> throw new IOException("bad filter type " + filter);
		}
	}
}
