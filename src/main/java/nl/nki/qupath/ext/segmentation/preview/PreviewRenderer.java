package nl.nki.qupath.ext.segmentation.preview;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import nl.nki.qupath.ext.segmentation.mixer.CellposeParameters;
import nl.nki.qupath.ext.segmentation.mixer.MixChannelsOp;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.opencv.tools.OpenCVTools;

/**
 * Turns the mixed 2-channel image into an RGB preview: magenta = cyto/membrane, green = nucleus.
 * <p>
 * The display mimics the normalization Cellpose will receive:
 * <ul>
 *     <li>PER_TILE: percentiles per block of {@code tileSize} image pixels (as BIOP Cellpose tiles), so
 *         tile-to-tile differences in contrast become visible;</li>
 *     <li>GLOBAL: percentiles over the whole preview region;</li>
 *     <li>NONE: values shown with a fixed 0–1 range.</li>
 * </ul>
 */
public final class PreviewRenderer {

    private PreviewRenderer() {}

    public record Result(WritableImage image, long mixMillis, long renderMillis) {}

    public static Result render(PreviewRegion region, MixChannelsOp op, CellposeParameters params, WritableImage reuse) {
        int w = region.getWidth(), h = region.getHeight();

        long t0 = System.nanoTime();
        Mat[] mixed = op.mix(region.getChannels());
        float[] cyto = OpenCVTools.extractPixels(mixed[0], (float[]) null);
        float[] nuc = OpenCVTools.extractPixels(mixed[1], (float[]) null);
        mixed[0].close();
        mixed[1].close();
        long t1 = System.nanoTime();

        // Block size in preview pixels
        int block;
        switch (params.getNormalization()) {
            case PER_TILE -> block = Math.max(8, (int) Math.round(params.getTileSize() / region.getDownsample()));
            default -> block = Math.max(w, h);
        }
        boolean normalize = params.getNormalization() != CellposeParameters.Normalization.NONE;
        double pLow = params.getNormLowPercentile(), pHigh = params.getNormHighPercentile();

        int[] argb = new int[w * h];
        // Start with opaque black, then add the two channels
        java.util.Arrays.fill(argb, 0xFF000000);
        for (int by = 0; by < h; by += block) {
            for (int bx = 0; bx < w; bx += block) {
                int bw = Math.min(block, w - bx), bh = Math.min(block, h - by);
                double[] cr = normalize ? percentiles(cyto, w, bx, by, bw, bh, pLow, pHigh) : new double[] {0, 1};
                double[] nr = normalize ? percentiles(nuc, w, bx, by, bw, bh, pLow, pHigh) : new double[] {0, 1};
                for (int y = by; y < by + bh; y++) {
                    int row = y * w;
                    for (int x = bx; x < bx + bw; x++) {
                        int i = row + x;
                        int c = toByte(cyto[i], cr[0], cr[1]);
                        int n = toByte(nuc[i], nr[0], nr[1]);
                        argb[i] = 0xFF000000 | (c << 16) | (n << 8) | c;
                    }
                }
            }
        }

        var image = (reuse != null && (int) reuse.getWidth() == w && (int) reuse.getHeight() == h)
                ? reuse : new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        long t2 = System.nanoTime();
        return new Result(image, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
    }

    private static int toByte(float v, double lo, double hi) {
        double scaled = (v - lo) / (hi - lo);
        if (!(scaled > 0)) return 0; // also NaN
        if (scaled >= 1) return 255;
        return (int) (scaled * 255 + 0.5);
    }

    /** Approximate low/high percentiles (0–100) of a block, via a histogram. */
    public static double[] percentiles(float[] data, int stride, int x0, int y0, int w, int h, double pLow, double pHigh) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int y = y0; y < y0 + h; y++)
            for (int x = x0; x < x0 + w; x++) {
                float v = data[y * stride + x];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        if (!(max > min))
            return new double[] {min, min + 1};
        final int bins = 4096;
        long[] hist = new long[bins];
        double scale = (bins - 1) / (double) (max - min);
        for (int y = y0; y < y0 + h; y++)
            for (int x = x0; x < x0 + w; x++) {
                float v = data[y * stride + x];
                if (v == v) // not NaN
                    hist[(int) ((v - min) * scale)]++;
            }
        long n = (long) w * h;
        double lo = valueAt(hist, n * pLow / 100.0, min, scale);
        double hi = valueAt(hist, n * pHigh / 100.0, min, scale);
        if (!(hi > lo))
            hi = lo + 1e-6;
        return new double[] {lo, hi};
    }

    private static double valueAt(long[] hist, double rank, double min, double scale) {
        long cum = 0;
        for (int i = 0; i < hist.length; i++) {
            cum += hist[i];
            if (cum >= rank)
                return min + i / scale;
        }
        return min + (hist.length - 1) / scale;
    }
}
