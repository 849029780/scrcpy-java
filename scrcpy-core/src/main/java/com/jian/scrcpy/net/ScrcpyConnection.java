package com.jian.scrcpy.net;

import com.jian.scrcpy.protocol.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ScrcpyConnection implements AutoCloseable {
    // Keep all sockets of one scrcpy session on one I/O event loop.
    private final EventLoopGroup group =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final EventLoopGroup acceptGroup =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final int port;
    private Channel video, audio, control, listener;
    private volatile ControlMessageQueue controlQueue;
    private volatile int localPort;
    private volatile String deviceName;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final String sessionLabel;
    private volatile Consumer<Throwable> transportFailureSink = cause -> {};

    public ScrcpyConnection(int p) {
        this(p, null);
    }

    public ScrcpyConnection(int p, String sessionLabel) {
        port = p;
        this.sessionLabel = sessionLabel == null || sessionLabel.isBlank() ? "unknown" : sessionLabel;
    }

    public void onTransportFailure(Consumer<Throwable> sink) {
        transportFailureSink = sink == null ? cause -> {} : sink;
    }

    private void reportTransportFailure(String stream, Throwable cause) {
        if (closed.get()) return;
        System.err.printf("[scrcpy-java][%s] %s transport failed: %s%n", sessionLabel, stream, cause);
        if (failureReported.compareAndSet(false, true)) {
            try {
                transportFailureSink.accept(cause);
            } catch (Throwable callbackError) {
                System.err.printf("[scrcpy-java][%s] transport failure callback failed: %s%n",
                        sessionLabel, callbackError);
            }
        }
    }

    private void configure(ChannelPipeline pipeline, String name,
                           ChannelInboundHandler videoHandler,
                           ChannelInboundHandler audioHandler,
                           ChannelInboundHandler controlHandler) {
        if ("video".equals(name)) {
            pipeline.addLast("deviceInfo", new DeviceInfoDecoder());
            pipeline.addLast(
                    "deviceInfoSink",
                    new SimpleChannelInboundHandler<DeviceInfoDecoder.DeviceInfo>() {
                        @Override
                        protected void channelRead0(
                                ChannelHandlerContext ctx,
                                DeviceInfoDecoder.DeviceInfo info) {
                            deviceName = info.name();
                            System.out.println("[scrcpy-java][" + sessionLabel
                                    + "] device name: " + deviceName);
                            ctx.pipeline().remove(this);
                        }
                    });
            pipeline.addLast("media", new ScrcpyMediaDecoder());
            pipeline.addLast("configMerger", new ConfigPacketMerger());
            if (videoHandler != null) {
                pipeline.addLast("videoHandler", videoHandler);
            }
        } else if ("audio".equals(name)) {
            pipeline.addLast("audioMedia", new ScrcpyAudioDecoder());
            if (audioHandler != null) {
                pipeline.addLast("audioHandler", audioHandler);
            }
        } else if ("control".equals(name)) {
            pipeline.addLast("deviceMessage", new DeviceMessageDecoder());
            pipeline.addLast("controlEncoder", new ControlMessageEncoder());
            controlQueue = new ControlMessageQueue();
            pipeline.addLast("controlQueue", controlQueue);
            if (controlHandler != null) {
                pipeline.addLast("controlHandler", controlHandler);
            }
        } else {
            throw new IllegalStateException("unknown socket type: " + name);
        }

        exceptionLogging(pipeline, name);
        System.out.printf("[scrcpy-java][%s] stream classified as %s%n", sessionLabel, name);
    }

    private void exceptionLogging(ChannelPipeline pipeline, String name) {
        pipeline.addLast("exceptionLogger", new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                reportTransportFailure(name, cause);
                cause.printStackTrace(System.err);
                ctx.close();
            }
        });
    }

    public void listen(ChannelInboundHandler videoHandler,
                       ChannelInboundHandler audioHandler,
                       ChannelInboundHandler controlHandler,
                       boolean videoEnabled, boolean audioEnabled, boolean controlEnabled) throws Exception {
        List<String> enabled = new ArrayList<>();
        if (videoEnabled) enabled.add("video");
        if (audioEnabled) enabled.add("audio");
        if (controlEnabled) enabled.add("control");
        if (enabled.isEmpty()) {
            throw new IllegalArgumentException("At least one scrcpy socket must be enabled");
        }

        listener = new ServerBootstrap()
                .group(acceptGroup, group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(
                                "streamClassifier",
                                new ScrcpyStreamClassifier((ctx, type) -> {
                                    String name = switch (type) {
                                        case VIDEO -> "video";
                                        case AUDIO -> "audio";
                                        case CONTROL -> "control";
                                    };

                                    if (!enabled.contains(name)) return false;

                                    configure(ctx.pipeline(), name,
                                            videoHandler, audioHandler, controlHandler);

                                    switch (name) {
                                        case "video" -> video = channel;
                                        case "audio" -> audio = channel;
                                        case "control" -> control = channel;
                                        default -> throw new IllegalStateException("unknown socket type: " + name);
                                    }
                                    return true;
                                }));
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port))
                .sync()
                .channel();

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

    public String deviceName() {
        return deviceName;
    }

    public Channel control() {
        return control;
    }

    public ControlMessageQueue controlQueue() {
        return controlQueue;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        Channel[] channels = {video, audio, control, listener};
        for (Channel channel : channels) {
            if (channel == null) continue;
            try {
                channel.close().syncUninterruptibly();
            } catch (Throwable t) {
                System.err.println("[scrcpy-java] channel close failed: " + t);
            }
        }

        acceptGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}