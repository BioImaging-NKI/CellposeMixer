package nl.nki.qupath.ext.segmentation.cellpose;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the BIOP Cellpose extension is installed and has a Python environment configured.
 * <p>
 * Only this class (and {@code CellposeRunner}) may touch {@code qupath.ext.biop.*} classes, and only
 * after {@link #isExtensionAvailable()} returned true. Otherwise we'd get a NoClassDefFoundError.
 */
public final class CellposeCheck {

    static final String CELLPOSE_CLASS = "qupath.ext.biop.cellpose.Cellpose2D";

    private CellposeCheck() {}

    /** True if the BIOP Cellpose extension classes can be loaded. */
    public static boolean isExtensionAvailable() {
        try {
            Class.forName(CELLPOSE_CLASS, false, CellposeCheck.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Returns a list of problems that prevent running Cellpose; empty if all is fine.
     */
    public static List<String> findProblems() {
        List<String> problems = new ArrayList<>();
        if (!isExtensionAvailable()) {
            problems.add("The BIOP Cellpose extension is not installed. Install 'QuPath Cellpose extension' "
                    + "via Extensions > Manage extensions (QuPath-BIOP catalog).");
            return problems;
        }
        String python = Biop.pythonPath();
        if (python == null || python.isBlank()) {
            problems.add("No Cellpose Python environment is set. Set it in Edit > Preferences > Cellpose/Omnipose.");
            return problems;
        }
        if (!python.equals(python.strip())) {
            problems.add("The Cellpose Python path has leading/trailing spaces: '" + python
                    + "'. Remove them in Edit > Preferences > Cellpose/Omnipose.");
            return problems;
        }
        try {
            if (!Files.isRegularFile(Path.of(python)))
                problems.add("The Cellpose Python path does not exist: " + python);
        } catch (InvalidPathException e) {
            problems.add("The Cellpose Python path is not a valid path: '" + python + "'");
        }
        return problems;
    }

    /** Human-readable summary for the 'Check Cellpose setup' menu item. */
    public static String summary() {
        var problems = findProblems();
        if (problems.isEmpty())
            return "Cellpose extension found.\nPython: " + Biop.pythonPath();
        return String.join("\n", problems);
    }

    /**
     * Preference key used by the BIOP extension (CellposeExtension.installExtension) to persist the Python path.
     */
    static final String PREF_PYTHON_PATH = "cellposePythonPath";

    /** Isolated so that BIOP classes are only resolved when this nested class is first used. */
    private static final class Biop {
        static String pythonPath() {
            // CellposeSetup is only filled when the BIOP extension's installExtension() has run.
            // Extension install order is not defined, so fall back to the stored preference itself.
            String path = qupath.ext.biop.cellpose.CellposeSetup.getInstance().getCellposePythonPath();
            if (path == null || path.isBlank())
                path = qupath.lib.gui.prefs.PathPrefs.createPersistentPreference(PREF_PYTHON_PATH, "").get();
            return path;
        }
    }
}
