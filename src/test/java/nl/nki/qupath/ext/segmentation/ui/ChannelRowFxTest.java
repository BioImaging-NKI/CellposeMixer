package nl.nki.qupath.ext.segmentation.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import nl.nki.qupath.ext.segmentation.mixer.ChannelRole;
import nl.nki.qupath.ext.segmentation.mixer.ChannelSetting;
import nl.nki.qupath.ext.segmentation.preview.ChannelStats;
import nl.nki.qupath.ext.segmentation.preview.IntensityScale;
import org.controlsfx.control.RangeSlider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real ChannelRow (needs a display; skipped when headless): slider moves and typed values must not
 * make the range run away.
 */
class ChannelRowFxTest {

    @BeforeAll
    static void startFx() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        var latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (error.get() != null)
            throw new AssertionError(error.get());
        return result.get();
    }

    /** Let a few pulses (layout, skin updates) pass. */
    private static void settle() throws Exception {
        for (int i = 0; i < 5; i++) {
            onFx(() -> null);
            Thread.sleep(30);
        }
    }

    private static <T> T find(javafx.scene.Parent root, Class<T> type, int index) {
        var found = new ArrayList<T>();
        collect(root, type, found);
        return found.get(index);
    }

    private static <T> void collect(javafx.scene.Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node))
            found.add(type.cast(node));
        if (node instanceof javafx.scene.Parent p)
            p.getChildrenUnmodifiable().forEach(c -> collect(c, type, found));
    }

    private static javafx.scene.input.MouseEvent mouse(javafx.event.EventType<javafx.scene.input.MouseEvent> type,
                                                       javafx.scene.Node node, double localX, double localY) {
        var scene = node.localToScene(localX, localY);
        var screen = node.localToScreen(localX, localY);
        return new javafx.scene.input.MouseEvent(type, scene.getX(), scene.getY(), screen.getX(), screen.getY(),
                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, false, false, false, null);
    }

    @ParameterizedTest
    @EnumSource(IntensityScale.class)
    void mouseDragOfHighThumbFollowsTheMouse(IntensityScale scale) throws Exception {
        var setting = new ChannelSetting("DAPI", ChannelRole.NUCLEUS, 100, 2000);
        float[] px = new float[10000];
        for (int i = 0; i < px.length; i++)
            px[i] = i % 5000;
        var stats = ChannelStats.compute(px);
        var log = new ArrayList<Double>();

        var row = onFx(() -> {
            // Same layout as CellposeMixerWindow: rows in a fit-to-width ScrollPane in a SplitPane,
            // status label at the bottom that changes on every value change
            var status = new javafx.scene.control.Label("status");
            var box = new javafx.scene.layout.VBox(1);
            var r = new ChannelRow(setting, 0, scale, () -> {
                log.add(setting.getHigh());
                status.setText(String.format("Preview %d px: mix %d ms (%s)", 1000, log.size(), "x".repeat(log.size() % 40)));
            }, x -> {});
            box.getChildren().add(r.getNode());
            var scroll = new javafx.scene.control.ScrollPane(box);
            scroll.setFitToWidth(true);
            var left = new javafx.scene.layout.VBox(6, new javafx.scene.control.Label("options"), scroll);
            left.setMinWidth(560);
            var split = new javafx.scene.control.SplitPane(left, new javafx.scene.layout.Pane());
            split.setDividerPositions(0.45);
            var root = new javafx.scene.layout.BorderPane(split);
            root.setBottom(new javafx.scene.layout.HBox(8, status));
            var stage = new Stage();
            stage.setScene(new Scene(root, 1350, 400));
            stage.show();
            r.setStats(stats);
            return r;
        });
        settle();
        var slider = find(row.getNode(), RangeSlider.class, 0);
        var thumb = onFx(() -> slider.lookup(".high-thumb"));
        assertNotNull(thumb, "high thumb");

        // Press on the thumb, drag 20 px to the right in steps of 2 px, release
        onFx(() -> { javafx.event.Event.fireEvent(thumb, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, thumb, 3, 3)); return null; });
        settle();
        for (int dx = 2; dx <= 20; dx += 2) {
            int d = dx;
            // Coordinates relative to the thumb's position at press time (the thumb moves while dragging)
            var pressScene = onFx(() -> thumb.localToScene(3, 3));
            onFx(() -> {
                var target = thumb.sceneToLocal(pressScene.getX() + 2, pressScene.getY());
                javafx.event.Event.fireEvent(thumb, mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, thumb, target.getX(), target.getY()));
                return null;
            });
            settle();
        }
        // Now hold the mouse still: repeated drag events at the same screen position must not move the value
        var stillScene = onFx(() -> thumb.localToScene(3, 3));
        double beforeHold = setting.getHigh();
        for (int i = 0; i < 10; i++) {
            onFx(() -> {
                var target = thumb.sceneToLocal(stillScene.getX(), stillScene.getY());
                javafx.event.Event.fireEvent(thumb, mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, thumb, target.getX(), target.getY()));
                return null;
            });
            settle();
        }
        System.out.println(scale + " holding still: " + beforeHold + " -> " + setting.getHigh());
        assertEquals(beforeHold, setting.getHigh(), beforeHold * 0.01, "value moves while the mouse is still");
        onFx(() -> { javafx.event.Event.fireEvent(thumb, mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED, thumb, 3, 3)); return null; });
        settle();

        System.out.println(scale + " high during drag: " + log);
        double high = setting.getHigh();
        assertTrue(Double.isFinite(high), "high is finite: " + high);
        assertTrue(high <= stats.getMax() * 1.01, "high stays within the slider range: " + high);
        settle();
        assertEquals(high, setting.getHigh(), 1e-9, "no change after release");
    }

    /** Regression: listeners kept the scale from construction; switching LOG -> LINEAR gave Infinity halfway. */
    @ParameterizedTest
    @EnumSource(IntensityScale.class)
    void switchingScaleKeepsSliderMappingCorrect(IntensityScale initial) throws Exception {
        var other = initial == IntensityScale.LOG ? IntensityScale.LINEAR : IntensityScale.LOG;
        var setting = new ChannelSetting("DAPI", ChannelRole.NUCLEUS, 100, 2000);
        float[] px = new float[10000];
        for (int i = 0; i < px.length; i++)
            px[i] = i % 5000;
        var stats = ChannelStats.compute(px);
        var row = onFx(() -> {
            var r = new ChannelRow(setting, 0, initial, () -> {}, x -> {});
            r.setStats(stats);
            r.setScale(other);
            return r;
        });
        var slider = find(row.getNode(), RangeSlider.class, 0);
        // Move the high thumb to 3/4 of the slider (above low=100 in both scales)
        onFx(() -> { slider.setHighValue(slider.getMin() + 0.75 * (slider.getMax() - slider.getMin())); return null; });
        double a0 = other.toAxis(stats.getMin()), a1 = other.toAxis(stats.getMax());
        double expected = other.fromAxis(a0 + 0.75 * (a1 - a0));
        assertTrue(Double.isFinite(setting.getHigh()), "finite: " + setting.getHigh());
        assertEquals(expected, setting.getHigh(), expected * 1e-6);
        assertTrue(setting.getHigh() < stats.getMax());
    }

    @ParameterizedTest
    @EnumSource(IntensityScale.class)
    void sliderAndFieldsStayStable(IntensityScale scale) throws Exception {
        var setting = new ChannelSetting("DAPI", ChannelRole.NUCLEUS, 100, 3000);
        float[] px = new float[10000];
        for (int i = 0; i < px.length; i++)
            px[i] = i % 5000;
        var stats = ChannelStats.compute(px);
        int[] changes = {0};

        var row = onFx(() -> {
            var r = new ChannelRow(setting, 0, scale, () -> changes[0]++, x -> {});
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(r.getNode()), 800, 100));
            stage.show();
            r.setStats(stats);
            return r;
        });
        settle();
        assertEquals(100, setting.getLow(), 1e-6, "low after setStats");
        assertEquals(3000, setting.getHigh(), 1e-6, "high after setStats");

        // Simulate dragging the high thumb a bit
        var slider = find(row.getNode(), RangeSlider.class, 0);
        onFx(() -> { slider.setHighValue(scale.toAxis(3500)); return null; });
        settle();
        assertEquals(3500, setting.getHigh(), 1, "high after drag");
        double before = setting.getHigh();
        settle();
        assertEquals(before, setting.getHigh(), 1e-9, "high must not keep changing");

        // Type values and press Enter
        var highField = find(row.getNode(), TextField.class, 1);
        onFx(() -> { highField.setText("4000"); highField.getOnAction().handle(null); return null; });
        settle();
        assertEquals(4000, setting.getHigh(), 1e-9, "high after typing");
        settle();
        assertEquals(4000, setting.getHigh(), 1e-9, "high must not keep changing after typing");
        assertEquals(100, setting.getLow(), 1, "low unchanged");

        // Refresh (as after auto ranges) must not change values
        onFx(() -> { row.refreshFromSetting(); return null; });
        settle();
        assertEquals(4000, setting.getHigh(), 1e-9);
        assertTrue(changes[0] < 10, "too many change events: " + changes[0]);
    }
}
