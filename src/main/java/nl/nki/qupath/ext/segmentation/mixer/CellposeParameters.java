package nl.nki.qupath.ext.segmentation.mixer;

/**
 * Cellpose model parameters. Sizes are in µm; conversion to pixels happens when Cellpose is built.
 * Defaults reproduce cellpose_codex.groovy (diameter 16 px at ~0.5 µm/px = 8 µm).
 */
public class CellposeParameters {

    public enum Normalization {
        /** Percentile normalization per tile (original script). */
        PER_TILE,
        /** Percentile normalization computed once over the whole parent annotation. */
        GLOBAL,
        /** No normalization: use the mixed values (already scaled by low/high) as they are. */
        NONE
    }

    private String model = "tissuenet_cp3";
    private double diameterMicrons = 8.0;
    /** Pixel size to run Cellpose at; null = the image's own pixel size. */
    private Double pixelSizeMicrons = null;
    private Normalization normalization = Normalization.PER_TILE;
    private double normLowPercentile = 0.1;
    private double normHighPercentile = 99.8;
    /** Downsample used to compute global normalization percentiles. */
    private double normGlobalDownsample = 10.0;
    private double cellprobThreshold = 0.0;
    private double flowThreshold = 0.4;
    private int tileSize = 1024;
    private boolean measureIntensity = true;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getDiameterMicrons() { return diameterMicrons; }
    public void setDiameterMicrons(double diameterMicrons) { this.diameterMicrons = diameterMicrons; }

    public Double getPixelSizeMicrons() { return pixelSizeMicrons; }
    public void setPixelSizeMicrons(Double pixelSizeMicrons) { this.pixelSizeMicrons = pixelSizeMicrons; }

    public Normalization getNormalization() { return normalization == null ? Normalization.PER_TILE : normalization; }
    public void setNormalization(Normalization normalization) { this.normalization = normalization; }

    public double getNormLowPercentile() { return normLowPercentile; }
    public void setNormLowPercentile(double v) { this.normLowPercentile = v; }

    public double getNormHighPercentile() { return normHighPercentile; }
    public void setNormHighPercentile(double v) { this.normHighPercentile = v; }

    public double getNormGlobalDownsample() { return normGlobalDownsample; }
    public void setNormGlobalDownsample(double v) { this.normGlobalDownsample = v; }

    public double getCellprobThreshold() { return cellprobThreshold; }
    public void setCellprobThreshold(double v) { this.cellprobThreshold = v; }

    public double getFlowThreshold() { return flowThreshold; }
    public void setFlowThreshold(double v) { this.flowThreshold = v; }

    public int getTileSize() { return tileSize; }
    public void setTileSize(int tileSize) { this.tileSize = tileSize; }

    public boolean isMeasureIntensity() { return measureIntensity; }
    public void setMeasureIntensity(boolean measureIntensity) { this.measureIntensity = measureIntensity; }

    /** Diameter in pixels at the given pixel size (µm/px), as Cellpose expects it. */
    public double diameterPixels(double pixelSizeMicrons) {
        return diameterMicrons / pixelSizeMicrons;
    }
}
