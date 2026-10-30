package com.jian.scrcpy.swing;

import com.jian.scrcpy.control.DeviceController;
import com.jian.scrcpy.media.VideoFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Swing video surface. Windows sessions may display FFmpeg D3D11VA textures
 * through the heavyweight native D3D11 Canvas. Software frames retain the
 * portable BGRA/Java2D path as a fallback and for non-Windows platforms.
 */
public final class SwingVideoSurface extends JComponent implements AutoCloseable {
    private final AtomicReference<VideoFrame> latest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile Runnable frameSizeListener = () -> {};
    private BufferedImage image;
    private int[] pixels;
    private volatile Component nativeCanvas;

    public SwingVideoSurface() {
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        setPreferredSize(new Dimension(360, 680));
        setDoubleBuffered(false);
        setLayout(new BorderLayout());
    }

    void installNativeCanvas(Component canvas) {
        if (nativeCanvas != null) throw new IllegalStateException("Native D3D11 Canvas already installed");
        nativeCanvas = canvas;
        add(canvas, BorderLayout.CENTER);
        revalidate();
    }

    void showHardwareFrame(int width, int height) {
        int oldWidth = frameWidth;
        int oldHeight = frameHeight;
        frameWidth = width;
        frameHeight = height;
        Component canvas = nativeCanvas;
        if (canvas != null && !canvas.isVisible()) {
            SwingUtilities.invokeLater(() -> {
                if (nativeCanvas == canvas) canvas.setVisible(true);
            });
        }
        if (oldWidth != width || oldHeight != height) {
            SwingUtilities.invokeLater(frameSizeListener);
        }
    }

    public void setFrameSizeListener(Runnable listener) {
        frameSizeListener = listener == null ? () -> {} : listener;
    }

    public void accept(VideoFrame frame) {
        if (frame == null) return;
        Component canvas = nativeCanvas;
        if (canvas != null && canvas.isVisible()) {
            SwingUtilities.invokeLater(() -> {
                if (nativeCanvas == canvas) canvas.setVisible(false);
            });
        }
        int oldWidth = frameWidth;
        int oldHeight = frameHeight;
        frameWidth = frame.width();
        frameHeight = frame.height();
        VideoFrame replaced = latest.getAndSet(frame);
        if (replaced != null) replaced.close();
        if (oldWidth != frameWidth || oldHeight != frameHeight) {
            SwingUtilities.invokeLater(frameSizeListener);
        }
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (scheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::drainLatest);
        }
    }

    private void drainLatest() {
        try {
            VideoFrame frame = latest.getAndSet(null);
            if (frame == null) return;
            try {
                if (image == null || image.getWidth() != frame.width() || image.getHeight() != frame.height()) {
                    image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
                    pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                }
                ByteBuffer bytes = frame.bgra().duplicate();
                bytes.clear();
                IntBuffer ints = bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                    ints.get(pixels, 0, Math.min(pixels.length, ints.remaining()));
                } else {
                    for (int i = 0; i < pixels.length && bytes.remaining() >= 4; i++) {
                        int b = bytes.get() & 0xff;
                        int g = bytes.get() & 0xff;
                        int r = bytes.get() & 0xff;
                        int a = bytes.get() & 0xff;
                        pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            } finally {
                frame.close();
            }
            repaint();
        } finally {
            scheduled.set(false);
            if (latest.get() != null) scheduleDrain();
        }
    }

    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }

    /**
     * Aspect-fit transform: scale the complete device frame as large as
     * possible inside the viewport without cropping or distorting it.
     */
    private Rectangle renderedBounds(int fw, int fh) {
        int cw = getWidth();
        int ch = getHeight();
        if (fw <= 0 || fh <= 0 || cw <= 0 || ch <= 0) return new Rectangle();
        double scale = Math.min(cw / (double) fw, ch / (double) fh);
        int width = Math.max(1, (int) Math.round(fw * scale));
        int height = Math.max(1, (int) Math.round(fh * scale));
        return new Rectangle((cw - width) / 2, (ch - height) / 2, width, height);
    }

    public DeviceController.ScreenPosition position(double x, double y) {
        int fw = frameWidth;
        int fh = frameHeight;
        Rectangle bounds = renderedBounds(fw, fh);
        // Ignore clicks in letterbox areas: only the actual rendered image
        // maps to Android coordinates.
        if (bounds.isEmpty() || x < bounds.x || y < bounds.y
                || x >= bounds.x + bounds.width || y >= bounds.y + bounds.height) return null;
        int px = Math.min(fw - 1, Math.max(0,
                (int) ((x - bounds.x) * fw / bounds.width)));
        int py = Math.min(fh - 1, Math.max(0,
                (int) ((y - bounds.y) * fh / bounds.height)));
        return new DeviceController.ScreenPosition(px, py, fw, fh);
    }

    /**
     * Edge detection must use the visible video rectangle, not the whole
     * Swing component. The primary layout can leave horizontal letterboxing
     * inside the component after the panel is expanded.
     */
    public boolean isAtImageEdge(double x, double y, int margin) {
        Rectangle bounds = renderedBounds(frameWidth, frameHeight);
        if (bounds.isEmpty()) return true;
        int safeMargin = Math.max(0, margin);
        int right = bounds.x + bounds.width;
        int bottom = bounds.y + bounds.height;
        return x < bounds.x || y < bounds.y || x >= right || y >= bottom
                || x <= bounds.x + safeMargin || y <= bounds.y + safeMargin
                || x >= right - safeMargin || y >= bottom - safeMargin;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        BufferedImage current = image;
        if (current == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Explicitly clear the complete surface before drawing this
            // session's frame; this prevents stale pixels after reparenting.
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Rectangle bounds = renderedBounds(current.getWidth(), current.getHeight());
            if (!bounds.isEmpty()) {
                g.drawImage(current, bounds.x, bounds.y, bounds.width, bounds.height, null);
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public void close() {
        VideoFrame pending = latest.getAndSet(null);
        if (pending != null) pending.close();
        image = null;
        pixels = null;
    }
}
