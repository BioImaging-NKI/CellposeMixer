package nl.nki.qupath.ext.segmentation.preview;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import qupath.lib.images.ImageData;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The pixels of one annotation (its bounding box), read once at a downsample so the longest side is at most
 * {@code maxSize} pixels, and kept in memory as one Mat per channel for fast re-mixing.
 */
public final class PreviewRegion implements AutoCloseable {

    private final RegionRequest request;
    private final ROI roi;
    private final List<Mat> channels;
    private final List<ChannelStats> stats;
    private final long readMillis;

    private PreviewRegion(RegionRequest request, ROI roi, List<Mat> channels, List<ChannelStats> stats, long readMillis) {
        this.request = request;
        this.roi = roi;
        this.channels = channels;
        this.stats = stats;
        this.readMillis = readMillis;
    }

    /** Read the region. Slow for big regions / many channels: call from a background thread. */
    public static PreviewRegion read(ImageData<BufferedImage> imageData, ROI roi, int maxSize) throws IOException {
        var server = imageData.getServer();
        double downsample = Math.max(1.0, Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) / maxSize);
        var request = RegionRequest.createInstance(server.getPath(), downsample, roi);

        long t0 = System.nanoTime();
        BufferedImage img = server.readRegion(request);
        Mat mat = OpenCVTools.imageToMat(img);
        var channels = splitChannels(mat);
        mat.close();

        var stats = new ArrayList<ChannelStats>(channels.size());
        for (Mat ch : channels)
            stats.add(ChannelStats.compute(OpenCVTools.extractPixels(ch, (float[]) null)));
        long readMillis = (System.nanoTime() - t0) / 1_000_000;
        return new PreviewRegion(request, roi, channels, stats, readMillis);
    }

    /**
     * Split a multichannel Mat into independent single-channel Mats that stay valid on their own.
     * (MatVector.get returns a reference into the vector, which dangles once the vector is closed: clone.)
     */
    static List<Mat> splitChannels(Mat mat) {
        var channels = new ArrayList<Mat>(mat.channels());
        try (var split = new MatVector()) {
            opencv_core.split(mat, split);
            for (long i = 0; i < split.size(); i++)
                channels.add(split.get(i).clone());
        }
        return channels;
    }

    public RegionRequest getRequest() { return request; }
    public ROI getRoi() { return roi; }
    public double getDownsample() { return request.getDownsample(); }
    /** One single-channel Mat per image channel. Do not close. */
    public List<Mat> getChannels() { return channels; }
    public List<ChannelStats> getStats() { return stats; }
    public int getWidth() { return channels.getFirst().cols(); }
    public int getHeight() { return channels.getFirst().rows(); }
    public long getReadMillis() { return readMillis; }

    /** Image x coordinate (full resolution) to preview pixel x. */
    public double toPreviewX(double x) { return (x - request.getX()) / request.getDownsample(); }
    public double toPreviewY(double y) { return (y - request.getY()) / request.getDownsample(); }

    @Override
    public void close() {
        channels.forEach(Mat::close);
    }
}
