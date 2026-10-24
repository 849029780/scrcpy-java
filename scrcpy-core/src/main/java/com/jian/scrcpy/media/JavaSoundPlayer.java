package com.jian.scrcpy.media;

import javax.sound.sampled.*;
import java.nio.*;

public final class JavaSoundPlayer implements AutoCloseable {
    private SourceDataLine line;
    private int rate, channels;

    public synchronized void accept(AudioFrame f) {
        try {
            if (line == null || rate != f.sampleRate() || channels != f.channels()) {
                close();
                open(f.sampleRate(), f.channels());
            }
            byte[] b = new byte[f.pcm().length * 2];
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : f.pcm()) bb.putShort(s);
            line.write(b, 0, b.length);
        } catch (LineUnavailableException e) {
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
    }

    public synchronized void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }
}