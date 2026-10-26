package com.jian.scrcpy.media;

import javax.sound.sampled.*;
public final class JavaSoundPlayer implements AutoCloseable {
    private SourceDataLine line;
    private int rate, channels;
    private byte[] outputBuffer;

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
            line.write(outputBuffer, 0, bytes);
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
            outputBuffer = null;
        }
    }
}