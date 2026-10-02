package se.itssimple.realearth.osm;

import java.util.Arrays;
import java.util.List;

/**
 * Draws water shapes onto a grid: areas filled by scanline (even-odd across all rings of one area, so
 * islands stay dry), waterways as round-capped strokes of their real width.
 *
 * A grid is described by the sample coordinate of its first cell and how many cells there are per
 * sample (1 for the terrain grid, 4 for the shoreline raster). Cell centres sit at
 * origin + (i + 0.5) / perSample - 0.5, which for perSample = 1 is exactly the sample positions.
 */
public final class Rasterizer {
	private Rasterizer() {
	}

	/**
	 * @param out       cells, row-major, {@code size * size}
	 * @param value     written into every covered cell (a higher value is never overwritten by a lower one)
	 */
	public static void fill(List<double[]> rings, double originX, double originY, int size, double perSample, byte[] out, byte value) {
		double[] xs = new double[64];
		for (int row = 0; row < size; row++) {
			double y = originY + (row + 0.5) / perSample - 0.5;
			int n = 0;
			for (double[] ring : rings) {
				int points = ring.length / 2;
				for (int i = 0; i < points; i++) {
					double x0 = ring[2 * i];
					double y0 = ring[2 * i + 1];
					double x1 = ring[2 * ((i + 1) % points)];
					double y1 = ring[2 * ((i + 1) % points) + 1];
					if ((y0 <= y) != (y1 <= y)) {
						if (n == xs.length) {
							xs = Arrays.copyOf(xs, n * 2);
						}
						xs[n++] = x0 + (y - y0) / (y1 - y0) * (x1 - x0);
					}
				}
			}
			if (n < 2) {
				continue;
			}
			Arrays.sort(xs, 0, n);
			for (int k = 0; k + 1 < n; k += 2) {
				int c0 = (int) Math.ceil((xs[k] - originX + 0.5) * perSample - 0.5);
				int c1 = (int) Math.floor((xs[k + 1] - originX + 0.5) * perSample - 0.5);
				c0 = Math.max(c0, 0);
				c1 = Math.min(c1, size - 1);
				for (int c = c0; c <= c1; c++) {
					int idx = row * size + c;
					if (out[idx] < value) {
						out[idx] = value;
					}
				}
			}
		}
	}

	/** Strokes a polyline with radius {@code radiusSamples}. */
	public static void stroke(double[] line, double radiusSamples, double originX, double originY, int size, double perSample, byte[] out, byte value) {
		double r = Math.max(radiusSamples, 0.5 / perSample);
		for (int i = 0; i + 3 < line.length; i += 2) {
			double ax = line[i];
			double ay = line[i + 1];
			double bx = line[i + 2];
			double by = line[i + 3];
			int c0 = Math.max(0, (int) Math.floor((Math.min(ax, bx) - r - originX + 0.5) * perSample - 0.5));
			int c1 = Math.min(size - 1, (int) Math.ceil((Math.max(ax, bx) + r - originX + 0.5) * perSample - 0.5));
			int r0 = Math.max(0, (int) Math.floor((Math.min(ay, by) - r - originY + 0.5) * perSample - 0.5));
			int r1 = Math.min(size - 1, (int) Math.ceil((Math.max(ay, by) + r - originY + 0.5) * perSample - 0.5));
			double dx = bx - ax;
			double dy = by - ay;
			double len2 = dx * dx + dy * dy;
			for (int row = r0; row <= r1; row++) {
				double py = originY + (row + 0.5) / perSample - 0.5;
				for (int c = c0; c <= c1; c++) {
					double px = originX + (c + 0.5) / perSample - 0.5;
					double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
					double ex = ax + t * dx - px;
					double ey = ay + t * dy - py;
					if (ex * ex + ey * ey <= r * r) {
						int idx = row * size + c;
						if (out[idx] < value) {
							out[idx] = value;
						}
					}
				}
			}
		}
	}
}
