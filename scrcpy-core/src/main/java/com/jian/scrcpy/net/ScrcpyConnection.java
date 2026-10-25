package com.jian.scrcpy.net;

import com.jian.scrcpy.protocol.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScrcpyConnection implements AutoCloseable {
    private final EventLoopGroup group =
            new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    private final int port;
    private Channel video;
    private Channel audio;
    private Channel control;
    private Channel listener;

    private volatile ControlMessageQueue controlQueue;
    private volatile int localPort;
    private volatile String deviceName;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile boolean videoEnabled;
    private volatile boolean audioEnabled;
    private volatile boolean controlEnabled;
    private final Set<Channel> pendingChannels = ConcurrentHashMap.newKeySet();

    private ChannelInboundHandler videoHandler;
    private ChannelInboundHandler audioHandler;
    private ChannelInboundHandler controlHandler;

    public ScrcpyConnection(int p) {
        port = p;
    }

    private void firstSocket(ChannelPipeline pipeline) {
        pipeline.addLast("deviceInfo", new DeviceInfoDecoder());
        pipeline.addLast(
                "deviceInfoSink",
                new SimpleChannelInboundHandler<DeviceInfoDecoder.DeviceInfo>() {
                    @Override
                    protected void channelRead0(
                            ChannelHandlerContext ctx,
                            DeviceInfoDecoder.DeviceInfo info) {
                        deviceName = info.name();
                        System.out.println("[scrcpy-java] device name: " + deviceName);
                        ctx.pipeline().remove(this);
                    }
                });
    }

    private void addClassifier(Channel channel) {
        pendingChannels.add(channel);
        channel.pipeline().addLast(
                "streamClassifier",
                new ScrcpyStreamClassifier(this::classifyStream));
    }

    /**
     * Classify a socket from its first protocol bytes.
     *
     * @return true when the classifier may hand the current bytes to the
     *         newly-installed protocol decoder.
     */
    private synchronized boolean classifyStream(ChannelHandlerContext ctx, int codecId) {
        Channel channel = ctx.channel();
        if (closed.get() || !pendingChannels.contains(channel)) {
            return false;
        }

        if (ScrcpyStreamClassifier.isVideoCodec(codecId)) {
            if (!videoEnabled || video != null) {
                pendingChannels.remove(channel);
                channel.close();
                return true;
            }
            bindVideo(ctx);
            return true;
        }

        if (ScrcpyStreamClassifier.isAudioCodec(codecId)) {
            if (!audioEnabled || audio != null) {
                pendingChannels.remove(channel);
                channel.close();
                return true;
            }
            bindAudio(ctx);
            return true;
        }

        // The control socket does not expose a codec id. It can only be
        // identified once every enabled media socket has been identified.
        if (codecId == -1 && controlEnabled && control == null && allMediaStreamsBound()) {
            bindControl(ctx);
            return true;
        }

        return false;
    }

    private void bindVideo(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        pendingChannels.remove(channel);

        channel.pipeline().addAfter(
                "streamClassifier", "media", new ScrcpyMediaDecoder());
        channel.pipeline().addAfter(
                "media", "configMerger", new ConfigPacketMerger());
        if (videoHandler != null) {
            channel.pipeline().addAfter(
                    "configMerger", "videoHandler", videoHandler);
        }

        video = channel;
        channel.pipeline().remove("streamClassifier");
        System.out.printf("[scrcpy-java] video socket identified: %s%n",
                channel.remoteAddress());
        maybeBindControl();
    }

    private void bindAudio(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        pendingChannels.remove(channel);

        channel.pipeline().addAfter(
                "streamClassifier", "audioMedia", new ScrcpyAudioDecoder());
        if (audioHandler != null) {
            channel.pipeline().addAfter(
                    "audioMedia", "audioHandler", audioHandler);
        }

        audio = channel;
        channel.pipeline().remove("streamClassifier");
        System.out.printf("[scrcpy-java] audio socket identified: %s%n",
                channel.remoteAddress());
        maybeBindControl();
    }

    private void bindControl(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        pendingChannels.remove(channel);

        channel.pipeline().addAfter(
                "streamClassifier", "deviceMessage", new DeviceMessageDecoder());
        channel.pipeline().addAfter(
                "deviceMessage", "controlEncoder", new ControlMessageEncoder());

        ControlMessageQueue queue = new ControlMessageQueue();
        controlQueue = queue;
        channel.pipeline().addAfter(
                "controlEncoder", "controlQueue", queue);

        if (controlHandler != null) {
            channel.pipeline().addAfter(
                    "controlQueue", "controlHandler", controlHandler);
        }

        control = channel;
        System.out.printf("[scrcpy-java] control socket identified: %s%n",
                channel.remoteAddress());
        maybeBindControl();
    }

    private boolean allMediaStreamsBound() {
        return (!videoEnabled || video != null)
                && (!audioEnabled || audio != null);
    }

    /**
     * Once all media sockets are identified, the only remaining pending
     * connection is the control socket. It may be completely idle, so it
     * cannot be detected from inbound bytes.
     */
    private synchronized void maybeBindControl() {
        if (!controlEnabled || control != null || !allMediaStreamsBound()) {
            return;
        }

        Channel channel = pendingChannels.stream().findFirst().orElse(null);
        if (channel == null) {
            return;
        }

        pendingChannels.remove(channel);
        channel.eventLoop().execute(() -> {
            if (closed.get() || control != null || !channel.isActive()) {
                if (!closed.get() && channel.isActive() && control == null) {
                    pendingChannels.add(channel);
                }
                return;
            }

            ChannelHandlerContext classifier =
                    channel.pipeline().context("streamClassifier");
            if (classifier != null) {
                bindControl(classifier);
            }
        });
    }

    public void listen(ChannelInboundHandler videoHandler,
                        ChannelInboundHandler audioHandler,
                        ChannelInboundHandler controlHandler,
                        boolean videoEnabled,
                        boolean audioEnabled,
                        boolean controlEnabled) throws Exception {
        if (!videoEnabled && !audioEnabled && !controlEnabled) {
            throw new IllegalArgumentException(
                    "At least one scrcpy socket must be enabled");
        }

        this.videoEnabled = videoEnabled;
        this.audioEnabled = audioEnabled;
        this.controlEnabled = controlEnabled;
        this.videoHandler = videoHandler;
        this.audioHandler = audioHandler;
        this.controlHandler = controlHandler;

        listener = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        // The first TCP connection, regardless of whether it is
                        // video, audio or control, carries the 64-byte device
                        // name before its own stream data.
                        if (video == null
                                && audio == null
                                && control == null
                                && pendingChannels.isEmpty()) {
                            firstSocket(channel.pipeline());
                        }

                        addClassifier(channel);

                        // If control is the only enabled stream, it has no
                        // codec header and may never send inbound data.
                        if (!videoEnabled && !audioEnabled && controlEnabled) {
                            ChannelHandlerContext classifier =
                                    channel.pipeline().context("streamClassifier");
                            if (classifier != null) {
                                bindControl(classifier);
                            }
                        }

                        System.out.printf("[scrcpy-java] socket accepted: %s%n",
                                channel.remoteAddress());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port))
                .sync()
                .channel();

        localPort = ((InetSocketAddress) listener.localAddress()).getPort();
        System.out.printf(
                "[scrcpy-java] listening on 127.0.0.1:%d for scrcpy reverse tunnel%n",
                localPort);
    }

    public void open(ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        throw new UnsupportedOperationException(
                "Use listen() for reverse scrcpy tunnel");
    }

    public void open(boolean videoEnabled,
                     boolean audioEnabled,
                     boolean controlEnabled,
                     ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        throw new UnsupportedOperationException(
                "Use listen() for reverse scrcpy tunnel");
    }

    public int localPort() {
        if (localPort <= 0) {
            throw new IllegalStateException("scrcpy listener is not started");
        }
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Channel[] channels = {video, audio, control, listener};
        for (Channel channel : channels) {
            if (channel == null) {
                continue;
            }
            try {
                channel.close().syncUninterruptibly();
            } catch (Throwable t) {
                System.err.println(
                        "[scrcpy-java] channel close failed: " + t);
            }
        }

        group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
                .syncUninterruptibly();
    }
}
