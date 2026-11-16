package nl.nki.qupath.ext.segmentation.scaling;

import org.junit.jupiter.api.Test;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoScalerTest {

    /** Bins 1..100 each with 100 pixels, plus zeros. */
    private static long[] uniformHistogram(long zeros) {
        long[] hist = new long[65536];
        hist[0] = zeros;
        for (int v = 1; v <= 100; v++)
            hist[v] = 100;
        return hist;
    }

    @Test
    void percentileMeanMatchesScriptLoops() {
        // Worked out by hand from the Groovy loops, n = 10000 (zeros ignored):
        // low band 0–0.2: i = 1; j drops bins 100..21 -> mean of 1..20 = 10.5
        // high band 0.95–0.999: i stops after bin 95 -> 96; j drops bin 100 -> mean of 96..99 = 97.5
        long[] hist = uniformHistogram(5000);
        assertEquals(10.5, PercentileMath.percentileMean(hist, 0.0, 0.2, true), 1e-9);
        assertEquals(97.5, PercentileMath.percentileMean(hist, 0.95, 0.999, true), 1e-9);
    }

    @Test
    void percentileMeanIgnoreZerosMatters() {
        long[] hist = uniformHistogram(1000);
        // Zeros counted: n = 11000, keep bins until total <= 2200 -> bins 0..12 (1000 zeros + 1200)
        // mean = (100 * (1 + ... + 12)) / 2200 = 7800 / 2200
        assertEquals(7800.0 / 2200.0, PercentileMath.percentileMean(hist, 0.0, 0.2, false), 1e-9);
        assertEquals(10.5, PercentileMath.percentileMean(hist, 0.0, 0.2, true), 1e-9);
        // Script quirk (kept on purpose): the upper loop removes whole bins, so when the band ends inside a very
        // large bin (here: bin 0 holds half the pixels) the band becomes empty -> NaN
        assertTrue(Double.isNaN(PercentileMath.percentileMean(uniformHistogram(10000), 0.0, 0.2, false)));
    }

    @Test
    void percentileMeanEmptyIsNaN() {
        assertTrue(Double.isNaN(PercentileMath.percentileMean(new long[65536], 0.0, 0.2, true)));
    }

    @Test
    void medianLikeScript() {
        assertEquals(2.0, PercentileMath.median(Arrays.asList(3.0, 1.0, 2.0)));
        assertEquals(2.5, PercentileMath.median(Arrays.asList(4.0, 1.0, 2.0, 3.0)));
        assertEquals(2.0, PercentileMath.median(Arrays.asList(Double.NaN, 2.0, null)));
        assertTrue(Double.isNaN(PercentileMath.median(List.of())));
    }

    @Test
    void histogramsOnlyCountPixelsInsideRoiAcrossTiles() throws Exception {
        // 2500 x 300 RGB: red = 10 left of x=1200, 20 right of it; green = 5; blue = 0
        int w = 2500, h = 300;
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, ((x < 1200 ? 10 : 20) << 16) | (5 << 8));
        try (var server = new WrappedBufferedImageServer("test", img)) {
            // Rectangle 1000..2200 x 50..150: crosses the 1024-px tile border and the red edge at x=1200
            var roi = ROIs.createRectangleROI(1000, 50, 1200, 100, ImagePlane.getDefaultPlane());
            long[][] hist = AutoScaler.histograms(server, roi, 1.0, () -> false);
            assertEquals(3, hist.length);
            assertEquals(200L * 100, hist[0][10]);
            assertEquals(1000L * 100, hist[0][20]);
            assertEquals(1200L * 100, hist[1][5]);
            assertEquals(1200L * 100, hist[2][0]);
            assertEquals(1200L * 100, Arrays.stream(hist[0]).sum());
        }
    }
}
