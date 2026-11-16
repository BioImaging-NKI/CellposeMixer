package nl.nki.qupath.ext.segmentation.preview;

/**
 * Axis scale for intensities (histograms, range sliders).
 * LOG is a symmetric log, {@code sign(v) * log10(1 + |v|)}: defined for 0 and negative values (float images).
 */
public enum IntensityScale {
    LINEAR, LOG;

    /** Intensity to axis position. */
    public double toAxis(double v) {
        return this == LINEAR ? v : Math.signum(v) * Math.log10(1 + Math.abs(v));
    }

    /** Axis position to intensity. */
    public double fromAxis(double a) {
        return this == LINEAR ? a : Math.signum(a) * (Math.pow(10, Math.abs(a)) - 1);
    }
}
