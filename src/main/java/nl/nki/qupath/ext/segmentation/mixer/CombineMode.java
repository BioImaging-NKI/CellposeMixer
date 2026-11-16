package nl.nki.qupath.ext.segmentation.mixer;

/** How scaled channels with the same role are combined into one channel. */
public enum CombineMode {
    /** Pixel-wise sum (the original CODEX script behaviour). */
    SUM,
    /** Pixel-wise maximum. */
    MAX
}
