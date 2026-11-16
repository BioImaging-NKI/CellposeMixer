package nl.nki.qupath.ext.segmentation.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import nl.nki.qupath.ext.segmentation.mixer.CellposeParameters;
import nl.nki.qupath.ext.segmentation.mixer.CombineMode;
import nl.nki.qupath.ext.segmentation.mixer.MixerSettings;
import nl.nki.qupath.ext.segmentation.mixer.MixerSettingsIO;
import nl.nki.qupath.ext.segmentation.preview.PreviewRegion;
import nl.nki.qupath.ext.segmentation.preview.PreviewRenderer;
import nl.nki.qupath.ext.segmentation.preview.IntensityScale;
import nl.nki.qupath.ext.segmentation.scaling.AutoScaler;
import nl.nki.qupath.ext.segmentation.cellpose.CellposeCheck;
import nl.nki.qupath.ext.segmentation.cellpose.CellposeRunner;
import javafx.scene.control.TextField;
import javafx.scene.control.ProgressBar;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.plugins.parameters.ParameterList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The CellposeMixer window: per-channel role + range, and a live preview of the mixed 2-channel image
 * of the selected annotation. The main QuPath viewer remains the view of the original data.
 */
public class CellposeMixerWindow {

    private static final Logger logger = LoggerFactory.getLogger(CellposeMixerWindow.class);
    private static final Color DETECTION_COLOR = Color.YELLOW;
    private static final Color NUCLEUS_COLOR = Color.CYAN;
    private static final Color ANNOTATION_COLOR = Color.WHITE;

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "cellpose-mixer-reader");
        t.setDaemon(true);
        return t;
    });

    private final VBox channelBox = new VBox(1);
    private final PreviewPane preview = new PreviewPane();
    private final Label status = new Label("Select an annotation and click 'Load selected annotation'");
    private final ComboBox<CombineMode> combineCombo = new ComboBox<>();
    private final CheckBox clipCheck = new CheckBox("Clip each channel to 0–1");
    private final ComboBox<CellposeParameters.Normalization> normCombo = new ComboBox<>();
    private final ComboBox<Integer> maxSizeCombo = new ComboBox<>();
    private final CheckBox showDetectionsCheck = new CheckBox("Show detections");
    private final CheckBox syncViewerCheck = new CheckBox("Show focused channel in viewer");
    private final CheckBox logAxisCheck = new CheckBox("Log intensity axis");
    private final ComboBox<Path> historyCombo = new ComboBox<>();
    private final Button saveButton = new Button("Save");
    private final Button autoButton = new Button("Auto ranges…");
    private final Button cancelButton = new Button("Cancel");
    private final ProgressBar progressBar = new ProgressBar();
    private volatile boolean scalingCancelled = false;

    private final ComboBox<String> modelCombo = new ComboBox<>();
    private final TextField diameterField = new TextField();
    private final Label diameterPxLabel = new Label();
    private final Label diameterUnitLabel = new Label("µm");
    private final TextField cellprobField = new TextField();
    private final TextField flowField = new TextField();
    private final TextField tileSizeField = new TextField();
    private final CheckBox measureCheck = new CheckBox("Measure intensity");
    private final Button runButton = new Button("Run Cellpose on selected annotation(s)");

    private ImageData<BufferedImage> imageData;
    private MixerSettings settings = new MixerSettings();
    private List<ChannelRow> rows = List.of();
    private List<String> channelNames = List.of();
    private ChannelRow focusedRow;
    private PreviewRegion region;
    private WritableImage previewImage;
    private boolean dirty = false;
    private int loadGeneration = 0;

    private final AnimationTimer renderTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (dirty) {
                dirty = false;
                renderPreview();
            }
        }
    };

    private final ChangeListener<ImageData<BufferedImage>> imageDataListener = (v, o, n) -> setImageData(n);

    public CellposeMixerWindow(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.setTitle("CellposeMixer");
        stage.setScene(new Scene(buildLayout(), 1350, 820));
        stage.setOnShown(e -> {
            qupath.imageDataProperty().addListener(imageDataListener);
            renderTimer.start();
            setImageData(qupath.getImageData());
        });
        stage.setOnHidden(e -> {
            qupath.imageDataProperty().removeListener(imageDataListener);
            renderTimer.stop();
            setRegion(null);
        });
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    // ---------------------------------------------------------------- layout

    private BorderPane buildLayout() {
        var loadButton = new Button("Load selected annotation");
        loadButton.setOnAction(e -> loadSelectedAnnotation());
        loadButton.setTooltip(new Tooltip("Read the pixels of the selected annotation for the preview"));

        maxSizeCombo.getItems().addAll(512, 1024, 2048, 4096);
        maxSizeCombo.setValue(1024);
        maxSizeCombo.setTooltip(new Tooltip("Longest side of the preview in pixels; larger annotations are downsampled"));

        saveButton.setOnAction(e -> save());
        saveButton.setTooltip(new Tooltip("Save as a new, timestamped settings file in the project"));
        var loadHistoryButton = new Button("Load");
        loadHistoryButton.setOnAction(e -> loadFromHistory());
        historyCombo.setPrefWidth(260);
        historyCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Path p) { return p == null ? "" : p.getFileName().toString(); }
            @Override public Path fromString(String s) { return null; }
        });
        var importButton = new Button("Import old JSON…");
        importButton.setOnAction(e -> importLegacy());
        importButton.setTooltip(new Tooltip("Import a codex_channel_command*.json file from the old scripts"));

        autoButton.setOnAction(e -> autoRanges());
        autoButton.setTooltip(new Tooltip("Set low/high of all channels from annotations with a given classification "
                + "in all project images (as get_cellpose_scaling_anno.groovy)"));
        cancelButton.setOnAction(e -> scalingCancelled = true);
        cancelButton.setVisible(false);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        var toolbar = new FlowPane(8, 6, loadButton, new Label("Preview size"), maxSizeCombo,
                new Separator(javafx.geometry.Orientation.VERTICAL), autoButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                saveButton, new Label("History"), historyCombo, loadHistoryButton, importButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        // Mix options
        combineCombo.getItems().addAll(CombineMode.values());
        combineCombo.setOnAction(e -> { settings.setCombine(combineCombo.getValue()); markDirty(); });
        clipCheck.setOnAction(e -> { settings.setClip(clipCheck.isSelected()); markDirty(); });
        normCombo.getItems().addAll(CellposeParameters.Normalization.values());
        normCombo.setOnAction(e -> { settings.getCellpose().setNormalization(normCombo.getValue()); markDirty(); });
        normCombo.setTooltip(new Tooltip("""
                Normalization Cellpose will apply; the preview shows its effect.
                PER_TILE: percentiles per Cellpose tile (watch for contrast jumps between tiles)
                GLOBAL: percentiles over the whole annotation
                NONE: mixed values as they are (preview shows 0–1)"""));
        showDetectionsCheck.setSelected(true);
        showDetectionsCheck.setOnAction(e -> updateOutlines());
        syncViewerCheck.setTooltip(new Tooltip("Click a channel row: the main viewer shows only that channel, "
                + "with its low–high range as display range. This changes the viewer's Brightness & Contrast settings."));
        syncViewerCheck.setOnAction(e -> syncViewer());

        var options = new FlowPane(10, 6,
                new Label("Combine"), combineCombo, clipCheck,
                new Label("Normalization"), normCombo);
        options.setAlignment(Pos.CENTER_LEFT);
        logAxisCheck.setSelected(MixerPrefs.LOG_INTENSITY_AXIS.get());
        logAxisCheck.setTooltip(new Tooltip("Log scale for the histograms and range sliders (y is always log)"));
        logAxisCheck.setOnAction(e -> {
            MixerPrefs.LOG_INTENSITY_AXIS.set(logAxisCheck.isSelected());
            rows.forEach(r -> r.setScale(intensityScale()));
        });
        var options2 = new FlowPane(10, 6, logAxisCheck, showDetectionsCheck, syncViewerCheck);

        var header = new HBox(6, fixedLabel("Channel", 110), fixedLabel("N   C", 60),
                fixedLabel("Range (histogram of preview region)", ChannelRow.SLIDER_WIDTH),
                fixedLabel("Low", 70), fixedLabel("High", 70));
        header.setPadding(new Insets(2, 4, 2, 4));
        header.setStyle("-fx-font-weight: bold;");

        var channelScroll = new ScrollPane(channelBox);
        channelScroll.setFitToWidth(true);
        var left = new VBox(6, options, options2, new Separator(), header, channelScroll, new Separator(),
                buildCellposePanel());
        VBox.setVgrow(channelScroll, javafx.scene.layout.Priority.ALWAYS);
        left.setPadding(new Insets(6));
        left.setMinWidth(560);

        var split = new SplitPane(left, preview);
        split.setDividerPositions(0.45);

        var legend = new Label("Preview: magenta = cyto/membrane, green = nucleus. Scroll = zoom, drag = pan, double-click = fit.");
        var statusLine = new HBox(8, status, progressBar, cancelButton);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        var bottom = new VBox(2, statusLine, legend);
        bottom.setPadding(new Insets(4, 6, 4, 6));

        var root = new BorderPane(split);
        root.setTop(toolbar);
        root.setBottom(bottom);
        return root;
    }

    private javafx.scene.Node buildCellposePanel() {
        modelCombo.setEditable(true);
        modelCombo.getItems().addAll("tissuenet_cp3", "cyto3", "livecell_cp3", "nuclei");
        modelCombo.setTooltip(new Tooltip("Cellpose 3 model name, or the path to a custom model"));
        modelCombo.valueProperty().addListener((v, o, n) -> {
            if (n != null && !n.isBlank())
                settings.getCellpose().setModel(n.strip());
        });

        bindNumber(diameterField, () -> settings.getCellpose().getDiameterMicrons(), v -> {
            if (v > 0) settings.getCellpose().setDiameterMicrons(v);
        });
        diameterField.setTooltip(new Tooltip("Median cell diameter in µm (converted to pixels for Cellpose)"));
        bindNumber(cellprobField, () -> settings.getCellpose().getCellprobThreshold(),
                v -> settings.getCellpose().setCellprobThreshold(v));
        bindNumber(flowField, () -> settings.getCellpose().getFlowThreshold(),
                v -> settings.getCellpose().setFlowThreshold(v));
        bindNumber(tileSizeField, () -> settings.getCellpose().getTileSize(), v -> {
            if (v >= 64) settings.getCellpose().setTileSize((int) Math.round(v));
            markDirty(); // per-tile preview depends on it
        });
        measureCheck.setOnAction(e -> settings.getCellpose().setMeasureIntensity(measureCheck.isSelected()));
        runButton.setOnAction(e -> runCellpose());
        runButton.setStyle("-fx-font-weight: bold;");

        var grid = new javafx.scene.layout.GridPane(8, 4);
        int r = 0;
        grid.addRow(r++, new Label("Model"), modelCombo, new Label("Tile size"), tileSizeField, new Label("px"));
        var diameterBox = new HBox(4, diameterField, diameterUnitLabel, diameterPxLabel);
        diameterBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(new Label("Diameter"), 0, r);
        grid.add(diameterBox, 1, r++, 4, 1);
        grid.addRow(r++, new Label("Cellprob threshold"), cellprobField, new Label("Flow threshold"), flowField);
        grid.addRow(r, measureCheck);
        for (var f : new TextField[] {diameterField, cellprobField, flowField, tileSizeField})
            f.setPrefColumnCount(5);

        var title = new Label("Cellpose");
        title.setStyle("-fx-font-weight: bold;");
        return new VBox(6, title, grid, runButton);
    }

    /** Text field showing a number; Enter or leaving the field writes it back, invalid input is reverted. */
    private void bindNumber(TextField field, java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter) {
        Runnable commit = () -> {
            try {
                setter.accept(Double.parseDouble(field.getText().strip()));
            } catch (NumberFormatException ex) {
                // revert below
            }
            field.setText(formatNumber(getter.getAsDouble()));
            updateDiameterLabel();
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((v, o, n) -> { if (!n) commit.run(); });
        field.setUserData(getter); // used by refreshCellposePanel
    }

    private void refreshCellposePanel() {
        var p = settings.getCellpose();
        modelCombo.setValue(p.getModel());
        for (var f : new TextField[] {diameterField, cellprobField, flowField, tileSizeField}) {
            if (f.getUserData() instanceof java.util.function.DoubleSupplier getter)
                f.setText(formatNumber(getter.getAsDouble()));
        }
        measureCheck.setSelected(p.isMeasureIntensity());
        updateDiameterLabel();
    }

    private void updateDiameterLabel() {
        diameterPxLabel.setStyle("");
        diameterUnitLabel.setText("µm");
        if (imageData == null) {
            diameterPxLabel.setText("");
            return;
        }
        if (!CellposeRunner.isCalibrated(imageData)) {
            // No pixel size: the value is used as pixels
            diameterUnitLabel.setText("px");
            diameterPxLabel.setText("⚠ Image has no pixel size: diameter is in pixels");
            diameterPxLabel.setStyle("-fx-text-fill: darkorange; -fx-font-weight: bold;");
            return;
        }
        double ps = CellposeRunner.pixelSizeMicrons(imageData, settings.getCellpose());
        diameterPxLabel.setText(String.format("= %.1f px at %.3f µm/px", settings.getCellpose().diameterPixels(ps), ps));
    }

    private static String formatNumber(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ---------------------------------------------------------------- run Cellpose

    private void runCellpose() {
        if (imageData == null) {
            Dialogs.showWarningNotification("CellposeMixer", "Open an image first.");
            return;
        }
        var problems = CellposeCheck.findProblems();
        if (!problems.isEmpty()) {
            Dialogs.showErrorMessage("CellposeMixer", String.join("\n", problems));
            return;
        }
        var annotations = imageData.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                .filter(o -> o.isAnnotation() && o.getROI() != null && o.getROI().isArea())
                .toList();
        if (annotations.isEmpty()) {
            Dialogs.showWarningNotification("CellposeMixer", "Select one or more area annotations first.");
            return;
        }
        try {
            settings.createOp(channelNames, true);
        } catch (IllegalArgumentException e) {
            Dialogs.showErrorMessage("CellposeMixer", e.getMessage());
            return;
        }
        if (!CellposeRunner.isCalibrated(imageData) && !Dialogs.showConfirmDialog("CellposeMixer – no pixel size",
                String.format("This image has no pixel size.%nThe diameter (%s) is used in pixels.%n%nContinue?",
                        formatNumber(settings.getCellpose().getDiameterMicrons()))))
            return;
        if (!confirmReplaceObjects(annotations))
            return;

        var imageDataNow = imageData;
        var settingsCopy = MixerSettingsIO.fromJson(MixerSettingsIO.toJson(settings)); // edits during the run don't apply
        runButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setStatus("Cellpose running…");
        reader.submit(() -> {
            try {
                var result = CellposeRunner.run(imageDataNow, annotations, settingsCopy,
                        msg -> Platform.runLater(() -> setStatus(msg + " (see the QuPath log for Cellpose output)")));
                Platform.runLater(() -> {
                    finishRun();
                    updateOutlines();
                    setStatus(String.format("Cellpose done: %d detections in %d annotation(s) in %.1f s",
                            result.nDetections(), result.nAnnotations(), result.millis() / 1000.0));
                });
            } catch (Exception e) {
                logger.error("Cellpose failed", e);
                Platform.runLater(() -> {
                    finishRun();
                    setStatus("Cellpose failed: " + e.getMessage());
                    Dialogs.showErrorMessage("CellposeMixer", "Cellpose failed:\n" + e.getMessage()
                            + "\n\nSee the QuPath log for details.");
                });
            }
        });
    }

    private void finishRun() {
        runButton.setDisable(false);
        progressBar.setVisible(false);
        progressBar.setProgress(0);
    }

    /** Cellpose removes all child objects of each annotation; warn (unless switched off) when there are any. */
    private boolean confirmReplaceObjects(List<PathObject> annotations) {
        if (!MixerPrefs.WARN_REPLACE_OBJECTS.get())
            return true;
        long nDetections = 0, nAnnotations = 0;
        for (var a : annotations) {
            for (var child : a.getChildObjects()) {
                if (child.isDetection()) nDetections++;
                else nAnnotations++;
            }
        }
        if (nDetections + nAnnotations == 0)
            return true;
        var dontAsk = new CheckBox("Don't show this again (Edit > Preferences > CellposeMixer)");
        var text = new Label(String.format("""
                Cellpose replaces everything inside the selected annotation(s):
                %d detection(s) and %d annotation(s) will be removed.

                Duplicate the image entry first if you want to keep them.""", nDetections, nAnnotations));
        var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("CellposeMixer");
        alert.setHeaderText("Replace existing objects?");
        alert.getDialogPane().setContent(new VBox(10, text, dontAsk));
        boolean ok = alert.showAndWait().filter(b -> b == javafx.scene.control.ButtonType.OK).isPresent();
        if (ok && dontAsk.isSelected())
            MixerPrefs.WARN_REPLACE_OBJECTS.set(false);
        return ok;
    }

    private IntensityScale intensityScale() {
        return logAxisCheck.isSelected() ? IntensityScale.LOG : IntensityScale.LINEAR;
    }

    private static Label fixedLabel(String text, double width) {
        var label = new Label(text);
        label.setPrefWidth(width);
        label.setMinWidth(width);
        return label;
    }

    // ---------------------------------------------------------------- image & settings

    private void setImageData(ImageData<BufferedImage> imageData) {
        this.imageData = imageData;
        setRegion(null);
        preview.setImage(null);
        preview.setOutlines(List.of());
        if (imageData == null) {
            channelNames = List.of();
            rebuildRows();
            setStatus("No image open");
            return;
        }
        channelNames = imageData.getServer().getMetadata().getChannels().stream().map(c -> c.getName()).toList();
        // Keep settings when switching image within the project; load the latest saved ones the first time
        if (settings.getChannels().isEmpty())
            settings = loadLatestOrDefault();
        refreshHistory();
        rebuildRows();
        setStatus("Image: " + imageData.getServer().getMetadata().getName() + " (" + channelNames.size()
                + " channels). Select an annotation and click 'Load selected annotation'.");
    }

    private MixerSettings loadLatestOrDefault() {
        var dir = projectDir();
        if (dir != null) {
            try {
                var latest = MixerSettingsIO.loadLatest(dir);
                if (latest.isPresent()) {
                    logger.info("Loaded CellposeMixer settings saved at {}", latest.get().getSavedAt());
                    return latest.get();
                }
            } catch (Exception e) {
                logger.error("Could not load CellposeMixer settings", e);
                Dialogs.showErrorNotification("CellposeMixer", "Could not load saved settings: " + e.getMessage());
            }
        }
        return new MixerSettings();
    }

    private void rebuildRows() {
        var aligned = settings.alignTo(channelNames);
        var newRows = new ArrayList<ChannelRow>();
        for (int i = 0; i < aligned.size(); i++)
            newRows.add(new ChannelRow(aligned.get(i), i, intensityScale(), this::markDirty, this::focusRow));
        rows = newRows;
        focusedRow = null;
        channelBox.getChildren().setAll(rows.stream().map(ChannelRow::getNode).toList());
        combineCombo.setValue(settings.getCombine());
        clipCheck.setSelected(settings.isClip());
        normCombo.setValue(settings.getCellpose().getNormalization());
        refreshCellposePanel();
        if (region != null)
            applyRegionStats(false);
        markDirty();
    }

    private Path projectDir() {
        var project = qupath.getProject();
        if (project == null || project.getPath() == null)
            return null;
        return project.getPath().getParent();
    }

    private void refreshHistory() {
        var dir = projectDir();
        saveButton.setDisable(dir == null);
        historyCombo.getItems().clear();
        if (dir == null)
            return;
        try {
            historyCombo.getItems().setAll(MixerSettingsIO.listHistory(dir));
            if (!historyCombo.getItems().isEmpty())
                historyCombo.getSelectionModel().selectFirst();
        } catch (IOException e) {
            logger.warn("Could not list settings history", e);
        }
    }

    private void save() {
        var dir = projectDir();
        if (dir == null) {
            Dialogs.showWarningNotification("CellposeMixer", "Settings are saved in the project; open a project first.");
            return;
        }
        try {
            var file = MixerSettingsIO.saveToProject(settings, dir);
            refreshHistory();
            setStatus("Saved " + file.getFileName());
        } catch (IOException e) {
            logger.error("Could not save settings", e);
            Dialogs.showErrorMessage("CellposeMixer", "Could not save settings:\n" + e.getMessage());
        }
    }

    private void loadFromHistory() {
        var file = historyCombo.getValue();
        if (file == null)
            return;
        try {
            settings = MixerSettingsIO.read(file);
            rebuildRows();
            setStatus("Loaded " + file.getFileName());
        } catch (Exception e) {
            logger.error("Could not load {}", file, e);
            Dialogs.showErrorMessage("CellposeMixer", "Could not load " + file.getFileName() + ":\n" + e.getMessage());
        }
    }

    private void importLegacy() {
        var chooser = new FileChooser();
        chooser.setTitle("Import old channel settings (codex_channel_command*.json)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        var dir = projectDir();
        if (dir != null)
            chooser.setInitialDirectory(dir.toFile());
        var file = chooser.showOpenDialog(stage);
        if (file == null)
            return;
        try {
            var result = MixerSettingsIO.importLegacy(Files.readString(file.toPath(), StandardCharsets.UTF_8), channelNames);
            // Keep the current Cellpose parameters; take channels/clip from the file
            result.settings().setCellpose(settings.getCellpose());
            settings = result.settings();
            rebuildRows();
            setStatus("Imported " + file.getName() + " (not saved yet)");
            if (!result.warnings().isEmpty())
                Dialogs.showMessageDialog("CellposeMixer – import", String.join("\n", result.warnings()));
        } catch (Exception e) {
            logger.error("Could not import {}", file, e);
            Dialogs.showErrorMessage("CellposeMixer", "Could not import " + file.getName() + ":\n" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- auto ranges

    private void autoRanges() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("CellposeMixer", "Auto ranges use all images of a project; open a project first.");
            return;
        }
        var sp = settings.getScaling();
        var params = new ParameterList()
                .addStringParameter("classification", "Annotation classification", sp.getClassification(),
                        "Annotations with exactly this classification are used, in all project images")
                .addDoubleParameter("downsample", "Downsample", sp.getDownsample(), "",
                        "1 = full resolution (as the old script)")
                .addDoubleParameter("lowFrom", "Low band from", sp.getLowBandFrom() * 100, "%", "")
                .addDoubleParameter("lowTo", "Low band to", sp.getLowBandTo() * 100, "%",
                        "low = mean intensity of the pixels in this percentile band")
                .addDoubleParameter("highFrom", "High band from", sp.getHighBandFrom() * 100, "%", "")
                .addDoubleParameter("highTo", "High band to", sp.getHighBandTo() * 100, "%",
                        "high = mean intensity of the pixels in this percentile band")
                .addBooleanParameter("ignoreZeros", "Ignore zero pixels", sp.isIgnoreZeros(), "Exclude zero padding");
        if (!GuiTools.showParameterDialog("Auto ranges", params))
            return;
        sp.setClassification(params.getStringParameterValue("classification").strip());
        sp.setDownsample(Math.max(1, params.getDoubleParameterValue("downsample")));
        sp.setLowBandFrom(params.getDoubleParameterValue("lowFrom") / 100);
        sp.setLowBandTo(params.getDoubleParameterValue("lowTo") / 100);
        sp.setHighBandFrom(params.getDoubleParameterValue("highFrom") / 100);
        sp.setHighBandTo(params.getDoubleParameterValue("highTo") / 100);
        sp.setIgnoreZeros(params.getBooleanParameterValue("ignoreZeros"));

        var openImageData = imageData;
        scalingCancelled = false;
        setScalingRunning(true);
        reader.submit(() -> {
            try {
                var result = AutoScaler.run(project, openImageData, sp,
                        (message, fraction) -> Platform.runLater(() -> {
                            setStatus("Auto ranges: " + message);
                            progressBar.setProgress(fraction);
                        }),
                        () -> scalingCancelled);
                Platform.runLater(() -> {
                    setScalingRunning(false);
                    applyAutoRanges(result);
                });
            } catch (java.util.concurrent.CancellationException e) {
                Platform.runLater(() -> {
                    setScalingRunning(false);
                    setStatus("Auto ranges cancelled; nothing changed");
                });
            } catch (Exception e) {
                logger.error("Auto ranges failed", e);
                Platform.runLater(() -> {
                    setScalingRunning(false);
                    setStatus("Auto ranges failed: " + e.getMessage());
                    Dialogs.showErrorMessage("CellposeMixer", "Auto ranges failed:\n" + e.getMessage());
                });
            }
        });
    }

    private void setScalingRunning(boolean running) {
        autoButton.setDisable(running);
        cancelButton.setVisible(running);
        progressBar.setVisible(running);
        progressBar.setProgress(0);
    }

    private void applyAutoRanges(AutoScaler.Result result) {
        int n = 0;
        // Apply to all settings (also channels not in the current image), matched by name
        for (var s : settings.getChannels()) {
            var r = result.channels().get(s.getName());
            if (r != null && r.isValid()) {
                s.setLow(r.low());
                s.setHigh(r.high());
                n++;
            }
        }
        rows.forEach(ChannelRow::refreshFromSetting);
        markDirty();
        var msg = new StringBuilder(String.format("Ranges set for %d channel(s) from %d annotation(s) in %d image(s). Not saved yet.",
                n, result.nAnnotations(), result.nImages()));
        if (!result.warnings().isEmpty())
            msg.append("\n\n").append(String.join("\n", result.warnings()));
        setStatus(String.format("Auto ranges: %d channel(s) from %d annotation(s) in %d image(s) (not saved yet)",
                n, result.nAnnotations(), result.nImages()));
        Dialogs.showMessageDialog("CellposeMixer – auto ranges", msg.toString());
    }

    // ---------------------------------------------------------------- preview region

    private void loadSelectedAnnotation() {
        if (imageData == null) {
            Dialogs.showWarningNotification("CellposeMixer", "Open an image first.");
            return;
        }
        PathObject selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
        if (selected == null || !selected.isAnnotation() || selected.getROI() == null || !selected.getROI().isArea()) {
            Dialogs.showWarningNotification("CellposeMixer", "Select an area annotation first.");
            return;
        }
        var imageDataNow = imageData;
        var roi = selected.getROI();
        int maxSize = maxSizeCombo.getValue();
        int generation = ++loadGeneration;
        setStatus("Reading annotation pixels…");
        reader.submit(() -> {
            try {
                var newRegion = PreviewRegion.read(imageDataNow, roi, maxSize);
                Platform.runLater(() -> {
                    if (generation != loadGeneration || imageDataNow != imageData) {
                        newRegion.close(); // superseded
                        return;
                    }
                    setRegion(newRegion);
                    applyRegionStats(true);
                    updateOutlines();
                    markDirty();
                    setStatus(String.format("Annotation read: %d × %d px at downsample %.2f (%.2f µm/px) in %d ms",
                            newRegion.getWidth(), newRegion.getHeight(), newRegion.getDownsample(),
                            pixelSizeMicrons() * newRegion.getDownsample(), newRegion.getReadMillis()));
                });
            } catch (Exception e) {
                logger.error("Could not read annotation", e);
                Platform.runLater(() -> setStatus("Could not read annotation: " + e.getMessage()));
            }
        });
    }

    private double pixelSizeMicrons() {
        var cal = imageData == null ? null : imageData.getServer().getPixelCalibration();
        return cal != null && cal.hasPixelSizeMicrons() ? cal.getAveragedPixelSizeMicrons() : Double.NaN;
    }

    private void setRegion(PreviewRegion newRegion) {
        if (region != null)
            region.close();
        region = newRegion;
    }

    /**
     * Give each row the histogram of the region. Channels that still have the default 0–1 range get an initial
     * range from the region (1st and 99.9th percentile of non-zero pixels).
     */
    private void applyRegionStats(boolean initUntouched) {
        if (region == null || region.getStats().size() != rows.size())
            return;
        for (var row : rows) {
            var stats = region.getStats().get(row.getChannelIndex());
            var s = row.getSetting();
            if (initUntouched && s.getLow() == 0 && s.getHigh() == 1 && stats.getMax() > 1) {
                s.setLow(stats.percentileIgnoringZeros(1));
                s.setHigh(Math.max(stats.percentileIgnoringZeros(99.9), s.getLow() + 1));
            }
            row.setStats(stats);
            row.refreshFromSetting();
        }
    }

    private void markDirty() {
        dirty = true;
        if (focusedRow != null && syncViewerCheck.isSelected())
            syncViewer();
    }

    private void renderPreview() {
        if (region == null)
            return;
        try {
            var op = settings.createOp(channelNames, false);
            var result = PreviewRenderer.render(region, op, settings.getCellpose(), previewImage);
            previewImage = result.image();
            preview.setImage(previewImage);
            String warning = op.getNucleus().isEmpty() ? "  —  no nucleus channel selected" : "";
            setStatus(String.format("Preview %d × %d px: mix %d ms, render %d ms (%d nucleus, %d cyto channels)%s",
                    region.getWidth(), region.getHeight(), result.mixMillis(), result.renderMillis(),
                    op.getNucleus().size(), op.getCyto().size(), warning));
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage().replace('\n', ';'));
        } catch (Exception e) {
            logger.error("Preview failed", e);
            setStatus("Preview failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- overlay

    private void updateOutlines() {
        if (region == null || imageData == null) {
            preview.setOutlines(List.of());
            return;
        }
        var outlines = new ArrayList<PreviewPane.Outline>();
        addOutline(outlines, region.getRoi(), ANNOTATION_COLOR);
        if (showDetectionsCheck.isSelected()) {
            for (var detection : imageData.getHierarchy().getAllDetectionsForROI(region.getRoi())) {
                addOutline(outlines, detection.getROI(), DETECTION_COLOR);
                if (detection instanceof PathCellObject cell && cell.getNucleusROI() != null)
                    addOutline(outlines, cell.getNucleusROI(), NUCLEUS_COLOR);
            }
        }
        preview.setOutlines(outlines);
    }

    private void addOutline(List<PreviewPane.Outline> outlines, ROI roi, Color color) {
        if (roi == null)
            return;
        var it = roi.getShape().getPathIterator(null, 0.5);
        double[] coords = new double[6];
        var xs = new ArrayList<Double>();
        var ys = new ArrayList<Double>();
        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO && !xs.isEmpty()) {
                outlines.add(toOutline(xs, ys, color));
                xs.clear();
                ys.clear();
            }
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                xs.add(region.toPreviewX(coords[0]));
                ys.add(region.toPreviewY(coords[1]));
            }
            it.next();
        }
        if (!xs.isEmpty())
            outlines.add(toOutline(xs, ys, color));
    }

    private static PreviewPane.Outline toOutline(List<Double> xs, List<Double> ys, Color color) {
        return new PreviewPane.Outline(xs.stream().mapToDouble(Double::doubleValue).toArray(),
                ys.stream().mapToDouble(Double::doubleValue).toArray(), color);
    }

    // ---------------------------------------------------------------- focus & viewer sync

    private void focusRow(ChannelRow row) {
        if (focusedRow != null)
            focusedRow.setFocused(false);
        focusedRow = row;
        row.setFocused(true);
        if (syncViewerCheck.isSelected())
            syncViewer();
    }

    /** Show only the focused channel in the main viewer, with the row's low–high as display range. */
    private void syncViewer() {
        if (!syncViewerCheck.isSelected() || focusedRow == null || imageData == null)
            return;
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() != imageData)
            return;
        var display = viewer.getImageDisplay();
        DirectServerChannelInfo target = null;
        for (var info : display.availableChannels()) {
            if (info instanceof DirectServerChannelInfo direct && direct.getChannel() == focusedRow.getChannelIndex())
                target = direct;
        }
        if (target == null)
            return;
        for (var info : List.copyOf(display.selectedChannels())) {
            if (info != target)
                display.setChannelSelected(info, false);
        }
        display.setChannelSelected(target, true);
        var s = focusedRow.getSetting();
        if (s.getHigh() > s.getLow())
            display.setMinMaxDisplay(target, (float) s.getLow(), (float) s.getHigh());
        viewer.repaintEntireImage();
    }

    private void setStatus(String text) {
        status.setText(text);
    }
}
