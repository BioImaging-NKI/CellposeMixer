package nl.nki.qupath.ext.segmentation.mixer;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelType;
import qupath.opencv.ops.ImageOp;

import java.util.List;

/**
 * Creates the 2-channel Cellpose input from a multichannel image.
 * <p>
 * Output channel 0 = cyto/membrane, channel 1 = nucleus (as in cellpose_codex.groovy, used with
 * {@code cellposeChannels(1, 2)}). Each input channel is scaled as {@code (x - low) / (high - low)},
 * optionally clipped to [0, 1], and channels with the same role are combined by sum or max.
 * A role without channels gives an all-zero output channel.
 * <p>
 * The same op is used for the interactive preview and for the actual Cellpose run.
 */
public class MixChannelsOp implements ImageOp {

    /** One input channel with its scaling. */
    public record ScaledChannel(int index, double low, double high) {
        double scale() { return 1.0 / (high - low); }
        double offset() { return -low / (high - low); }
    }

    public static final String CYTO_CHANNEL_NAME = "Cyto/membrane";
    public static final String NUCLEUS_CHANNEL_NAME = "Nucleus";

    private final List<ScaledChannel> nucleus;
    private final List<ScaledChannel> cyto;
    private final CombineMode combine;
    private final boolean clip;

    public MixChannelsOp(List<ScaledChannel> nucleus, List<ScaledChannel> cyto, CombineMode combine, boolean clip) {
        this.nucleus = List.copyOf(nucleus);
        this.cyto = List.copyOf(cyto);
        this.combine = combine;
        this.clip = clip;
    }

    public List<ScaledChannel> getNucleus() { return nucleus; }
    public List<ScaledChannel> getCyto() { return cyto; }
    public CombineMode getCombine() { return combine; }
    public boolean isClip() { return clip; }

    @Override
    public Mat apply(Mat input) {
        var split = new MatVector();
        opencv_core.split(input, split);
        try {
            var channels = new java.util.ArrayList<Mat>((int) split.size());
            for (long i = 0; i < split.size(); i++)
                channels.add(split.get(i));
            Mat[] mixed = mix(channels);
            Mat output = new Mat();
            try (var merged = new MatVector(mixed)) {
                opencv_core.merge(merged, output);
            }
            mixed[0].close();
            mixed[1].close();
            return output;
        } finally {
            for (long i = 0; i < split.size(); i++)
                split.get(i).close();
            split.close();
        }
    }

    /**
     * Mix already-split single-channel Mats (any depth; all the same size).
     * Used directly by the interactive preview to avoid re-splitting on every change.
     *
     * @param channels one Mat per image channel, indexed by channel index
     * @return {cyto, nucleus} as new CV_32FC1 Mats; the caller must close them
     */
    public Mat[] mix(List<Mat> channels) {
        if (channels.isEmpty())
            throw new IllegalArgumentException("No input channels");
        for (var ch : nucleus) checkIndex(ch, channels.size());
        for (var ch : cyto) checkIndex(ch, channels.size());
        int rows = channels.getFirst().rows(), cols = channels.getFirst().cols();
        return new Mat[] { combine(channels, cyto, rows, cols), combine(channels, nucleus, rows, cols) };
    }

    private static void checkIndex(ScaledChannel ch, int nChannels) {
        if (ch.index() < 0 || ch.index() >= nChannels)
            throw new IllegalArgumentException("Channel index " + ch.index() + " out of range (image has " + nChannels + " channels)");
    }

    private Mat combine(List<Mat> split, List<ScaledChannel> channels, int rows, int cols) {
        Mat acc = null;
        for (var ch : channels) {
            // convertTo gives float output regardless of input depth: x * scale + offset
            Mat scaled = new Mat();
            split.get(ch.index()).convertTo(scaled, opencv_core.CV_32F, ch.scale(), ch.offset());
            if (clip) {
                opencv_imgproc.threshold(scaled, scaled, 1.0, 1.0, opencv_imgproc.THRESH_TRUNC);
                opencv_imgproc.threshold(scaled, scaled, 0.0, 0.0, opencv_imgproc.THRESH_TOZERO);
            }
            if (acc == null) {
                acc = scaled;
                continue;
            }
            if (combine == CombineMode.MAX)
                opencv_core.max(acc, scaled, acc);
            else
                opencv_core.add(acc, scaled, acc);
            scaled.close();
        }
        if (acc == null)
            acc = new Mat(rows, cols, opencv_core.CV_32FC1, Scalar.all(0));
        return acc;
    }

    @Override
    public List<ImageChannel> getChannels(List<ImageChannel> channels) {
        return List.of(
                ImageChannel.getInstance(CYTO_CHANNEL_NAME, ColorTools.packRGB(255, 0, 255)),
                ImageChannel.getInstance(NUCLEUS_CHANNEL_NAME, ColorTools.packRGB(0, 255, 0)));
    }

    @Override
    public PixelType getOutputType(PixelType inputType) {
        return PixelType.FLOAT32;
    }
}
