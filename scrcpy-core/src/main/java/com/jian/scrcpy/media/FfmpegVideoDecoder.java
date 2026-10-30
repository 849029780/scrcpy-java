package com.jian.scrcpy.media;

import com.jian.scrcpy.protocol.*;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public final class FfmpegVideoDecoder extends SimpleChannelInboundHandler<MediaMessage> implements AutoCloseable {
    private final Consumer<VideoFrame> sink;
    private final String decoderMode;
    private final String serial;
    private final VideoHardwareDeviceProvider hardwareDeviceProvider;
    private final VideoRenderer renderer;
    private AVCodecContext ctx;
    private AVFrame frame, softwareFrame, rgb;
    private AVPacket packet;
    private SwsContext sws;
    private AVBufferRef hwDevice;
    private AVCodecContext.Get_format_AVCodecContext_IntPointer getFormatCallback;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private String activeHardware;
    private CodecId activeCodec;
    private boolean hardwareConfirmed;
    /** Codec announced by the stream. Decoder creation is deferred until the
     * session header so an encoder retry/resized session starts cleanly. */
    private CodecId announcedCodec;
    private int sessionWidth, sessionHeight;
    private int width, height;
    private int pixelFormat = -1;

    // FFmpeg writes swscale output directly into these off-heap buffers. They
    // are then exposed to JavaFX PixelBuffer without the old native -> byte[]
    // full-frame copy. Three buffers cover decode, pending and displayed frames.
    private static final int BGRA_POOL_SIZE = 3;
    private final ArrayDeque<BgraBuffer> bgraPool = new ArrayDeque<>(BGRA_POOL_SIZE);
    private final ArrayList<BgraBuffer> bgraBuffers = new ArrayList<>(BGRA_POOL_SIZE);
    private int bgraBufferSize;

    private static final class BgraBuffer {
        final ByteBuffer buffer;
        final BytePointer pointer;
        // Accessed only while holding the decoder's BGRA-pool monitor.
        boolean leased;
        boolean retired;
        boolean closed;

        BgraBuffer(int size) {
            buffer = ByteBuffer.allocateDirect(size);
            pointer = new BytePointer(buffer);
        }

        void close() {
            if (!closed) {
                closed = true;
                pointer.close();
            }
        }
    }

    private static final boolean DEBUG_SKIP_RENDER =
            Boolean.getBoolean("scrcpy.video.debug.skip-render");
    private static final boolean DEBUG_SKIP_DECODE =
            Boolean.getBoolean("scrcpy.video.debug.skip-decode");
    private static final boolean DEBUG_SKIP_SWS_SCALE =
            Boolean.getBoolean("scrcpy.video.debug.skip-sws-scale");
    // Enabled by default so GPU zero-readback behavior is visible without
    // requiring a JVM flag. Set -Dscrcpy.video.stats=false to silence it.
    private static final boolean VIDEO_STATS_ENABLED =
            Boolean.parseBoolean(System.getProperty("scrcpy.video.stats", "true"));
    // Each FfmpegVideoDecoder owns its AVCodecContext, AVPacket, AVFrames and
    // hardware device. Serialize packet send/receive per decoder, not process-wide,
    // so independent devices can decode concurrently. Initialization, fallback
    // and destruction remain globally serialized as a conservative FFmpeg boundary.
    // Set -Dscrcpy.video.parallel-native=false to restore the legacy global mode.
    private static final boolean PARALLEL_NATIVE_DECODE =
            Boolean.parseBoolean(System.getProperty("scrcpy.video.parallel-native", "true"));
    // Keep software decoding bounded for multi-device sessions. FFmpeg's
    // thread_count=0 auto mode may create a CPU-sized worker pool per decoder,
    // which scales poorly when several devices are connected at once.
    private static final int SOFTWARE_DECODER_THREADS = resolveSoftwareDecoderThreads();
    private static final int FFMPEG_THREAD_SLICE = 2;
    private final AtomicLong statsDecodedFrames = new AtomicLong();
    // hardwareFrames counts frames accepted by the renderer's bounded Java
    // queue; native GPU submission and successful Present are measured separately.
    private final AtomicLong statsHardwareFrames = new AtomicLong();
    private final AtomicLong statsNativePresentationMisses = new AtomicLong();
    private final AtomicLong statsHardwareTransferAttempts = new AtomicLong();
    private final AtomicLong statsHardwareTransferSuccesses = new AtomicLong();
    private final AtomicLong statsHardwareTransferFailures = new AtomicLong();
    private final AtomicLong statsSoftwareFrames = new AtomicLong();
    private final AtomicLong statsQueueDrops = new AtomicLong();
    private final AtomicLong statsPoolMisses = new AtomicLong();
    private final AtomicLong statsScaleNanos = new AtomicLong();
    private final AtomicLong statsScaleCount = new AtomicLong();
    private final AtomicLong statsLockWaitNanos = new AtomicLong();
    private final AtomicLong statsLockHoldNanos = new AtomicLong();
    private final AtomicLong statsLockContentions = new AtomicLong();
    private final AtomicLong statsDeviceLockWaitNanos = new AtomicLong();
    private final AtomicLong statsDeviceLockHoldNanos = new AtomicLong();
    private final AtomicLong statsDeviceLockContentions = new AtomicLong();
    private long statsLastReportNanos = System.nanoTime();

    // Keep the existing global native lock for stability with multi-device
    // decoding. Hardware decoding can be made more parallel later once every
    // backend has been stress-tested independently.
    private static final ReentrantLock FFMPEG_VIDEO_LOCK = new ReentrantLock();
    // A decoder worker exclusively owns its AVCodecContext, AVPacket and
    // AVFrames. This lock is only a defensive per-instance boundary.
    private final ReentrantLock decoderNativeLock = new ReentrantLock();
    // Keep the compressed backlog short. Retained Netty ByteBuf packets count
    // toward process memory and a long queue only adds latency for live video.
    private final BlockingQueue<MediaMessage> decodeQueue = new ArrayBlockingQueue<>(16);
    // Reused staging storage for the JavaCPP AVPacket copy. Directly wrapping a
    // Netty ByteBuf is not portable across the supported platforms/allocators.
    private byte[] encodedBuffer = new byte[0];
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread decodeThread;

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink) {
        this(sink, "hardware", VideoHardwareDeviceProvider.DEFAULT, null, null);
    }

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink, String decoderMode) {
        this(sink, decoderMode, VideoHardwareDeviceProvider.DEFAULT, null, null);
    }

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink, String decoderMode,
                              VideoHardwareDeviceProvider hardwareDeviceProvider) {
        this(sink, decoderMode, hardwareDeviceProvider, null, null);
    }

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink, String decoderMode,
                              VideoHardwareDeviceProvider hardwareDeviceProvider, String serial) {
        this(sink, decoderMode, hardwareDeviceProvider, serial, null);
    }

    public FfmpegVideoDecoder(Consumer<VideoFrame> sink, String decoderMode,
                              VideoHardwareDeviceProvider hardwareDeviceProvider, String serial,
                              VideoRenderer renderer) {
        av_log_set_level(AV_LOG_WARNING);
        super(false);
        this.sink = sink;
        this.serial = serial == null || serial.isBlank() ? "unknown" : serial;
        this.decoderMode = normalizeMode(decoderMode);
        this.hardwareDeviceProvider = Objects.requireNonNullElse(
                hardwareDeviceProvider, VideoHardwareDeviceProvider.DEFAULT);
        this.renderer = renderer != null ? renderer : new VideoRenderer() {
            @Override
            public boolean renderHardware(HardwareVideoFrame frame) {
                return false;
            }

            @Override
            public void renderSoftware(VideoFrame frame) {
                sink.accept(frame);
            }
        };
        decodeThread = Thread.ofVirtual().name("scrcpy-video-decoder").start(this::decodeLoop);
    }

    private static String normalizeMode(String mode) {
        String value = Objects.requireNonNullElse(mode, "hardware").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "hardware", "software" -> value;
            default -> "hardware";
        };
    }

    private static int resolveSoftwareDecoderThreads() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        int defaultThreads = Math.max(1, Math.min(2, available / 4));
        String configured = System.getProperty("scrcpy.video.threads");
        if (configured == null || configured.isBlank()) {
            return defaultThreads;
        }
        try {
            int requested = Integer.parseInt(configured.trim());
            if (requested < 1) {
                System.err.printf("[scrcpy-java] invalid scrcpy.video.threads=%s; using %d%n",
                        configured, defaultThreads);
                return defaultThreads;
            }
            // More decoder threads than logical processors only increases
            // scheduling pressure for this live, multi-device workload.
            return Math.min(requested, available);
        } catch (NumberFormatException e) {
            System.err.printf("[scrcpy-java] invalid scrcpy.video.threads=%s; using %d%n",
                    configured, defaultThreads);
            return defaultThreads;
        }
    }

    private static int avId(CodecId c) {
        return switch (c) {
            case H264 -> AV_CODEC_ID_H264;
            case H265 -> AV_CODEC_ID_HEVC;
            case AV1 -> AV_CODEC_ID_AV1;
            default -> throw new IllegalArgumentException("Not video: " + c);
        };
    }

    private void init(CodecId codecId) {
        closeNative();
        activeCodec = codecId;

        AVCodec decoder = avcodec_find_decoder(avId(codecId));
        if (decoder == null || decoder.isNull()) {
            throw new IllegalStateException("decoder unavailable " + codecId);
        }

        if (!"software".equals(decoderMode)) {
            for (HardwareBackend backend : hardwareBackends()) {
                if (tryInitHardware(decoder, codecId, backend)) {
                    return;
                }
            }
            if ("hardware".equals(decoderMode)) {
                System.out.printf("[scrcpy-java][%s] hardware video decoding unavailable for %s; using software decoder%n", serial, codecId);
            }
        }

        initSoftware(decoder, codecId);
    }

    private void initSoftware(AVCodec decoder, CodecId codecId) {
        ctx = avcodec_alloc_context3(decoder);
        if (ctx != null && !ctx.isNull()) {
            configureCodecContext(ctx, false);
        }
        if (ctx == null || ctx.isNull() || avcodec_open2(ctx, decoder, (AVDictionary) null) < 0) {
            closeNative();
            throw new IllegalStateException("cannot open software decoder " + codecId);
        }
        allocateFramesAndPacket();
        System.out.printf("[scrcpy-java][%s] video decoder: software (%s), threads=%d, threadType=%s%n",
                serial, codecId, ctx.thread_count(),
                (ctx.active_thread_type() & FFMPEG_THREAD_SLICE) != 0 ? "slice" : "serial");
    }

    private boolean tryInitHardware(AVCodec decoder, CodecId codecId, HardwareBackend backend) {
        AVCodecHWConfig selected = null;
        for (int i = 0; ; i++) {
            AVCodecHWConfig config = avcodec_get_hw_config(decoder, i);
            if (config == null || config.isNull()) break;
            if ((config.methods() & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0
                    && config.device_type() == backend.type()) {
                selected = config;
                break;
            }
        }
        if (selected == null) return false;

        // Prefer a renderer-owned hardware device. This is required for
        // zero-copy/cross-API interop on hybrid-GPU systems: the decoder and
        // renderer must address the same physical GPU. Until a renderer
        // supplies one, preserve the existing platform-default FFmpeg path.
        AVBufferRef device = hardwareDeviceProvider.createDeviceContext(backend.type());
        boolean rendererOwnedDevice = device != null && !device.isNull();
        int createResult = 0;
        if (!rendererOwnedDevice) {
            // JavaCPP maps AVBufferRef ** to AVBufferRef directly here.
            device = new AVBufferRef((Pointer) null);
            createResult = av_hwdevice_ctx_create(
                    device, backend.type(), (String) null, (AVDictionary) null, 0);
        }
        if (device == null || device.isNull() || createResult < 0) {
            if (device != null && !device.isNull()) av_buffer_unref(device);
            System.err.printf("[scrcpy-java][%s] cannot create hardware device %s: %d%n", serial, backend.name(), createResult);
            return false;
        }
        if (rendererOwnedDevice) {
            System.out.printf("[scrcpy-java][%s] video decoder: using renderer-owned %s device%n", serial, backend.name());
        }

        AVCodecContext candidate = avcodec_alloc_context3(decoder);
        if (candidate == null || candidate.isNull()) {
            av_buffer_unref(device);
            return false;
        }

        configureCodecContext(candidate, true);

        int selectedPixelFormat = selected.pix_fmt();
        AVCodecContext.Get_format_AVCodecContext_IntPointer callback =
                new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                    @Override
                    public int call(AVCodecContext ignored, IntPointer formats) {
                        StringBuilder offered = new StringBuilder();
                        for (long i = 0; ; i++) {
                            int format = formats.get(i);
                            if (format == AV_PIX_FMT_NONE) break;
                            if (offered.length() > 0) offered.append(',');
                            offered.append(format);
                            if (format == selectedPixelFormat) {
                                return format;
                            }
                        }
                        System.err.printf("[scrcpy-java][%s] hardware %s get_format: requested=%d not offered; offered=[%s]%n",
                                serial,
                                backend.name(), selectedPixelFormat, offered);
                        return AV_PIX_FMT_NONE;
                    }
                };

        candidate.get_format(callback);
        AVBufferRef codecDeviceRef = av_buffer_ref(device);
        candidate.hw_device_ctx(codecDeviceRef);
        // Android MediaCodec may advertise AVC Baseline (66) rather than
        // Constrained Baseline. FFmpeg's DXVA mode table accepts Constrained
        // Baseline/Main/High by default, so allow the GPU/driver to decide
        // whether this otherwise valid stream can be decoded.
        candidate.hwaccel_flags(candidate.hwaccel_flags()
                | AV_HWACCEL_FLAG_IGNORE_LEVEL
                | AV_HWACCEL_FLAG_ALLOW_PROFILE_MISMATCH);

        if (avcodec_open2(candidate, decoder, (AVDictionary) null) < 0) {
            avcodec_free_context(candidate);
            av_buffer_unref(device);
            callback.close();
            return false;
        }

        ctx = candidate;
        hwDevice = device;
        hwPixelFormat = selectedPixelFormat;
        getFormatCallback = callback;
        activeHardware = backend.name();
        allocateFramesAndPacket();
        System.out.printf("[scrcpy-java][%s] video decoder: trying hardware %s (%s)%n", serial, activeHardware, codecId);
        return true;
    }

    private void configureCodecContext(AVCodecContext codecContext, boolean hardware) {
        // The session dimensions are known before avcodec_open2(). Keep the
        // software pixel format unset for hardware decoders: FFmpeg must choose
        // the hardware surface format (for example AV_PIX_FMT_D3D11) through
        // get_format(). Pre-setting YUV420P can interfere with that negotiation.
        if (sessionWidth > 0) codecContext.width(sessionWidth);
        if (sessionHeight > 0) codecContext.height(sessionHeight);
        if (!hardware) {
            codecContext.pix_fmt(AV_PIX_FMT_YUV420P);
            codecContext.thread_count(SOFTWARE_DECODER_THREADS);
            // Frame threading adds decoder latency. Slice threading keeps work
            // within one frame and is ignored automatically when unsupported.
            codecContext.thread_type(FFMPEG_THREAD_SLICE);
        }
        codecContext.flags(codecContext.flags() | AV_CODEC_FLAG_LOW_DELAY);
    }

    private void allocateFramesAndPacket() {
        frame = av_frame_alloc();
        softwareFrame = av_frame_alloc();
        rgb = av_frame_alloc();
        packet = av_packet_alloc();
        if (frame == null || softwareFrame == null || rgb == null || packet == null
                || frame.isNull() || softwareFrame.isNull() || rgb.isNull() || packet.isNull()) {
            closeNative();
            throw new IllegalStateException("cannot allocate FFmpeg video frames");
        }
    }

    private static List<HardwareBackend> hardwareBackends() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of(new HardwareBackend("d3d11va", AV_HWDEVICE_TYPE_D3D11VA));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of(new HardwareBackend("videotoolbox", AV_HWDEVICE_TYPE_VIDEOTOOLBOX));
        }
        if (os.contains("linux")) {
            // VAAPI covers Intel/AMD on the normal Linux graphics stack.
            // CUDA/NVDEC is attempted next for NVIDIA systems.
            return List.of(
                    new HardwareBackend("vaapi", AV_HWDEVICE_TYPE_VAAPI),
                    new HardwareBackend("cuda", AV_HWDEVICE_TYPE_CUDA));
        }
        return List.of();
    }

    private record HardwareBackend(String name, int type) {}

    private void converter(int w, int h, int fmt) {
        if (w == width && h == height && fmt == pixelFormat && sws != null) return;

        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }

        width = w;
        height = h;
        pixelFormat = fmt;
        sws = sws_getContext(
                w, h, fmt,
                w, h, AV_PIX_FMT_BGRA,
                SWS_BILINEAR,
                null, null, (DoublePointer) null);
        if (sws == null) {
            throw new IllegalStateException("cannot create swscale context: " + w + "x" + h + " fmt=" + fmt);
        }

        // rgb is only a lightweight destination descriptor now. Its data
        // pointer is assigned to a pooled direct ByteBuffer for each frame.
        av_frame_unref(rgb);
        rgb.width(w);
        rgb.height(h);
        rgb.format(AV_PIX_FMT_BGRA);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ch, MediaMessage m) {
        if (closed.get()) {
            releaseMessage(m);
            return;
        }
        if (!decodeQueue.offer(m)) {
            MediaMessage dropped = decodeQueue.poll();
            if (dropped != null) {
                releaseMessage(dropped);
                if (VIDEO_STATS_ENABLED) statsQueueDrops.incrementAndGet();
            }
            if (!decodeQueue.offer(m)) {
                releaseMessage(m);
                if (VIDEO_STATS_ENABLED) statsQueueDrops.incrementAndGet();
            }
        }
    }

    private void decodeLoop() {
        try {
            while (!closed.get() || !decodeQueue.isEmpty()) {
                MediaMessage m = decodeQueue.take();
                try {
                    decodeMessage(m);
                    reportVideoStatsIfDue();
                } finally {
                    releaseMessage(m);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("[scrcpy-java][" + serial + "] video decode worker failed: " + t);
            t.printStackTrace(System.err);
        } finally {
            MediaMessage m;
            while ((m = decodeQueue.poll()) != null) releaseMessage(m);
            // Destroy this context under the same process-wide lock as
            // send/receive/init; another device may still be in libavcodec.
            withVideoLock(() -> {
                closeNative();
                return null;
            });
            retireBgraPool();
        }
    }

    private void decodeMessage(MediaMessage m) {
        // Only the native decoder calls are serialized globally. Expensive
        // swscale, Java heap copies and the JavaFX sink are intentionally kept
        // outside this lock so multiple devices can convert/render in parallel.
        decodeMessage0(m);
    }

    private void decodeMessage0(MediaMessage m) {
        if (m instanceof MediaMessage.Codec c) {
            announcedCodec = c.codec();
            return;
        }
        if (m instanceof MediaMessage.Session session) {
            sessionWidth = session.width();
            sessionHeight = session.height();
            if (!DEBUG_SKIP_DECODE && announcedCodec != null && ctx == null) {
                withVideoLock(() -> {
                    init(announcedCodec);
                    return null;
                });
                System.out.printf("[scrcpy-java][%s] video decoder initialized for session %dx%d%s%n",
                        serial,
                        session.width(), session.height(),
                        session.clientResized() ? " (client resized)" : "");
            } else if (ctx != null) {
                System.out.printf("[scrcpy-java][%s] video session updated: %dx%d%s%n",
                        serial,
                        session.width(), session.height(),
                        session.clientResized() ? " (client resized)" : "");
            }
            return;
        }
        if (!(m instanceof MediaMessage.Packet p) || p.config()) return;
        if (DEBUG_SKIP_DECODE) return;
        if (ctx == null) {
            if (announcedCodec == null) return;
            withVideoLock(() -> {
                init(announcedCodec);
                return null;
            });
        }

        int payloadSize = p.data().readableBytes();
        byte[] encoded = ensureEncodedCapacity(payloadSize);
        p.data().getBytes(p.data().readerIndex(), encoded, 0, payloadSize);

        int sendResult;
        sendResult = withDecoderLock(() -> {
            av_packet_unref(packet);
            if (av_new_packet(packet, payloadSize) < 0) return -1;
            packet.data().put(encoded, 0, payloadSize);
            packet.pts(p.pts()).dts(p.pts());
            return avcodec_send_packet(ctx, packet);
        });

        if (sendResult < 0) {
            if (activeHardware != null && !hardwareConfirmed) {
                logHardwarePacketFailure("avcodec_send_packet", sendResult, encoded, payloadSize, p.pts());
                withVideoLock(() -> {
                    fallbackToSoftware(encoded, payloadSize, p.pts(), "avcodec_send_packet=" + sendResult);
                    return null;
                });
            }
            return;
        }

        int receiveResult;
        for (;;) {
            receiveResult = withDecoderLock(() -> avcodec_receive_frame(ctx, frame));

            if (receiveResult < 0) break;
            if (VIDEO_STATS_ENABLED) statsDecodedFrames.incrementAndGet();

            if (activeHardware != null && !hardwareConfirmed) {
                hardwareConfirmed = true;
                System.out.printf("[scrcpy-java][%s] video decoder: hardware %s confirmed (%s)%n", serial, activeHardware, activeCodec);
            }
            if (DEBUG_SKIP_RENDER) continue;

            AVFrame source = frame;
            if (activeHardware != null && frame.format() == hwPixelFormat) {
                HardwareVideoFrame hardwareFrame = HardwareVideoFrame.from(
                        frame, serial, activeHardware, p.pts());
                if (hardwareFrame != null) {
                    boolean accepted = false;
                    try {
                        accepted = renderer.renderHardware(hardwareFrame);
                    } finally {
                        if (!accepted) hardwareFrame.close();
                    }
                    if (accepted) {
                        if (VIDEO_STATS_ENABLED) statsHardwareFrames.incrementAndGet();
                        continue;
                    }
                }

                // A miss here means the renderer did not present the D3D11
                // texture directly. Count the explicit GPU->system-memory
                // transfer separately so zero-readback can be verified.
                statsNativePresentationMisses.incrementAndGet();
                statsHardwareTransferAttempts.incrementAndGet();
                int transferResult = withDecoderLock(() -> {
                    av_frame_unref(softwareFrame);
                    return av_hwframe_transfer_data(softwareFrame, frame, 0);
                });
                if (transferResult < 0) {
                    statsHardwareTransferFailures.incrementAndGet();
                    continue;
                }
                statsHardwareTransferSuccesses.incrementAndGet();
                source = softwareFrame;
            }
            if (DEBUG_SKIP_SWS_SCALE) continue;

            converter(source.width(), source.height(), source.format());
            BgraBuffer output = acquireBgraBuffer(width * height * 4);
            if (output == null) {
                if (VIDEO_STATS_ENABLED) statsPoolMisses.incrementAndGet();
                continue;
            }
            configureRgbDestination(output);
            long scaleStarted = VIDEO_STATS_ENABLED ? System.nanoTime() : 0L;
            int scaled = sws_scale(
                    sws,
                    source.data(),
                    source.linesize(),
                    0,
                    source.height(),
                    rgb.data(),
                    rgb.linesize());
            if (VIDEO_STATS_ENABLED) {
                statsScaleNanos.addAndGet(System.nanoTime() - scaleStarted);
                statsScaleCount.incrementAndGet();
            }
            if (scaled <= 0) {
                recycleBgraBuffer(output);
                continue;
            }

            publishFrame(output, p.pts());
        }

        if (receiveResult != AVERROR_EAGAIN() && receiveResult != AVERROR_EOF()
                && activeHardware != null && !hardwareConfirmed) {
            int fallbackError = receiveResult;
            withVideoLock(() -> {
                fallbackToSoftware(encoded, payloadSize, p.pts(), "avcodec_receive_frame=" + fallbackError);
                return null;
            });
        }
    }

    private <T> T withVideoLock(Supplier<T> operation) {
        return withMeasuredLock(FFMPEG_VIDEO_LOCK, operation,
                statsLockWaitNanos, statsLockHoldNanos, statsLockContentions);
    }

    private <T> T withDecoderLock(Supplier<T> operation) {
        if (!PARALLEL_NATIVE_DECODE) {
            return withVideoLock(operation);
        }
        return withMeasuredLock(decoderNativeLock, operation,
                statsDeviceLockWaitNanos, statsDeviceLockHoldNanos, statsDeviceLockContentions);
    }

    private <T> T withMeasuredLock(ReentrantLock lock, Supplier<T> operation,
                                   AtomicLong waitNanos, AtomicLong holdNanos,
                                   AtomicLong contentions) {
        long waitStarted = VIDEO_STATS_ENABLED ? System.nanoTime() : 0L;
        // tryLock() measures actual acquisition contention instead of racing
        // with isLocked() and potentially over-counting waiters.
        boolean acquiredImmediately = lock.tryLock();
        if (!acquiredImmediately) {
            if (VIDEO_STATS_ENABLED) contentions.incrementAndGet();
            lock.lock();
        }
        long acquired = VIDEO_STATS_ENABLED ? System.nanoTime() : 0L;
        try {
            if (VIDEO_STATS_ENABLED) waitNanos.addAndGet(acquired - waitStarted);
            return operation.get();
        } finally {
            if (VIDEO_STATS_ENABLED) holdNanos.addAndGet(System.nanoTime() - acquired);
            lock.unlock();
        }
    }

    private void reportVideoStatsIfDue() {
        if (!VIDEO_STATS_ENABLED) return;
        long now = System.nanoTime();
        long elapsed = now - statsLastReportNanos;
        if (elapsed < 5_000_000_000L) return;
        statsLastReportNanos = now;

        long decoded = statsDecodedFrames.getAndSet(0);
        long hardware = statsHardwareFrames.getAndSet(0);
        long nativeMisses = statsNativePresentationMisses.getAndSet(0);
        long transferAttempts = statsHardwareTransferAttempts.getAndSet(0);
        long transferSuccesses = statsHardwareTransferSuccesses.getAndSet(0);
        long transferFailures = statsHardwareTransferFailures.getAndSet(0);
        long software = statsSoftwareFrames.getAndSet(0);
        long queueDrops = statsQueueDrops.getAndSet(0);
        long poolMisses = statsPoolMisses.getAndSet(0);
        long scaleNanos = statsScaleNanos.getAndSet(0);
        long scaleCount = statsScaleCount.getAndSet(0);
        long lockWaitNanos = statsLockWaitNanos.getAndSet(0);
        long lockHoldNanos = statsLockHoldNanos.getAndSet(0);
        long lockContentions = statsLockContentions.getAndSet(0);
        long deviceLockWaitNanos = statsDeviceLockWaitNanos.getAndSet(0);
        long deviceLockHoldNanos = statsDeviceLockHoldNanos.getAndSet(0);
        long deviceLockContentions = statsDeviceLockContentions.getAndSet(0);
        double seconds = elapsed / 1_000_000_000.0;
        double decodedFps = decoded / seconds;
        double scaleAverageMs = scaleCount == 0 ? 0.0 : scaleNanos / 1_000_000.0 / scaleCount;
        System.out.printf(
                Locale.ROOT,
                "[scrcpy-java][%s] video stats: decoded=%.1f fps, rendererAccepted=%.1f fps, nativePresentMisses=%d, hwToCpuTransfers=%d/%d (failed=%d), softwarePresent=%.1f fps, queueDrops=%d, bgraPoolMisses=%d, swscaleAvg=%.2f ms, nativeLockMode=%s, globalLockWait=%.2f ms, globalLockHold=%.2f ms, globalLockContended=%d, deviceLockWait=%.2f ms, deviceLockHold=%.2f ms, deviceLockContended=%d, queue=%d/16%n",
                serial, decodedFps, hardware / seconds, nativeMisses,
                transferSuccesses, transferAttempts, transferFailures, software / seconds,
                queueDrops, poolMisses, scaleAverageMs,
                PARALLEL_NATIVE_DECODE ? "parallel-device" : "global",
                lockWaitNanos / 1_000_000.0, lockHoldNanos / 1_000_000.0,
                lockContentions,
                deviceLockWaitNanos / 1_000_000.0, deviceLockHoldNanos / 1_000_000.0,
                deviceLockContentions, decodeQueue.size());
    }

    private byte[] ensureEncodedCapacity(int size) {
        if (encodedBuffer.length < size) {
            int capacity = Math.max(size, Math.max(64 * 1024, encodedBuffer.length << 1));
            encodedBuffer = new byte[capacity];
        }
        return encodedBuffer;
    }

    private void logHardwarePacketFailure(String operation, int error, byte[] encoded, int encodedLength, long pts) {
        byte[] errorBuffer = new byte[256];
        BytePointer errorPointer = new BytePointer(errorBuffer);
        String errorText;
        try {
            int result = av_strerror(error, errorPointer, errorBuffer.length);
            errorText = result >= 0 ? errorPointer.getString() : "unknown";
        } finally {
            errorPointer.close();
        }

        int previewLength = Math.min(encodedLength, 48);
        StringBuilder hex = new StringBuilder(previewLength * 3);
        for (int i = 0; i < previewLength; i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format("%02x", encoded[i] & 0xff));
        }

        System.err.printf(
                "[scrcpy-java][%s] hardware decode diagnostic: backend=%s codec=%s op=%s error=%d (%s) packetBytes=%d pts=%d session=%dx%d ctx=%dx%d pixFmt=%d hwPixelFmt=%d hwDevice=%s firstBytes=%s%n",
                serial, activeHardware, activeCodec, operation, error, errorText, encodedLength, pts,
                sessionWidth, sessionHeight,
                ctx != null ? ctx.width() : -1, ctx != null ? ctx.height() : -1,
                ctx != null ? ctx.pix_fmt() : AV_PIX_FMT_NONE, hwPixelFormat,
                hwDevice != null && !hwDevice.isNull(), hex);
    }

    private void fallbackToSoftware(byte[] encoded, int encodedLength, long pts, String reason) {
        CodecId codecId = activeCodec;
        String backend = activeHardware;
        System.err.printf("[scrcpy-java][%s] hardware decoder %s failed before first frame (%s); falling back to software: %s%n",
                serial,
                backend, codecId, reason);
        closeNative();
        activeCodec = codecId;
        AVCodec decoder = avcodec_find_decoder(avId(codecId));
        if (decoder == null || decoder.isNull()) {
            throw new IllegalStateException("software decoder unavailable " + codecId);
        }
        initSoftware(decoder, codecId);

        av_packet_unref(packet);
        if (av_new_packet(packet, encodedLength) < 0) return;
        packet.data().put(encoded, 0, encodedLength);
        packet.pts(pts).dts(pts);
        int softwareSendResult = avcodec_send_packet(ctx, packet);
        if (softwareSendResult < 0) {
            System.err.printf("[scrcpy-java][%s] software decoder also rejected fallback packet (%s): avcodec_send_packet=%d, bytes=%d%n",
                    serial,
                    codecId, softwareSendResult, encodedLength);
            return;
        }
        while (avcodec_receive_frame(ctx, frame) >= 0) {
            if (VIDEO_STATS_ENABLED) statsDecodedFrames.incrementAndGet();
            if (DEBUG_SKIP_RENDER) continue;
            converter(frame.width(), frame.height(), frame.format());
            if (DEBUG_SKIP_SWS_SCALE) continue;
            BgraBuffer output = acquireBgraBuffer(width * height * 4);
            if (output == null) {
                if (VIDEO_STATS_ENABLED) statsPoolMisses.incrementAndGet();
                continue;
            }
            configureRgbDestination(output);
            long scaleStarted = VIDEO_STATS_ENABLED ? System.nanoTime() : 0L;
            int scaled = sws_scale(sws, frame.data(), frame.linesize(), 0, frame.height(), rgb.data(), rgb.linesize());
            if (VIDEO_STATS_ENABLED) {
                statsScaleNanos.addAndGet(System.nanoTime() - scaleStarted);
                statsScaleCount.incrementAndGet();
            }
            if (scaled <= 0) {
                recycleBgraBuffer(output);
                continue;
            }
            publishFrame(output, pts);
        }
    }

    private void configureRgbDestination(BgraBuffer output) {
        output.buffer.clear();
        output.pointer.position(0);
        rgb.data(0, output.pointer);
        rgb.linesize(0, width * 4);
    }

    private void publishFrame(BgraBuffer output, long pts) {
        if (VIDEO_STATS_ENABLED) statsSoftwareFrames.incrementAndGet();
        output.buffer.position(0);
        output.buffer.limit(width * height * 4);
        VideoFrame videoFrame = new VideoFrame(
                width, height, output.buffer, pts, () -> recycleBgraBuffer(output));
        try {
            renderer.renderSoftware(videoFrame);
        } catch (Throwable t) {
            videoFrame.close();
            throw t;
        }
    }

    private synchronized BgraBuffer acquireBgraBuffer(int size) {
        if (bgraBufferSize != size) {
            resetBgraPool(size);
        }
        BgraBuffer buffer = bgraPool.pollFirst();
        if (buffer != null) {
            buffer.leased = true;
        }
        return buffer;
    }

    /**
     * Retire the previous size generation without freeing buffers that are
     * still referenced by JavaFX PixelBuffer/WritableImage instances.
     * A leased buffer is freed only when its VideoFrame is released.
     */
    private void resetBgraPool(int size) {
        for (BgraBuffer buffer : bgraBuffers) {
            buffer.retired = true;
            if (!buffer.leased) {
                buffer.close();
            }
        }
        bgraPool.clear();
        bgraBuffers.clear();
        bgraBufferSize = size;
        for (int i = 0; i < BGRA_POOL_SIZE; i++) {
            BgraBuffer buffer = new BgraBuffer(size);
            bgraBuffers.add(buffer);
            bgraPool.addLast(buffer);
        }
    }

    private synchronized void recycleBgraBuffer(BgraBuffer buffer) {
        if (!buffer.leased) {
            return;
        }
        buffer.leased = false;
        if (buffer.retired || closed.get()) {
            buffer.close();
            return;
        }
        if (bgraBuffers.contains(buffer) && !bgraPool.contains(buffer)) {
            bgraPool.addLast(buffer);
        }
    }

    /** Retire the active generation when the decoder worker exits. */
    private synchronized void retireBgraPool() {
        for (BgraBuffer buffer : bgraBuffers) {
            buffer.retired = true;
            if (!buffer.leased) {
                buffer.close();
            }
        }
        bgraPool.clear();
        bgraBuffers.clear();
        bgraBufferSize = 0;
    }

    private static void releaseMessage(MediaMessage m) {
        if (m instanceof MediaMessage.Packet p) ReferenceCountUtil.release(p.data());
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        decodeThread.interrupt();
        try {
            decodeThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void closeNative() {
        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (rgb != null) {
            av_frame_free(rgb);
            rgb = null;
        }
        if (softwareFrame != null) {
            av_frame_free(softwareFrame);
            softwareFrame = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
        if (hwDevice != null && !hwDevice.isNull()) {
            av_buffer_unref(hwDevice);
            hwDevice = null;
        }
        if (getFormatCallback != null) {
            getFormatCallback.close();
            getFormatCallback = null;
        }
        // The direct BGRA pool is independent from an FFmpeg decoder retry,
        // but a session size change will recreate it lazily on the next frame.
        hwPixelFormat = AV_PIX_FMT_NONE;
        activeHardware = null;
        hardwareConfirmed = false;
        width = height = 0;
        pixelFormat = -1;
    }
}
