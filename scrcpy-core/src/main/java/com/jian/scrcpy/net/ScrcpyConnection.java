package com.jian.scrcpy.net;

import com.jian.scrcpy.protocol.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ScrcpyConnection implements AutoCloseable {
    private final EventLoopGroup group =
            new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    private final int port;
    private Channel video, audio, control, listener;
    private volatile String deviceName;

    public ScrcpyConnection(int p) {
        port = p;
    }

    private Channel connect(ChannelInitializer<Channel> initializer, String name) throws Exception {
        Exception last = null;
        for (int n = 0; n < 100; n++) {
            try {
                Channel channel = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(initializer)
                        .connect(new InetSocketAddress("127.0.0.1", port))
                        .sync()
                        .channel();
                System.out.printf("[scrcpy-java] %s socket connected: %s%n", name, channel.remoteAddress());
                return channel;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last;
    }

    private void configure(ChannelPipeline pipeline, boolean firstSocket, String name,
                           ChannelInboundHandler videoHandler,
                           ChannelInboundHandler audioHandler,
                           ChannelInboundHandler controlHandler) {
        if (firstSocket) {
            firstSocket(pipeline);
        }
        if ("video".equals(name)) {
            pipeline.addLast("media", new ScrcpyMediaDecoder());
            pipeline.addLast("configMerger", new ConfigPacketMerger());
            pipeline.addLast("videoHandler", videoHandler);
        } else if ("audio".equals(name)) {
            pipeline.addLast("media", new ScrcpyMediaDecoder());
            pipeline.addLast("audioHandler", audioHandler);
        } else {
            pipeline.addLast("deviceMessage", new DeviceMessageDecoder());
            pipeline.addLast("controlEncoder", new ControlMessageEncoder());
            pipeline.addLast("controlHandler", controlHandler);
        }
        exceptionLogging(pipeline, name);
    }

    private void firstSocket(ChannelPipeline pipeline) {
        pipeline.addLast("forwardProbe", new ForwardProbeDecoder());
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

    private void exceptionLogging(ChannelPipeline pipeline, String name) {
        pipeline.addLast("exceptionLogger", new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                System.err.println("[scrcpy-java] " + name + " pipeline error: " + cause);
                cause.printStackTrace(System.err);
                ctx.close();
            }
        });
    }

    public void listen(ChannelInboundHandler videoHandler,
                       ChannelInboundHandler audioHandler,
                       ChannelInboundHandler controlHandler,
                       boolean videoEnabled, boolean audioEnabled, boolean controlEnabled) throws Exception {
        List<ChannelInitializer<Channel>> initializers = new ArrayList<>();
        if (videoEnabled) initializers.add(initializer("video", true, videoHandler, audioHandler, controlHandler));
        if (audioEnabled) initializers.add(initializer("audio", initializers.isEmpty(), videoHandler, audioHandler, controlHandler));
        if (controlEnabled) initializers.add(initializer("control", initializers.isEmpty(), videoHandler, audioHandler, controlHandler));

        if (initializers.isEmpty()) throw new IllegalArgumentException("At least one scrcpy socket must be enabled");

        listener = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    private int index;
                    @Override
                    protected void initChannel(Channel channel) {
                        ChannelInitializer<Channel> initializer = initializers.get(index++);
                        initializer.initChannel(channel);
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port))
                .sync()
                .channel();

        System.out.printf("[scrcpy-java] listening on 127.0.0.1:%d for scrcpy reverse tunnel%n", port);
    }

    private ChannelInitializer<Channel> initializer(String name, boolean firstSocket,
                                                     ChannelInboundHandler videoHandler,
                                                     ChannelInboundHandler audioHandler,
                                                     ChannelInboundHandler controlHandler) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                configure(channel.pipeline(), firstSocket, name, videoHandler, audioHandler, controlHandler);
                System.out.printf("[scrcpy-java] %s socket accepted: %s%n", name, channel.remoteAddress());
            }
        };
    }

    public void open(ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        open(true, true, true, videoHandler, audioHandler, controlHandler);
    }

    public void open(boolean videoEnabled, boolean audioEnabled, boolean controlEnabled,
                     ChannelInboundHandler videoHandler,
                     ChannelInboundHandler audioHandler,
                     ChannelInboundHandler controlHandler) throws Exception {
        throw new UnsupportedOperationException("Use listen() for reverse scrcpy tunnel");
    }

    public String deviceName() {
        return deviceName;
    }

    public Channel control() {
        return control;
    }

    @Override
    public void close() {
        for (var channel : new Channel[]{video, audio, control, listener}) {
            if (channel != null) channel.close();
        }
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
