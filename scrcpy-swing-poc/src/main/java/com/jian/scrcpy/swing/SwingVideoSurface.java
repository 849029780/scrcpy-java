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
 * Swing video surface. It keeps only the newest decoded frame and copies BGRA
 * pixels into a reusable TYPE_INT_ARGB image on the EDT. This is the portable
 * baseline renderer; the native D3D11 experiment remains isolated in this module.
 */
public final class SwingVideoSurface extends JComponent implements AutoCloseable {
    private final AtomicReference<VideoFrame> latest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile Runnable frameSizeListener = () -> {};
    private BufferedImage image;
    private int[] pixels;

    public SwingVideoSurface() {
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        setPreferredSize(new Dimension(360, 680));
        setDoubleBuffered(false);
    }

    public void setFrameSizeListener(Runnable listener) {
        frameSizeListener = listener == null ? () -> {} : listener;
    }

    public void accept(VideoFrame frame) {
        if (frame == null) return;
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

    public DeviceController.ScreenPosition position(double x, double y) {
        int fw = frameWidth;
        int fh = frameHeight;
        int cw = getWidth();
        int ch = getHeight();
        if (fw <= 0 || fh <= 0 || cw <= 0 || ch <= 0) return null;
        double scale = Math.min(cw / (double) fw, ch / (double) fh);
        double shownW = fw * scale;
        double shownH = fh * scale;
        double left = (cw - shownW) / 2.0;
        double top = (ch - shownH) / 2.0;
        if (x < left || y < top || x >= left + shownW || y >= top + shownH) return null;
        int px = Math.min(fw - 1, Math.max(0, (int) ((x - left) / scale)));
        int py = Math.min(fh - 1, Math.max(0, (int) ((y - top) / scale)));
        return new DeviceController.ScreenPosition(px, py, fw, fh);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        BufferedImage current = image;
        if (current == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min(getWidth() / (double) current.getWidth(),
                    getHeight() / (double) current.getHeight());
            int width = Math.max(1, (int) Math.round(current.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(current.getHeight() * scale));
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;
            g.drawImage(current, x, y, width, height, null);
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
