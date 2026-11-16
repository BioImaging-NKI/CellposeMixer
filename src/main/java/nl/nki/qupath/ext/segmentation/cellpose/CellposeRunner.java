package nl.nki.qupath.ext.segmentation.cellpose;

import nl.nki.qupath.ext.segmentation.mixer.CellposeParameters;
import nl.nki.qupath.ext.segmentation.mixer.MixChannelsOp;
import nl.nki.qupath.ext.segmentation.mixer.MixerSettings;
import nl.nki.qupath.ext.segmentation.preview.PreviewRegion;
import nl.nki.qupath.ext.segmentation.preview.PreviewRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs BIOP Cellpose on annotations with the CellposeMixer settings.
 * <p>
 * Equivalent to cellpose_codex.groovy: the mixed 2-channel image is passed as preprocessing,
 * {@code cellposeChannels(1, 2)} (cyto/membrane, nucleus). Normalization:
 * <ul>
 *     <li>PER_TILE: {@code normalizePercentiles(low, high)} and Cellpose's own normalization stays on — exactly as
 *         the old script (so effectively normalized twice per tile);</li>
 *     <li>GLOBAL: percentiles of the mixed image over the whole annotation (computed here, because BIOP's global
 *         normalization works on the input channels, before mixing), then {@code noCellposeNormalization()};</li>
 *     <li>NONE: mixed values as they are, {@code noCellposeNormalization()}.</li>
 * </ul>
 * Cellpose replaces all child objects of each annotation (BIOP calls clearChildObjects()).
 * Only touch this class after {@link CellposeCheck#findProblems()} returned no problems.
 */
public final class CellposeRunner {

    private static final Logger logger = LoggerFactory.getLogger(CellposeRunner.class);

    /** Max size (longest side, px) used to estimate global percentiles. */
    private static final int GLOBAL_NORM_MAX_SIZE = 2048;

    private CellposeRunner() {}

    public record Result(int nAnnotations, int nDetections, long millis) {}

    /**
     * Run Cellpose on each annotation (one after the other). Slow: call from a background thread.
     *
     * @throws IllegalArgumentException if the settings don't fit the image (e.g. missing channel, no nucleus)
     */
    public static Result run(ImageData<BufferedImage> imageData, List<PathObject> annotations, MixerSettings settings,
                             java.util.function.Consumer<String> progress) throws IOException {
        var channelNames = imageData.getServer().getMetadata().getChannels().stream().map(c -> c.getName()).toList();
        MixChannelsOp mixOp = settings.createOp(channelNames, true);
        var params = settings.getCellpose();
        double pixelSize = pixelSizeMicrons(imageData, params);

        long t0 = System.currentTimeMillis();
        int nDetections = 0;
        for (int i = 0; i < annotations.size(); i++) {
            var annotation = annotations.get(i);
            progress.accept("Cellpose: annotation " + (i + 1) + "/" + annotations.size());
            var ops = new ArrayList<ImageOp>();
            ops.add(mixOp);
            if (params.getNormalization() == CellposeParameters.Normalization.GLOBAL)
                ops.addAll(globalNormalizationOps(imageData, annotation, mixOp, params));

            var builder = qupath.ext.biop.cellpose.Cellpose2D.builder(params.getModel())
                    .pixelSize(pixelSize)
                    .channels(new int[0]) // all channels in; the mix op selects them (as the old script)
                    .preprocess(ops.toArray(ImageOp[]::new))
                    .tileSize(params.getTileSize())
                    .cellposeChannels(1, 2)
                    .cellprobThreshold(params.getCellprobThreshold())
                    .flowThreshold(params.getFlowThreshold())
                    .diameter(params.diameterPixels(pixelSize));
            if (params.getNormalization() == CellposeParameters.Normalization.PER_TILE)
                builder.normalizePercentiles(params.getNormLowPercentile(), params.getNormHighPercentile());
            else
                builder.noCellposeNormalization();
            if (params.isMeasureIntensity())
                builder.measureIntensity();

            logger.info("Running Cellpose ({}, diameter {} µm = {} px at {} µm/px, normalization {}) on {}",
                    params.getModel(), params.getDiameterMicrons(), params.diameterPixels(pixelSize), pixelSize,
                    params.getNormalization(), annotation);
            builder.build().detectObjects(imageData, List.of(annotation));
            nDetections += (int) annotation.getChildObjects().stream().filter(PathObject::isDetection).count();
        }
        return new Result(annotations.size(), nDetections, System.currentTimeMillis() - t0);
    }

    /**
     * Pixel size to run at: the parameter if set (scripts only), otherwise the image's own.
     * For an image without a pixel size this is 1 (QuPath's uncalibrated default), so the diameter value is
     * effectively in pixels; see {@link #isCalibrated(ImageData)}.
     */
    public static double pixelSizeMicrons(ImageData<BufferedImage> imageData, CellposeParameters params) {
        if (params.getPixelSizeMicrons() != null && params.getPixelSizeMicrons() > 0)
            return params.getPixelSizeMicrons();
        var cal = imageData.getServer().getPixelCalibration();
        return cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : 1.0;
    }

    /** False if the image has no pixel size in µm: the diameter is then used in pixels. */
    public static boolean isCalibrated(ImageData<BufferedImage> imageData) {
        return imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
    }

    /** Subtract/multiply ops that map the annotation's low/high percentiles of each mixed channel to 0/1. */
    private static List<ImageOp> globalNormalizationOps(ImageData<BufferedImage> imageData, PathObject annotation,
                                                        MixChannelsOp mixOp, CellposeParameters params) throws IOException {
        try (var region = PreviewRegion.read(imageData, annotation.getROI(), GLOBAL_NORM_MAX_SIZE)) {
            var mixed = mixOp.mix(region.getChannels());
            double[] lo = new double[2], scale = new double[2];
            for (int c = 0; c < 2; c++) {
                float[] px = OpenCVTools.extractPixels(mixed[c], (float[]) null);
                mixed[c].close();
                double[] p = PreviewRenderer.percentiles(px, region.getWidth(), 0, 0, region.getWidth(), region.getHeight(),
                        params.getNormLowPercentile(), params.getNormHighPercentile());
                lo[c] = p[0];
                scale[c] = 1.0 / (p[1] - p[0]);
            }
            logger.info("Global normalization (cyto, nucleus): low {} {}, high {} {}",
                    lo[0], lo[1], lo[0] + 1 / scale[0], lo[1] + 1 / scale[1]);
            return List.of(ImageOps.Core.subtract(lo), ImageOps.Core.multiply(scale));
        }
    }
}
