package com.jian.scrcpy.media;

public record AudioFrame(int sampleRate, int channels, short[] pcm, long pts) {
}