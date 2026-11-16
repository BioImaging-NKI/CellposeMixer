package nl.nki.qupath.ext.segmentation.preview;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** CellposeRunner's GLOBAL normalization relies on subtract/multiply taking one value per channel. */
class ImageOpsAssumptionsTest {

    @Test
    void subtractAndMultiplyArePerChannel() {
        var mat = new Mat(4, 4, opencv_core.CV_32FC2, new Scalar(10, 100, 0, 0));
        mat = ImageOps.Core.subtract(2, 50).apply(mat);
        mat = ImageOps.Core.multiply(0.5, 0.1).apply(mat);
        var channels = OpenCVTools.splitChannels(mat);
        assertEquals(4.0f, OpenCVTools.extractPixels(channels.get(0), (float[]) null)[0], 1e-6); // (10-2)*0.5
        assertEquals(5.0f, OpenCVTools.extractPixels(channels.get(1), (float[]) null)[0], 1e-6); // (100-50)*0.1
    }
}
