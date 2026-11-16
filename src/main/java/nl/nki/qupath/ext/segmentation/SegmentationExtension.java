package nl.nki.qupath.ext.segmentation;

import javafx.scene.control.MenuItem;
import nl.nki.qupath.ext.segmentation.cellpose.CellposeCheck;
import nl.nki.qupath.ext.segmentation.ui.CellposeMixerWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Entry point of the extension. QuPath finds this class through
 * META-INF/services/qupath.lib.gui.extensions.QuPathExtension.
 */
public class SegmentationExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(SegmentationExtension.class);

    static final String NAME = "CellposeMixer";
    private static final String DESCRIPTION = "Interactive channel mixing for Cellpose segmentation of multiplexed images (NKI)";
    private static final Version QUPATH_VERSION = Version.parse("v0.7.0");

    private boolean isInstalled = false;
    private CellposeMixerWindow mixerWindow;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled)
            return;
        isInstalled = true;

        logger.info("Installing {} ({})", NAME, buildInfo());
        // Defer: other extensions (BIOP Cellpose) may not be installed yet at this point
        javafx.application.Platform.runLater(() -> {
            var cellposeProblems = CellposeCheck.findProblems();
            if (cellposeProblems.isEmpty())
                logger.info("{}: Cellpose setup OK", NAME);
            else
                logger.warn("{}: Cellpose setup problems:\n{}", NAME, String.join("\n", cellposeProblems));
        });

        nl.nki.qupath.ext.segmentation.ui.MixerPrefs.addToPreferencePane(qupath);

        var menu = qupath.getMenu("Extensions>" + NAME, true);

        var mixerItem = new MenuItem("Open CellposeMixer…");
        mixerItem.setOnAction(e -> {
            if (mixerWindow == null)
                mixerWindow = new CellposeMixerWindow(qupath);
            mixerWindow.show();
        });
        menu.getItems().add(mixerItem);

        var checkItem = new MenuItem("Check Cellpose setup");
        checkItem.setOnAction(e -> Dialogs.showMessageDialog(NAME, CellposeCheck.summary()));
        menu.getItems().add(checkItem);

        var aboutItem = new MenuItem("About (build info)");
        aboutItem.setOnAction(e -> Dialogs.showInfoNotification(NAME, buildInfo()));
        menu.getItems().add(aboutItem);
    }

    /** Version + build timestamp, stamped by Gradle (see build.gradle.kts). */
    static String buildInfo() {
        var props = new Properties();
        try (InputStream in = SegmentationExtension.class.getResourceAsStream("build-info.properties")) {
            if (in != null)
                props.load(in);
        } catch (IOException e) {
            logger.warn("Could not read build-info.properties", e);
        }
        return "Version " + props.getProperty("version", "?") + ", built " + props.getProperty("buildTime", "?");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return QUPATH_VERSION;
    }
}
