package com.jian.scrcpy.media;

import com.jian.scrcpy.metrics.SessionMetrics;
import javax.sound.sampled.*;
public final class JavaSoundPlayer implements AutoCloseable {
    private SourceDataLine line;
    private int rate, channels;
    private byte[] outputBuffer;
    private final SessionMetrics metrics;

    public JavaSoundPlayer() { this(null); }

    public JavaSoundPlayer(SessionMetrics metrics) { this.metrics = metrics; }

    public synchronized void accept(AudioFrame f) {
        try {
            if (line == null || rate != f.sampleRate() || channels != f.channels()) {
                close();
                open(f.sampleRate(), f.channels());
            }
            short[] pcm = f.pcm();
            int bytes = pcm.length * 2;
            if (outputBuffer == null || outputBuffer.length < bytes) {
                outputBuffer = new byte[bytes];
            }
            for (int i = 0; i < pcm.length; i++) {
                short s = pcm[i];
                int p = i << 1;
                outputBuffer[p] = (byte) s;
                outputBuffer[p + 1] = (byte) (s >>> 8);
            }
            try {
                line.write(outputBuffer, 0, bytes);
                if (metrics != null) metrics.recordPlayerWrite(bytes);
            } catch (RuntimeException outputFailure) {
                if (metrics != null) metrics.recordPlayerWriteError();
                // A disconnected/reconfigured Windows audio endpoint can leave
                // SourceDataLine unusable. Close it now; the next frame retries
                // opening the current default output device.
                close();
                throw outputFailure;
            }
        } catch (LineUnavailableException e) {
            if (metrics != null) metrics.recordPlayerWriteError();
            close();
            throw new IllegalStateException(e);
        }
    }

    private void open(int r, int c) throws LineUnavailableException {
        rate = r;
        channels = c;
        var f = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, r, 16, c, c * 2, r, false);
        line = AudioSystem.getSourceDataLine(f);
        line.open(f, Math.max(4096, r * c / 10));
        line.start();
        if (metrics != null) metrics.recordPlayerOpen();
    }

    public synchronized void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
            outputBuffer = null;
        }
    }
}