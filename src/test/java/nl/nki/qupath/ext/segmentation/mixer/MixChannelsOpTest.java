package nl.nki.qupath.ext.segmentation.mixer;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UShortIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MixChannelsOpTest {

    private static final int ROWS = 7, COLS = 5, CHANNELS = 4;

    /** Random 16-bit-like intensities, deterministic. */
    private static float[][][] randomData(long seed) {
        var rng = new Random(seed);
        var data = new float[ROWS][COLS][CHANNELS];
        for (var row : data)
            for (var px : row)
                for (int c = 0; c < CHANNELS; c++)
                    px[c] = rng.nextInt(5000);
        return data;
    }

    private static Mat toFloatMat(float[][][] data) {
        var mat = new Mat(ROWS, COLS, opencv_core.CV_32FC(CHANNELS));
        try (FloatIndexer idx = mat.createIndexer()) {
            for (int y = 0; y < ROWS; y++)
                for (int x = 0; x < COLS; x++)
                    for (int c = 0; c < CHANNELS; c++)
                        idx.put(y, x, c, data[y][x][c]);
        }
        return mat;
    }

    private static Mat toUShortMat(float[][][] data) {
        var mat = new Mat(ROWS, COLS, opencv_core.CV_16UC(CHANNELS));
        try (UShortIndexer idx = mat.createIndexer()) {
            for (int y = 0; y < ROWS; y++)
                for (int x = 0; x < COLS; x++)
                    for (int c = 0; c < CHANNELS; c++)
                        idx.put(y, x, c, (int) data[y][x][c]);
        }
        return mat;
    }

    private static float get(Mat mat, int y, int x, int c) {
        try (FloatIndexer idx = mat.createIndexer()) {
            return idx.get(y, x, c);
        }
    }

    /** The maths of cellpose_codex.groovy (AddChannelsOp): sum of (x - low) / (high - low), no clipping. */
    private static double scriptReference(float[] px, List<MixChannelsOp.ScaledChannel> channels) {
        double sum = 0;
        for (var ch : channels)
            sum += (px[ch.index()] - ch.low()) / (ch.high() - ch.low());
        return sum;
    }

    private static final List<MixChannelsOp.ScaledChannel> NUC = List.of(
            new MixChannelsOp.ScaledChannel(0, 100, 3000));
    private static final List<MixChannelsOp.ScaledChannel> CYTO = List.of(
            new MixChannelsOp.ScaledChannel(1, 50, 1000),
            new MixChannelsOp.ScaledChannel(3, 300, 4000));

    @Test
    void sumWithoutClipMatchesOriginalScript() {
        var data = randomData(1);
        var op = new MixChannelsOp(NUC, CYTO, CombineMode.SUM, false);
        var out = op.apply(toFloatMat(data));

        assertEquals(2, out.channels());
        assertEquals(opencv_core.CV_32F, out.depth());
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++) {
                assertEquals(scriptReference(data[y][x], CYTO), get(out, y, x, 0), 1e-4, "cyto at " + y + "," + x);
                assertEquals(scriptReference(data[y][x], NUC), get(out, y, x, 1), 1e-4, "nucleus at " + y + "," + x);
            }
    }

    @Test
    void sixteenBitInputGivesSameResultAsFloat() {
        // BIOP Cellpose converts to float first, but the preview may pass 16-bit data directly
        var data = randomData(2);
        var op = new MixChannelsOp(NUC, CYTO, CombineMode.SUM, false);
        var fromFloat = op.apply(toFloatMat(data));
        var fromShort = op.apply(toUShortMat(data));
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++)
                for (int c = 0; c < 2; c++)
                    assertEquals(get(fromFloat, y, x, c), get(fromShort, y, x, c), 1e-5);
    }

    @Test
    void clipLimitsEachChannelBeforeCombining() {
        var data = randomData(3);
        var op = new MixChannelsOp(NUC, CYTO, CombineMode.SUM, true);
        var out = op.apply(toFloatMat(data));
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++) {
                double expected = 0;
                for (var ch : CYTO)
                    expected += Math.clamp((data[y][x][ch.index()] - ch.low()) / (ch.high() - ch.low()), 0.0, 1.0);
                assertEquals(expected, get(out, y, x, 0), 1e-5);
                float nuc = get(out, y, x, 1);
                assertTrue(nuc >= 0 && nuc <= 1, "clipped nucleus in [0,1]: " + nuc);
            }
    }

    @Test
    void maxTakesLargestScaledValue() {
        var data = randomData(4);
        var op = new MixChannelsOp(NUC, CYTO, CombineMode.MAX, false);
        var out = op.apply(toFloatMat(data));
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++) {
                double expected = Double.NEGATIVE_INFINITY;
                for (var ch : CYTO)
                    expected = Math.max(expected, (data[y][x][ch.index()] - ch.low()) / (ch.high() - ch.low()));
                // Unclipped values can be negative; max must not be floored at 0
                assertEquals(expected, get(out, y, x, 0), 1e-5);
            }
    }

    @Test
    void emptyRoleGivesZeroChannel() {
        var op = new MixChannelsOp(NUC, List.of(), CombineMode.SUM, true);
        var out = op.apply(toFloatMat(randomData(5)));
        assertEquals(2, out.channels());
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++)
                assertEquals(0f, get(out, y, x, 0));
    }

    @Test
    void indexOutOfRangeFails() {
        var op = new MixChannelsOp(List.of(new MixChannelsOp.ScaledChannel(CHANNELS, 0, 1)), List.of(), CombineMode.SUM, true);
        var input = toFloatMat(randomData(6));
        assertThrows(IllegalArgumentException.class, () -> op.apply(input));
    }
}
