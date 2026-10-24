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
        List<String> names = new ArrayList<>();
        if (videoEnabled) names.add("video");
        if (audioEnabled) names.add("audio");
        if (controlEnabled) names.add("control");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("At least one scrcpy socket must be enabled");
        }

        listener = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<>() {
                    private int index;

                    @Override
                    protected void initChannel(Channel channel) {
                        int i = index++;
                        if (i >= names.size()) {
                            channel.close();
                            return;
                        }

                        String name = names.get(i);
                        configure(channel.pipeline(), i == 0, name,
                                videoHandler, audioHandler, controlHandler);

                        switch (name) {
                            case "video" -> video = channel;
                            case "audio" -> audio = channel;
                            case "control" -> control = channel;
                            default -> throw new IllegalStateException("unknown socket: " + name);
                        }

                        System.out.printf("[scrcpy-java] %s socket accepted: %s%n",
                                name, channel.remoteAddress());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port))
                .sync()
                .channel();

        System.out.printf("[scrcpy-java] listening on 127.0.0.1:%d for scrcpy reverse tunnel%n", port);
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
