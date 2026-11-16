package nl.nki.qupath.ext.segmentation.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import nl.nki.qupath.ext.segmentation.mixer.ChannelRole;
import nl.nki.qupath.ext.segmentation.mixer.ChannelSetting;
import nl.nki.qupath.ext.segmentation.preview.ChannelStats;
import nl.nki.qupath.ext.segmentation.preview.IntensityScale;
import org.controlsfx.control.RangeSlider;

import java.util.function.Consumer;

/**
 * One channel: role (nucleus / cyto / ignore), histogram, low–high range slider and numeric fields.
 * Edits are written straight into the {@link ChannelSetting}.
 */
class ChannelRow {

    static final double SLIDER_WIDTH = 240;
    private static final double HIST_HEIGHT = 22;

    private final ChannelSetting setting;
    private final int channelIndex;
    private final HBox node = new HBox(6);
    private final CheckBox nucCheck = new CheckBox("N");
    private final CheckBox cytoCheck = new CheckBox("C");
    private boolean updatingRole = false;
    private final RangeSlider slider = new RangeSlider(0, 1, 0, 1);
    private final Canvas histogram = new Canvas(SLIDER_WIDTH, HIST_HEIGHT);
    private final TextField lowField = new TextField();
    private final TextField highField = new TextField();
    private ChannelStats stats;
    private IntensityScale scale;
    private boolean updating = false;

    ChannelRow(ChannelSetting setting, int channelIndex, IntensityScale initialScale, Runnable onChange, Consumer<ChannelRow> onFocus) {
        this.setting = setting;
        // Not called "scale": the slider listeners must use the field, which setScale() changes
        this.scale = initialScale;
        this.channelIndex = channelIndex;

        var name = new Label(setting.getName());
        name.setPrefWidth(110);
        name.setMinWidth(110);
        name.setTooltip(new Tooltip(setting.getName() + " (channel " + (channelIndex + 1) + ")"));

        nucCheck.setTooltip(new Tooltip("Nucleus"));
        cytoCheck.setTooltip(new Tooltip("Cyto / membrane"));
        nucCheck.setSelected(setting.getRole() == ChannelRole.NUCLEUS);
        cytoCheck.setSelected(setting.getRole() == ChannelRole.CYTO);
        // At most one role: checking one unchecks the other; neither = ignore
        nucCheck.selectedProperty().addListener((v, o, n) -> roleChanged(n, cytoCheck, onChange));
        cytoCheck.selectedProperty().addListener((v, o, n) -> roleChanged(n, nucCheck, onChange));

        slider.setPrefWidth(SLIDER_WIDTH);
        slider.setMaxWidth(SLIDER_WIDTH);
        // The slider works in axis units (linear or log); the setting holds real intensities
        slider.lowValueProperty().addListener((v, o, n) -> {
            if (updating) return;
            setting.setLow(this.scale.fromAxis(n.doubleValue()));
            refreshFields();
            onChange.run();
        });
        slider.highValueProperty().addListener((v, o, n) -> {
            if (updating) return;
            setting.setHigh(this.scale.fromAxis(n.doubleValue()));
            refreshFields();
            onChange.run();
        });

        for (var field : new TextField[] {lowField, highField}) {
            field.setPrefColumnCount(6);
            field.setOnAction(e -> commitFields(onChange));
            field.focusedProperty().addListener((v, o, n) -> { if (!n) commitFields(onChange); });
        }
        lowField.setTooltip(new Tooltip("Low: maps to 0"));
        highField.setTooltip(new Tooltip("High: maps to 1"));

        var sliderBox = new VBox(0, histogram, slider);
        node.getChildren().addAll(name, nucCheck, cytoCheck, sliderBox, lowField, highField);
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(2, 4, 2, 4));
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> onFocus.accept(this));

        updateRange();
        refreshFields();
        updateStyle();
    }

    private void roleChanged(boolean selected, CheckBox other, Runnable onChange) {
        if (updatingRole)
            return;
        updatingRole = true;
        try {
            if (selected)
                other.setSelected(false);
        } finally {
            updatingRole = false;
        }
        setting.setRole(nucCheck.isSelected() ? ChannelRole.NUCLEUS : cytoCheck.isSelected() ? ChannelRole.CYTO : ChannelRole.IGNORE);
        updateStyle();
        onChange.run();
    }

    HBox getNode() { return node; }
    ChannelSetting getSetting() { return setting; }
    int getChannelIndex() { return channelIndex; }

    void setFocused(boolean focused) {
        node.setBorder(focused
                ? new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(Color.DODGERBLUE,
                    javafx.scene.layout.BorderStrokeStyle.SOLID, new javafx.scene.layout.CornerRadii(3),
                    new javafx.scene.layout.BorderWidths(1.5)))
                : null);
    }

    /** Switch between linear and log intensity axis. */
    void setScale(IntensityScale scale) {
        this.scale = scale;
        refreshFromSetting();
    }

    /** Set statistics of the preview region: updates the slider range and histogram. */
    void setStats(ChannelStats stats) {
        this.stats = stats;
        updateRange();
        drawHistogram();
    }

    /** Re-read the setting (e.g. after auto-scaling) and update the controls. */
    void refreshFromSetting() {
        updating = true;
        try {
            updateRange();
        } finally {
            updating = false;
        }
        refreshFields();
        drawHistogram();
    }

    private void updateRange() {
        double max = Math.max(setting.getHigh(), 1);
        double min = Math.min(setting.getLow(), 0);
        if (stats != null) {
            max = Math.max(max, stats.getMax());
            min = Math.min(min, stats.getMin());
        } else {
            max = Math.max(max, setting.getHigh() * 1.5);
        }
        updating = true;
        try {
            slider.setMin(scale.toAxis(min));
            slider.setMax(scale.toAxis(max));
            // RangeSlider keeps low <= high: open up the range first, then set both
            slider.setHighValue(slider.getMax());
            slider.setLowValue(scale.toAxis(setting.getLow()));
            slider.setHighValue(scale.toAxis(setting.getHigh()));
        } finally {
            updating = false;
        }
    }

    private void commitFields(Runnable onChange) {
        try {
            double low = Double.parseDouble(lowField.getText().trim());
            double high = Double.parseDouble(highField.getText().trim());
            if (low == setting.getLow() && high == setting.getHigh())
                return;
            setting.setLow(low);
            setting.setHigh(high);
            refreshFromSetting();
            onChange.run();
        } catch (NumberFormatException e) {
            refreshFields();
        }
    }

    private void refreshFields() {
        lowField.setText(format(setting.getLow()));
        highField.setText(format(setting.getHigh()));
        boolean invalid = !(setting.getHigh() > setting.getLow());
        highField.setStyle(invalid ? "-fx-text-fill: red;" : "");
        drawHistogram();
    }

    private static String format(double v) {
        return Math.abs(v) >= 100 || v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.3g", v);
    }

    private void updateStyle() {
        String color = switch (setting.getRole()) {
            case NUCLEUS -> "rgba(0, 200, 0, 0.12)";
            case CYTO -> "rgba(255, 0, 255, 0.12)";
            default -> "transparent";
        };
        node.setStyle("-fx-background-color: " + color + ";");
    }

    private void drawHistogram() {
        var gc = histogram.getGraphicsContext2D();
        double w = histogram.getWidth(), h = histogram.getHeight();
        gc.clearRect(0, 0, w, h);
        double min = slider.getMin(), max = slider.getMax();
        if (!(max > min))
            return;
        if (stats != null) {
            long[] hist = stats.getHistogram();
            double maxLog = 0;
            for (long c : hist)
                maxLog = Math.max(maxLog, Math.log1p(c));
            if (maxLog > 0) {
                gc.setFill(Color.gray(0.45));
                for (int i = 0; i < hist.length; i++) {
                    if (hist[i] == 0) continue;
                    double x0 = (scale.toAxis(stats.binValue(i)) - min) / (max - min) * w;
                    double x1 = (scale.toAxis(stats.binValue(i + 1)) - min) / (max - min) * w;
                    double bh = Math.log1p(hist[i]) / maxLog * h;
                    gc.fillRect(x0, h - bh, Math.max(1, x1 - x0), bh);
                }
            }
        }
        // low/high markers
        gc.setStroke(Color.DODGERBLUE);
        gc.setLineWidth(1.5);
        for (double v : new double[] {setting.getLow(), setting.getHigh()}) {
            double x = (scale.toAxis(v) - min) / (max - min) * w;
            gc.strokeLine(x, 0, x, h);
        }
    }
}
