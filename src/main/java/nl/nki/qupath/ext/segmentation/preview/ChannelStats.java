package nl.nki.qupath.ext.segmentation.preview;

/**
 * Intensity statistics of one channel within the preview region: a histogram over [min, max] with log-spaced bins
 * ({@link IntensityScale#LOG}), so low intensities get as much resolution as high ones. Zero-valued pixels are
 * counted separately (CODEX images often contain zero padding).
 */
public final class ChannelStats {

    public static final int BINS = 512;
    private static final IntensityScale BIN_SCALE = IntensityScale.LOG;

    private final double min;
    private final double max;
    private final long[] histogram;
    private final long zeroCount;
    private final long total;

    private ChannelStats(double min, double max, long[] histogram, long zeroCount, long total) {
        this.min = min;
        this.max = max;
        this.histogram = histogram;
        this.zeroCount = zeroCount;
        this.total = total;
    }

    public static ChannelStats compute(float[] pixels) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (float v : pixels) {
            if (Float.isNaN(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min > max) { // all NaN or empty
            min = 0;
            max = 1;
        }
        long[] hist = new long[BINS];
        long zeros = 0, total = 0;
        double aMin = BIN_SCALE.toAxis(min), aRange = BIN_SCALE.toAxis(max) - aMin;
        for (float v : pixels) {
            if (Float.isNaN(v)) continue;
            total++;
            if (v == 0f) {
                zeros++;
                continue;
            }
            int bin = aRange <= 0 ? 0 : (int) ((BIN_SCALE.toAxis(v) - aMin) / aRange * (BINS - 1));
            hist[bin]++;
        }
        return new ChannelStats(min, max, hist, zeros, total);
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    /** Histogram of non-zero pixels; bin i covers [{@link #binValue(int) binValue(i)}, binValue(i + 1)). */
    public long[] getHistogram() { return histogram; }
    public long getZeroCount() { return zeroCount; }

    /** Lower edge of bin i (may be called with i = BINS for the upper edge of the last bin). */
    public double binValue(int bin) {
        double aMin = BIN_SCALE.toAxis(min), aMax = BIN_SCALE.toAxis(max);
        return BIN_SCALE.fromAxis(aMin + (aMax - aMin) * bin / (BINS - 1));
    }

    /**
     * Approximate percentile (0–100) of the non-zero pixels, as in get_cellpose_scaling_anno.groovy (IGNORE_ZEROS).
     * Accuracy is about 2% of the value (log-spaced bins).
     */
    public double percentileIgnoringZeros(double percentile) {
        long n = total - zeroCount;
        if (n <= 0)
            return 0;
        long target = (long) Math.ceil(n * percentile / 100.0);
        long cum = 0;
        for (int i = 0; i < BINS; i++) {
            cum += histogram[i];
            if (cum >= target)
                return binValue(i);
        }
        return max;
    }
}
