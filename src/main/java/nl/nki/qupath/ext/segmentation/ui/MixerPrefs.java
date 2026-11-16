package nl.nki.qupath.ext.segmentation.ui;

import javafx.beans.property.BooleanProperty;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/** Persistent preferences of the CellposeMixer (also shown in Edit > Preferences > CellposeMixer). */
public final class MixerPrefs {

    private MixerPrefs() {}

    /** Warn before Cellpose replaces the objects inside an annotation. */
    public static final BooleanProperty WARN_REPLACE_OBJECTS =
            PathPrefs.createPersistentPreference("nki.cellposeMixer.warnReplaceObjects", true);

    /** Log intensity axis for the channel histograms and range sliders. */
    public static final BooleanProperty LOG_INTENSITY_AXIS =
            PathPrefs.createPersistentPreference("nki.cellposeMixer.logIntensityAxis", false);

    public static void addToPreferencePane(QuPathGUI qupath) {
        var item = new PropertyItemBuilder<>(WARN_REPLACE_OBJECTS, Boolean.class)
                .name("Warn before replacing objects")
                .category("CellposeMixer")
                .description("Show a warning before Cellpose replaces the existing objects inside an annotation")
                .build();
        qupath.getPreferencePane().getPropertySheet().getItems().add(item);
    }
}
