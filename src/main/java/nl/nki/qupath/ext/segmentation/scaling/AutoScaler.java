package nl.nki.qupath.ext.segmentation.scaling;

import nl.nki.qupath.ext.segmentation.mixer.ScalingParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.projects.Project;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Automatic low/high per channel from classified annotations in all project images.
 * Same algorithm as get_cellpose_scaling_anno.groovy:
 * <ol>
 *     <li>per annotation and channel, a 16-bit histogram of the pixels inside the annotation;</li>
 *     <li>low = mean of the pixels in the low percentile band, high = mean of the high band;</li>
 *     <li>per channel, the median over all annotations.</li>
 * </ol>
 * Differences: channels are matched by name across images (the script used the index), annotations are read in
 * tiles (no memory limit on annotation size), and the image open in the viewer is used with its unsaved changes.
 */
public final class AutoScaler {

    private static final Logger logger = LoggerFactory.getLogger(AutoScaler.class);

    /** Tile size (output pixels) for reading annotations. */
    private static final int TILE = 1024;
    private static final int BINS = 65536;

    private AutoScaler() {}

    public interface Progress {
        void update(String message, double fraction);
    }

    public record ChannelResult(String name, double low, double high, int nAnnotations) {
        public boolean isValid() {
            return Double.isFinite(low) && Double.isFinite(high) && high > low;
        }
    }

    public record Result(Map<String, ChannelResult> channels, int nImages, int nAnnotations, List<String> warnings) {}

    /**
     * @param project        the project
     * @param openImageData  image currently open in the viewer (used instead of the saved data for its entry); may be null
     * @param cancelled      checked regularly; when true a {@link CancellationException} is thrown
     */
    public static Result run(Project<BufferedImage> project, ImageData<BufferedImage> openImageData,
                             ScalingParameters params, Progress progress, BooleanSupplier cancelled) throws IOException {
        var openEntry = openImageData == null ? null : project.getEntry(openImageData);
        var entries = project.getImageList();
        Map<String, List<Double>> lows = new LinkedHashMap<>();
        Map<String, List<Double>> highs = new LinkedHashMap<>();
        var warnings = new ArrayList<String>();
        int nImages = 0, nAnnotations = 0;

        for (int e = 0; e < entries.size(); e++) {
            checkCancelled(cancelled);
            var entry = entries.get(e);
            progress.update("Image " + (e + 1) + "/" + entries.size() + ": " + entry.getImageName(), e / (double) entries.size());
            boolean isOpen = entry == openEntry;
            ImageData<BufferedImage> imageData = isOpen ? openImageData : entry.readImageData();
            try {
                var annotations = imageData.getHierarchy().getAnnotationObjects().stream()
                        .filter(a -> params.getClassification().equals(a.getClassification()))
                        .filter(a -> a.getROI() != null && a.getROI().isArea())
                        .toList();
                if (annotations.isEmpty()) {
                    logger.info("Skipping {}: no annotation classified '{}'", entry.getImageName(), params.getClassification());
                    continue;
                }
                var server = imageData.getServer();
                if (server.getPixelType() != PixelType.UINT8 && server.getPixelType() != PixelType.UINT16)
                    warnings.add(entry.getImageName() + ": pixel type " + server.getPixelType()
                            + "; values are rounded and clipped to 0–65535");
                var names = server.getMetadata().getChannels().stream().map(c -> c.getName()).toList();
                nImages++;
                for (int a = 0; a < annotations.size(); a++) {
                    checkCancelled(cancelled);
                    progress.update("Image " + (e + 1) + "/" + entries.size() + ": " + entry.getImageName()
                            + " – annotation " + (a + 1) + "/" + annotations.size(),
                            (e + a / (double) annotations.size()) / entries.size());
                    long[][] hist = histograms(server, annotations.get(a).getROI(), params.getDownsample(), cancelled);
                    nAnnotations++;
                    for (int c = 0; c < names.size(); c++) {
                        double low = PercentileMath.percentileMean(hist[c], params.getLowBandFrom(), params.getLowBandTo(), params.isIgnoreZeros());
                        double high = PercentileMath.percentileMean(hist[c], params.getHighBandFrom(), params.getHighBandTo(), params.isIgnoreZeros());
                        lows.computeIfAbsent(names.get(c), k -> new ArrayList<>()).add(low);
                        highs.computeIfAbsent(names.get(c), k -> new ArrayList<>()).add(high);
                        logger.debug("{} ; {} ; annotation {} ; low: {} ; high: {}", entry.getImageName(), names.get(c), a, low, high);
                    }
                }
            } finally {
                if (!isOpen)
                    closeQuietly(imageData.getServer());
            }
        }
        progress.update("Done", 1.0);

        var result = new LinkedHashMap<String, ChannelResult>();
        for (var name : lows.keySet()) {
            var r = new ChannelResult(name, PercentileMath.median(lows.get(name)), PercentileMath.median(highs.get(name)),
                    lows.get(name).size());
            result.put(name, r);
            if (!r.isValid())
                warnings.add(name + ": no valid range (low " + r.low() + ", high " + r.high() + "); left unchanged");
        }
        if (nAnnotations == 0)
            warnings.add("No annotations classified '" + params.getClassification() + "' found in the project");
        return new Result(result, nImages, nAnnotations, warnings);
    }

    /** One 16-bit histogram per channel of the pixels inside the ROI, reading the ROI in tiles. */
    static long[][] histograms(ImageServer<BufferedImage> server, ROI roi, double downsample, BooleanSupplier cancelled)
            throws IOException {
        int nChannels = server.nChannels();
        long[][] hist = new long[nChannels][BINS];
        int x0 = (int) Math.floor(roi.getBoundsX()), y0 = (int) Math.floor(roi.getBoundsY());
        int x1 = (int) Math.ceil(roi.getBoundsX() + roi.getBoundsWidth());
        int y1 = (int) Math.ceil(roi.getBoundsY() + roi.getBoundsHeight());
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(server.getWidth(), x1);
        y1 = Math.min(server.getHeight(), y1);
        int tileFull = (int) Math.ceil(TILE * downsample);
        boolean isFloat = server.getPixelType().isFloatingPoint();

        for (int y = y0; y < y1; y += tileFull) {
            for (int x = x0; x < x1; x += tileFull) {
                checkCancelled(cancelled);
                var request = RegionRequest.createInstance(server.getPath(), downsample,
                        x, y, Math.min(tileFull, x1 - x), Math.min(tileFull, y1 - y), roi.getZ(), roi.getT());
                BufferedImage img = server.readRegion(request);
                if (img == null)
                    continue;
                int w = img.getWidth(), h = img.getHeight();
                var mask = BufferedImageTools.createROIMask(w, h, roi, request).getRaster().getSamples(0, 0, w, h, 0, (int[]) null);
                var raster = img.getRaster();
                int[] ints = isFloat ? null : new int[w * h];
                float[] floats = isFloat ? new float[w * h] : null;
                for (int c = 0; c < nChannels; c++) {
                    long[] hc = hist[c];
                    if (isFloat) {
                        raster.getSamples(0, 0, w, h, c, floats);
                        for (int i = 0; i < mask.length; i++)
                            if (mask[i] != 0 && !Float.isNaN(floats[i]))
                                hc[Math.clamp(Math.round(floats[i]), 0, BINS - 1)]++;
                    } else {
                        raster.getSamples(0, 0, w, h, c, ints);
                        for (int i = 0; i < mask.length; i++)
                            if (mask[i] != 0)
                                hc[Math.clamp(ints[i], 0, BINS - 1)]++;
                    }
                }
            }
        }
        return hist;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancellationException("Cancelled");
    }

    private static void closeQuietly(ImageServer<?> server) {
        try {
            server.close();
        } catch (Exception e) {
            logger.debug("Could not close server", e);
        }
    }
}
