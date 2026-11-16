package nl.nki.qupath.ext.segmentation.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreviewMathTest {

    @Test
    void channelStatsPercentileIgnoresZeros() {
        // 1000 zeros (padding) + values 1..1000
        float[] px = new float[2000];
        for (int i = 0; i < 1000; i++)
            px[1000 + i] = i + 1;
        var stats = ChannelStats.compute(px);
        assertEquals(1000, stats.getZeroCount());
        assertEquals(1000, stats.getMax());
        // Log-spaced bins are ~1.4% of the value wide here: allow 2%
        assertEquals(500, stats.percentileIgnoringZeros(50), 500 * 0.02);
        assertEquals(999, stats.percentileIgnoringZeros(99.9), 999 * 0.02);
        assertEquals(10, stats.percentileIgnoringZeros(1), 1);
    }

    @Test
    void logScaleRoundTripsIncludingZeroAndNegative() {
        for (double v : new double[] {-1000, -0.5, 0, 0.5, 1, 600, 65535}) {
            assertEquals(v, IntensityScale.LOG.fromAxis(IntensityScale.LOG.toAxis(v)), Math.abs(v) * 1e-12 + 1e-12);
            assertEquals(v, IntensityScale.LINEAR.fromAxis(IntensityScale.LINEAR.toAxis(v)));
        }
        assertEquals(0, IntensityScale.LOG.toAxis(0));
        assertEquals(3, IntensityScale.LOG.toAxis(999), 1e-12);
        assertTrue(IntensityScale.LOG.toAxis(-9) < 0);
    }

    @Test
    void histogramBinsAreLogSpaced() {
        float[] px = {1, 10, 100, 1000, 10000};
        var stats = ChannelStats.compute(px);
        // Equal steps on the log axis: each decade gets about the same number of bins
        double a0 = IntensityScale.LOG.toAxis(stats.binValue(0));
        double a1 = IntensityScale.LOG.toAxis(stats.binValue(1));
        double a2 = IntensityScale.LOG.toAxis(stats.binValue(2));
        assertEquals(a1 - a0, a2 - a1, 1e-9);
        long nonEmpty = java.util.Arrays.stream(stats.getHistogram()).filter(c -> c > 0).count();
        assertEquals(5, nonEmpty); // each decade in its own bin
    }

    @Test
    void blockPercentilesUseOnlyTheBlock() {
        // 4x2 image: left 2x2 block 0..3, right 2x2 block 100..103
        int w = 4;
        float[] data = {0, 1, 100, 101,
                        2, 3, 102, 103};
        double[] left = PreviewRenderer.percentiles(data, w, 0, 0, 2, 2, 0, 100);
        double[] right = PreviewRenderer.percentiles(data, w, 2, 0, 2, 2, 0, 100);
        assertEquals(0, left[0], 0.01);
        assertEquals(3, left[1], 0.01);
        assertEquals(100, right[0], 0.01);
        assertEquals(103, right[1], 0.01);
    }

    @Test
    void splitChannelsStayValidAndMixable() {
        // Regression: channels used to reference a closed MatVector -> garbage sizes / native crash
        var mat = new org.bytedeco.opencv.opencv_core.Mat(30, 40, org.bytedeco.opencv.global.opencv_core.CV_16UC(3),
                new org.bytedeco.opencv.opencv_core.Scalar(100, 200, 300, 0));
        var channels = PreviewRegion.splitChannels(mat);
        mat.close();
        System.gc();
        assertEquals(3, channels.size());
        for (var ch : channels) {
            assertEquals(30, ch.rows());
            assertEquals(40, ch.cols());
            assertEquals(1, ch.channels());
        }
        var op = new nl.nki.qupath.ext.segmentation.mixer.MixChannelsOp(
                java.util.List.of(new nl.nki.qupath.ext.segmentation.mixer.MixChannelsOp.ScaledChannel(0, 0, 200)),
                java.util.List.of(), nl.nki.qupath.ext.segmentation.mixer.CombineMode.SUM, true);
        var mixed = op.mix(channels);
        assertEquals(30, mixed[1].rows());
        assertEquals(0.5f, qupath.opencv.tools.OpenCVTools.extractPixels(mixed[1], (float[]) null)[0], 1e-6);
        assertEquals(30, mixed[0].rows()); // empty cyto -> zero Mat with the right size
    }

    @Test
    void constantBlockDoesNotDivideByZero() {
        float[] data = {5, 5, 5, 5};
        double[] r = PreviewRenderer.percentiles(data, 2, 0, 0, 2, 2, 0.1, 99.8);
        assertTrue(r[1] > r[0]);
    }
}
