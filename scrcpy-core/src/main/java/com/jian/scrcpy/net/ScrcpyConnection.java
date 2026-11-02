package com.jian.scrcpy.net;

import com.jian.scrcpy.protocol.*;
import com.jian.scrcpy.metrics.SessionMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ScrcpyConnection implements AutoCloseable {
    /** Assigned by the parent accept event loop, before child registration can run concurrently. */
    private static final AttributeKey<Integer> ACCEPT_INDEX =
            AttributeKey.valueOf(ScrcpyConnection.class, "acceptIndex");

    /**
     * All device sockets are non-blocking and can share a bounded process-wide
     * event-loop pool. Creating two one-thread groups per device scales to
     * 100 platform threads for 50 devices and wastes thread stacks/schedulers.
     */
    private static final class SharedEventLoops {
        private static EventLoopGroup ioGroup;
        private static EventLoopGroup acceptGroup;
        private static int references;
        private static NettyTransport.Selection transport;

        static synchronized Groups acquire() {
            if (ioGroup == null || ioGroup.isShuttingDown() || ioGroup.isShutdown()) {
                int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
                transport = NettyTransport.select();
                ioGroup = new MultiThreadIoEventLoopGroup(threads, transport.ioHandlerFactory());
                acceptGroup = new MultiThreadIoEventLoopGroup(1, transport.ioHandlerFactory());
            }
            references++;
            return new Groups(acceptGroup, ioGroup);
        }

        static void release() {
            EventLoopGroup ioToClose = null;
            EventLoopGroup acceptToClose = null;
            synchronized (SharedEventLoops.class) {
                if (references <= 0) return;
                references--;
                if (references == 0) {
                    ioToClose = ioGroup;
                    acceptToClose = acceptGroup;
                    ioGroup = null;
                    acceptGroup = null;
                }
            }
            // Initiate shutdown without blocking the Swing/connection close
            // caller. Netty completes the graceful shutdown asynchronously.
            if (acceptToClose != null) {
                acceptToClose.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            }
            if (ioToClose != null) {
                ioToClose.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            }
        }

        static synchronized Class<? extends io.netty.channel.ServerChannel> serverChannelClass() {
            if (transport == null) {
                throw new IllegalStateException("Netty transport has not been initialized");
            }
            return transport.serverChannelClass();
        }

        private record Groups(EventLoopGroup accept, EventLoopGroup io) {
        }
    }

    private final SharedEventLoops.Groups eventLoops = SharedEventLoops.acquire();
    private final EventLoopGroup group = eventLoops.io();
    private final EventLoopGroup acceptGroup = eventLoops.accept();
    private final int port;
    private Channel video, audio, control, listener;
    private volatile ControlMessageQueue controlQueue;
    private volatile int localPort;
    private volatile String deviceName;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final String sessionLabel;
    private final SessionMetrics metrics;
    private volatile Consumer<Throwable> transportFailureSink = cause -> {};

    public ScrcpyConnection(int p) { this(p, null, null); }

    public ScrcpyConnection(int p, String sessionLabel) { this(p, sessionLabel, null); }

    public ScrcpyConnection(int p, String sessionLabel, SessionMetrics metrics) {
        port = p;
        this.sessionLabel = sessionLabel == null || sessionLabel.isBlank() ? "unknown" : sessionLabel;
        this.metrics = metrics;
    }

    public void onTransportFailure(Consumer<Throwable> sink) {
        transportFailureSink = sink == null ? cause -> {} : sink;
    }

    private void reportTransportFailure(String stream, Throwable cause) {
        if (closed.get()) return;
        System.err.printf("[scrcpy-java][%s] %s transport failed: %s%n", sessionLabel, stream, cause);
        if (failureReported.compareAndSet(false, true)) {
            if (metrics != null) metrics.recordTransportFailure();
            try { transportFailureSink.accept(cause); }
            catch (Throwable callbackError) {
                System.err.printf("[scrcpy-java][%s] transport failure callback failed: %s%n",
                        sessionLabel, callbackError);
            }
        }
    }

    private void configure(ChannelPipeline pipeline, boolean firstSocket, String name,
                           ChannelInboundHandler videoHandler,
                           ChannelInboundHandler audioHandler,
                           ChannelInboundHandler controlHandler) {
        if (firstSocket) firstSocket(pipeline);

        if ("video".equals(name)) {
            pipeline.addLast("media", new ScrcpyMediaDecoder(sessionLabel));
            pipeline.addLast("configMerger", new ConfigPacketMerger());
            if (videoHandler != null) pipeline.addLast("videoHandler", videoHandler);
        } else if ("audio".equals(name)) {
            if (metrics != null) {
                pipeline.addLast("audioTcpMetrics", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                        if (message instanceof ByteBuf buffer) metrics.recordAudioTcp(buffer.readableBytes());
                        ctx.fireChannelRead(message);
                    }
                });
            }
            pipeline.addLast("audioMedia", new ScrcpyAudioDecoder(sessionLabel, metrics));
            if (audioHandler != null) pipeline.addLast("audioHandler", audioHandler);
        } else {
            pipeline.addLast("deviceMessage", new DeviceMessageDecoder());
            pipeline.addLast("controlEncoder", new ControlMessageEncoder());
            controlQueue = new ControlMessageQueue();
            pipeline.addLast("controlQueue", controlQueue);
            if (controlHandler != null) pipeline.addLast("controlHandler", controlHandler);
        }
        exceptionLogging(pipeline, name);
    }

    private void firstSocket(ChannelPipeline pipeline) {
        pipeline.addLast("deviceInfo", new DeviceInfoDecoder());
        pipeline.addLast("deviceInfoSink",
                new SimpleChannelInboundHandler<DeviceInfoDecoder.DeviceInfo>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx,
                                                DeviceInfoDecoder.DeviceInfo info) {
                        deviceName = info.name();
                        System.out.println("[scrcpy-java][" + sessionLabel + "] device name: " + deviceName);
                        ctx.pipeline().remove(this);
                    }
                });
    }

    private void exceptionLogging(ChannelPipeline pipeline, String name) {
        pipeline.addLast("exceptionLogger", new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                reportTransportFailure(name, cause);
                cause.printStackTrace(System.err);
                ctx.close();
            }

            @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                if (!closed.get()) {
                    reportTransportFailure(name, new ClosedChannelException());
                }
                ctx.fireChannelInactive();
            }
        });
    }

    public void listen(ChannelInboundHandler videoHandler,
                       ChannelInboundHandler audioHandler,
                       ChannelInboundHandler controlHandler,
                       boolean videoEnabled, boolean audioEnabled, boolean controlEnabled) throws Exception {
        List<String> names = new ArrayList<>();
        if (videoEnabled) names.add("video");
        if (audioEnabled) names.add("audio");
        if (controlEnabled) names.add("control");
        if (names.isEmpty()) throw new IllegalArgumentException("At least one scrcpy socket must be enabled");

        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        listener = new ServerBootstrap()
                .group(acceptGroup, group)
                .channel(SharedEventLoops.serverChannelClass())
                // The parent acceptor runs on one event loop and observes TCP accepts
                // in order. Assign the protocol socket index here, not in the child
                // ChannelInitializer: child registration runs on the shared multi-
                // thread IO group and its initialization tasks may execute out of order.
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof Channel child) {
                            child.attr(ACCEPT_INDEX).set(accepted.getAndIncrement());
                        }
                        ctx.fireChannelRead(msg);
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override protected void initChannel(Channel channel) {
                        Integer acceptIndex = channel.attr(ACCEPT_INDEX).get();
                        if (acceptIndex == null || acceptIndex >= names.size()) {
                            channel.close();
                            return;
                        }
                        int i = acceptIndex;
                        String name = names.get(i);
                        configure(channel.pipeline(), i == 0, name,
                                videoHandler, audioHandler, controlHandler);
                        switch (name) {
                            case "video" -> video = channel;
                            case "audio" -> audio = channel;
                            case "control" -> control = channel;
                            default -> throw new IllegalStateException("unknown socket type: " + name);
                        }
                        System.out.printf("[scrcpy-java][%s] %s socket accepted (acceptIndex=%d): %s%n",
                                sessionLabel, name, i, channel.remoteAddress());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port))
                .sync().channel();

        localPort = ((InetSocketAddress) listener.localAddress()).getPort();
        System.out.printf("[scrcpy-java][%s] listening on 127.0.0.1:%d for scrcpy reverse tunnel%n",
                sessionLabel, localPort);
    }

    public void open(ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        throw new UnsupportedOperationException("Use listen() for reverse scrcpy tunnel");
    }

    public void open(boolean videoEnabled, boolean audioEnabled, boolean controlEnabled,
                     ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        throw new UnsupportedOperationException("Use listen() for reverse scrcpy tunnel");
    }

    public int localPort() {
        if (localPort <= 0) throw new IllegalStateException("scrcpy listener is not started");
        return localPort;
    }

    public String deviceName() { return deviceName; }
    public Channel control() { return control; }
    public ControlMessageQueue controlQueue() { return controlQueue; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Channel[] channels = {video, audio, control, listener};
        for (Channel channel : channels) {
            if (channel == null) continue;
            try { channel.close().syncUninterruptibly(); }
            catch (Throwable t) { System.err.println("[scrcpy-java] channel close failed: " + t); }
        }
        // Event loops are shared by all active device connections. Release
        // this connection's lease; the shared groups shut down after the last
        // device closes, keeping reconnect and partial-startup paths safe.
        SharedEventLoops.release();
    }
}