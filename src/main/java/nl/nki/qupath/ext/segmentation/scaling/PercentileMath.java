package nl.nki.qupath.ext.segmentation.scaling;

import java.util.List;

/**
 * Exact ports of the helper functions in get_cellpose_scaling_anno.groovy, so results match the old workflow.
 */
public final class PercentileMath {

    private PercentileMath() {}

    /**
     * Mean pixel value (bin index = intensity, 16-bit histogram) of the pixels within a percentile band.
     * Port of {@code percentileMeanFromHistogram}, including its exact loop behaviour.
     *
     * @param histogram  histogram with one bin per integer intensity
     * @param lower      lower bound of the band, as a fraction (0–1)
     * @param upper      upper bound of the band, as a fraction (0–1)
     * @param ignoreZeros exclude bin 0 (zero padding)
     * @return the mean, or NaN if the band is empty
     */
    public static double percentileMean(long[] histogram, double lower, double upper, boolean ignoreZeros) {
        long nPixels = 0;
        for (long c : histogram)
            nPixels += c;
        if (ignoreZeros)
            nPixels -= histogram[0];
        long lowerTotal = 0;
        long upperTotal = nPixels;
        int i = ignoreZeros ? 1 : 0;
        while (lowerTotal < nPixels * lower && i < histogram.length) {
            lowerTotal += histogram[i];
            i++;
        }
        int j = histogram.length - 1;
        while (upperTotal > nPixels * upper && j >= 0) {
            upperTotal -= histogram[j];
            j--;
        }
        long sum = 0;
        long n = 0;
        for (int idx = i; idx <= j; idx++) {
            n += histogram[idx];
            sum += histogram[idx] * idx;
        }
        return n == 0 ? Double.NaN : sum / (double) n;
    }

    /** Median ignoring null/NaN values; NaN if nothing is left. Port of {@code listMedians} for one list. */
    public static double median(List<Double> values) {
        var cleaned = values.stream().filter(v -> v != null && !v.isNaN()).sorted().toList();
        int n = cleaned.size();
        if (n == 0)
            return Double.NaN;
        return n % 2 == 1 ? cleaned.get(n / 2) : (cleaned.get(n / 2 - 1) + cleaned.get(n / 2)) / 2.0;
    }
}
