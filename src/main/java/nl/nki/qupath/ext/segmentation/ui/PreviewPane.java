package nl.nki.qupath.ext.segmentation.ui;

import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Affine;

import java.util.List;

/**
 * Zoomable/pannable view of the preview image with an outline overlay.
 * Scroll wheel = zoom around the mouse position, drag = pan, double-click = fit.
 * <p>
 * The image (in preview pixel coordinates) is placed with one affine transform; the outline overlay is
 * screen-sized and drawn with the same mapping:
 * {@code screen = zoom * content + (tx, ty)}.
 */
public class PreviewPane extends Pane {

    /** A closed polygon in preview pixel coordinates. */
    public record Outline(double[] xs, double[] ys, Color color) {}

    private static final double ZOOM_STEP = 1.2;

    private final ImageView imageView = new ImageView();
    private final Canvas overlay = new Canvas();
    private final Group content = new Group(imageView);
    private final Affine transform = new Affine();
    private List<Outline> outlines = List.of();
    private double zoom = 1, tx = 0, ty = 0;
    private double dragStartX, dragStartY, dragStartTx, dragStartTy;
    private boolean needsFit = true;

    public PreviewPane() {
        imageView.setSmooth(false);
        content.getTransforms().add(transform);
        content.setManaged(false);
        // Overlay is screen-sized and not transformed, so outlines stay crisp 1-pixel lines at any zoom
        overlay.setManaged(false);
        overlay.setMouseTransparent(true);
        getChildren().addAll(content, overlay);
        setStyle("-fx-background-color: black;");
        setMinSize(100, 100);

        // Don't draw outside the pane
        var clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        addEventHandler(ScrollEvent.SCROLL, this::handleScroll);
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            dragStartX = e.getX();
            dragStartY = e.getY();
            dragStartTx = tx;
            dragStartTy = ty;
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            tx = dragStartTx + e.getX() - dragStartX;
            ty = dragStartTy + e.getY() - dragStartY;
            applyTransform();
            drawOutlines();
        });
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
                fit();
        });
        // Fit once the pane has a size (e.g. first image arrived before the window was laid out)
        widthProperty().addListener((v, o, n) -> fitIfNeeded());
        heightProperty().addListener((v, o, n) -> fitIfNeeded());
        overlay.widthProperty().bind(widthProperty());
        overlay.heightProperty().bind(heightProperty());
        overlay.widthProperty().addListener((v, o, n) -> drawOutlines());
        overlay.heightProperty().addListener((v, o, n) -> drawOutlines());
    }

    /** Set a new image; keeps the view if the size didn't change, otherwise fits. */
    public void setImage(Image image) {
        var old = imageView.getImage();
        boolean sameSize = old != null && image != null
                && old.getWidth() == image.getWidth() && old.getHeight() == image.getHeight();
        imageView.setImage(image);
        if (!sameSize) {
            needsFit = true;
            fitIfNeeded();
        }
        drawOutlines();
    }

    public void setOutlines(List<Outline> outlines) {
        this.outlines = outlines == null ? List.of() : outlines;
        drawOutlines();
    }

    /** Fit the whole image in the pane, centered. */
    public void fit() {
        var img = imageView.getImage();
        if (img == null || getWidth() <= 0 || getHeight() <= 0)
            return;
        zoom = Math.min(getWidth() / img.getWidth(), getHeight() / img.getHeight());
        tx = (getWidth() - img.getWidth() * zoom) / 2;
        ty = (getHeight() - img.getHeight() * zoom) / 2;
        needsFit = false;
        applyTransform();
        drawOutlines();
    }

    private void fitIfNeeded() {
        if (needsFit)
            fit();
    }

    private void handleScroll(ScrollEvent e) {
        if (imageView.getImage() == null || e.getDeltaY() == 0)
            return;
        double newZoom = Math.clamp(zoom * (e.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP), 0.05, 64);
        // Keep the content point under the mouse fixed: mouse = newZoom * p + newT, with p = (mouse - t) / zoom
        double factor = newZoom / zoom;
        tx = e.getX() - (e.getX() - tx) * factor;
        ty = e.getY() - (e.getY() - ty) * factor;
        zoom = newZoom;
        applyTransform();
        drawOutlines(); // keep lines ~1 screen pixel wide
        e.consume();
    }

    private void applyTransform() {
        transform.setToTransform(zoom, 0, tx, 0, zoom, ty);
    }

    private void drawOutlines() {
        var gc = overlay.getGraphicsContext2D();
        gc.setTransform(1, 0, 0, 1, 0, 0);
        gc.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
        if (imageView.getImage() == null)
            return;
        // Same mapping as the image; line width 1/zoom = 1 screen pixel
        gc.setTransform(zoom, 0, 0, zoom, tx, ty);
        gc.setLineWidth(1.0 / zoom);
        for (var o : outlines) {
            gc.setStroke(o.color());
            gc.strokePolygon(o.xs(), o.ys(), o.xs().length);
        }
    }
}
