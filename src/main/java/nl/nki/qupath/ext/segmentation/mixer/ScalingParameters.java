package nl.nki.qupath.ext.segmentation.mixer;

/**
 * Parameters for automatic low/high ranges from classified annotations (port of get_cellpose_scaling_anno.groovy).
 * Saved with the settings so it's clear how the ranges were derived. Percentile bands are fractions (0–1).
 */
public class ScalingParameters {

    private String classification = "for_scaling";
    private double downsample = 1.0;
    private double lowBandFrom = 0.0;
    private double lowBandTo = 0.2;
    private double highBandFrom = 0.95;
    private double highBandTo = 0.999;
    private boolean ignoreZeros = true;

    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }

    public double getDownsample() { return downsample; }
    public void setDownsample(double downsample) { this.downsample = downsample; }

    public double getLowBandFrom() { return lowBandFrom; }
    public void setLowBandFrom(double v) { this.lowBandFrom = v; }

    public double getLowBandTo() { return lowBandTo; }
    public void setLowBandTo(double v) { this.lowBandTo = v; }

    public double getHighBandFrom() { return highBandFrom; }
    public void setHighBandFrom(double v) { this.highBandFrom = v; }

    public double getHighBandTo() { return highBandTo; }
    public void setHighBandTo(double v) { this.highBandTo = v; }

    public boolean isIgnoreZeros() { return ignoreZeros; }
    public void setIgnoreZeros(boolean ignoreZeros) { this.ignoreZeros = ignoreZeros; }
}
