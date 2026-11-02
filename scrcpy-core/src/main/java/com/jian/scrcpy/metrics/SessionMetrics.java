package com.jian.scrcpy.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-device transport/audio/device telemetry. A single shared daemon scheduler
 * reports all active sessions; no reporting thread is created per device.
 * Counters are interval deltas, while last-event ages and queue depth are gauges.
 */
public final class SessionMetrics implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SessionMetrics.class);
    private static final ScheduledExecutorService REPORTER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "scrcpy-session-metrics");
                thread.setDaemon(true);
                return thread;
            });

    private final String serial;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicInteger audioQueueDepth = new AtomicInteger();
    private final AtomicInteger audioQueueMaxDepth = new AtomicInteger();
    private final AtomicLong lastAudioTcpNanos = new AtomicLong();
    private final AtomicLong lastPcmNanos = new AtomicLong();
    private final AtomicLong lastPlayerWriteNanos = new AtomicLong();
    private volatile String displayState = "unknown";
    private volatile String transportState = "ok";
    private final ScheduledFuture<?> reportTask;

    private final LongAdder audioTcpBytes = new LongAdder();
    private final LongAdder audioTcpReads = new LongAdder();
    private final LongAdder audioPackets = new LongAdder();
    private final LongAdder audioConfigPackets = new LongAdder();
    private final LongAdder audioPacketBytes = new LongAdder();
    private final LongAdder audioQueueAccepted = new LongAdder();
    private final LongAdder audioQueueEvicted = new LongAdder();
    private final LongAdder audioQueueDropped = new LongAdder();
    private final LongAdder audioDecodePackets = new LongAdder();
    private final LongAdder audioDecodeErrors = new LongAdder();
    private final LongAdder pcmFrames = new LongAdder();
    private final LongAdder pcmSamples = new LongAdder();
    private final LongAdder pcmBytes = new LongAdder();
    private final LongAdder audioOutputErrors = new LongAdder();
    private final LongAdder playerWriteBytes = new LongAdder();
    private final LongAdder playerWriteErrors = new LongAdder();
    private final LongAdder playerOpens = new LongAdder();
    private final LongAdder transportErrors = new LongAdder();

    public SessionMetrics(String serial) {
        this.serial = serial == null || serial.isBlank() ? "unknown" : serial;
        reportTask = REPORTER.scheduleAtFixedRate(this::reportSafely, 5, 5, TimeUnit.SECONDS);
    }

    public void setConnected(boolean value) { connected.set(value); }
    public void setDisplayOn(boolean value) { displayState = value ? "on" : "off"; }
    public void recordTransportFailure() { transportErrors.increment(); transportState = "error"; }

    public void recordAudioTcp(int bytes) {
        if (bytes <= 0) return;
        audioTcpBytes.add(bytes);
        audioTcpReads.increment();
        lastAudioTcpNanos.set(System.nanoTime());
    }

    public void recordAudioPacket(boolean config, int bytes) {
        audioPackets.increment();
        if (config) audioConfigPackets.increment();
        else audioPacketBytes.add(Math.max(0, bytes));
    }

    public void recordAudioQueue(boolean accepted, boolean evicted, int depth) {
        if (accepted) audioQueueAccepted.increment();
        if (evicted) audioQueueEvicted.increment();
        if (!accepted) audioQueueDropped.increment();
        updateAudioQueueDepth(depth);
    }

    public void updateAudioQueueDepth(int depth) {
        int value = Math.max(0, depth);
        audioQueueDepth.set(value);
        audioQueueMaxDepth.accumulateAndGet(value, Math::max);
    }

    public void recordAudioDecodePacket() { audioDecodePackets.increment(); }
    public void recordAudioDecodeError() { audioDecodeErrors.increment(); }

    public void recordPcm(int samples, int bytes) {
        pcmFrames.increment();
        pcmSamples.add(Math.max(0, samples));
        pcmBytes.add(Math.max(0, bytes));
        lastPcmNanos.set(System.nanoTime());
    }

    public void recordAudioOutputError() { audioOutputErrors.increment(); }
    public void recordPlayerWrite(int bytes) {
        if (bytes > 0) {
            playerWriteBytes.add(bytes);
            lastPlayerWriteNanos.set(System.nanoTime());
        }
    }
    public void recordPlayerWriteError() { playerWriteErrors.increment(); }
    public void recordPlayerOpen() { playerOpens.increment(); }

    private static long interval(LongAdder counter) { return counter.sumThenReset(); }
    private static long ageMillis(AtomicLong timestamp) {
        long value = timestamp.get();
        return value == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - value);
    }

    private void reportSafely() {
        try { report(false); }
        catch (Throwable error) {
            LOG.error("[scrcpy-java][{}] metrics report failed", serial, error);
        }
    }

    private synchronized void report(boolean finalReport) {
        long tcpBytes = interval(audioTcpBytes);
        long tcpReads = interval(audioTcpReads);
        long packets = interval(audioPackets);
        long configPackets = interval(audioConfigPackets);
        long packetBytes = interval(audioPacketBytes);
        long queueAccepted = interval(audioQueueAccepted);
        long queueEvicted = interval(audioQueueEvicted);
        long queueDropped = interval(audioQueueDropped);
        long decodePackets = interval(audioDecodePackets);
        long decodeErrors = interval(audioDecodeErrors);
        long frames = interval(pcmFrames);
        long samples = interval(pcmSamples);
        long pcmByteCount = interval(pcmBytes);
        long outputErrors = interval(audioOutputErrors);
        long writeBytes = interval(playerWriteBytes);
        long writeErrors = interval(playerWriteErrors);
        long opens = interval(playerOpens);
        long transportErrorCount = interval(transportErrors);
        int depth = audioQueueDepth.get();
        int maxDepth = audioQueueMaxDepth.getAndSet(depth);
        if (!finalReport && tcpBytes == 0 && packets == 0 && decodePackets == 0
                && frames == 0 && writeBytes == 0 && transportErrorCount == 0) {
            // Still report a quiet session: this is useful evidence when the
            // device is connected but the audio socket has stopped delivering.
        }
        LOG.debug(String.format(Locale.ROOT,
                "[scrcpy-java][%s] %s stats: connected=%s, display=%s, transport=%s, transportErrors=%d, "
                        + "audio{tcpBytes=%d,tcpReads=%d,lastTcpAgoMs=%d,packets=%d,configPackets=%d,packetBytes=%d,"
                        + "queueAccepted=%d,queueEvicted=%d,queueDropped=%d,queue=%d/%d,"
                        + "decodePackets=%d,decodeErrors=%d,pcmFrames=%d,pcmSamples=%d,pcmBytes=%d,lastPcmAgoMs=%d,"
                        + "outputErrors=%d,playerWriteBytes=%d,playerWriteErrors=%d,playerOpens=%d,lastWriteAgoMs=%d}%n",
                serial, finalReport ? "final device" : "device",
                connected.get(), displayState, transportState, transportErrorCount,
                tcpBytes, tcpReads, ageMillis(lastAudioTcpNanos), packets, configPackets, packetBytes,
                queueAccepted, queueEvicted, queueDropped, depth, maxDepth,
                decodePackets, decodeErrors, frames, samples, pcmByteCount, ageMillis(lastPcmNanos),
                outputErrors, writeBytes, writeErrors, opens, ageMillis(lastPlayerWriteNanos)));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connected.set(false);
        reportTask.cancel(false);
        report(true);
    }
}
