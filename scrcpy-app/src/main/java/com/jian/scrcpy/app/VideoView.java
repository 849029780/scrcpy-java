package com.jian.scrcpy.app;

import com.jian.scrcpy.media.VideoFrame;
import javafx.application.Platform;
import javafx.scene.image.*;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoView extends ImageView {
    private final AtomicReference<VideoFrame> latest = new AtomicReference<>();
    private boolean scheduled;
    private volatile int frameWidth;
    private volatile int frameHeight;

    public VideoView() {
        setPreserveRatio(true);
        setSmooth(true);
    }

    public void accept(VideoFrame f) {
        frameWidth = f.width();
        frameHeight = f.height();
        latest.set(f);
        synchronized (this) {
            if (scheduled) return;
            scheduled = true;
        }
        Platform.runLater(() -> {
            try {
                VideoFrame v = latest.getAndSet(null);
                if (v != null) {
                    WritableImage image = new WritableImage(v.width(), v.height());
                    image.getPixelWriter().setPixels(0, 0, v.width(), v.height(), PixelFormat.getByteBgraInstance(), v.bgra(), 0, v.width() * 4);
                    setImage(image);
                }
            } finally {
                synchronized (this) {
                    scheduled = false;
                }
                if (latest.get() != null) accept(latest.get());
            }
        });
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }
}