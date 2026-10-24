package com.jian.scrcpy.net;
import com.jian.scrcpy.protocol.*;import io.netty.bootstrap.Bootstrap;import io.netty.channel.*;import io.netty.channel.nio.NioIoHandler;import io.netty.channel.socket.nio.NioSocketChannel;import java.net.*;import java.util.concurrent.TimeUnit;
public final class ScrcpyConnection implements AutoCloseable {
 private final EventLoopGroup group=new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());private final int port;private Channel video,audio,control;private volatile String deviceName;
 public ScrcpyConnection(int p){port=p;}
 private Channel connect(ChannelInitializer<Channel> i)throws Exception{Exception last=null;for(int n=0;n<100;n++){try{return new Bootstrap().group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY,true).handler(i).connect(new InetSocketAddress("127.0.0.1",port)).sync().channel();}catch(Exception e){last=e;Thread.sleep(100);}}throw last;}
 public void open(ChannelInboundHandler vh,ChannelInboundHandler ah,ChannelInboundHandler ch)throws Exception{
  video=connect(new ChannelInitializer<>(){protected void initChannel(Channel c){c.pipeline().addLast(new ForwardProbeDecoder(),new DeviceInfoDecoder(),new SimpleChannelInboundHandler<DeviceInfoDecoder.DeviceInfo>(){protected void channelRead0(ChannelHandlerContext x,DeviceInfoDecoder.DeviceInfo d){deviceName=d.name();x.pipeline().remove(this);}},new ScrcpyMediaDecoder(),new ConfigPacketMerger(),vh);}});
  audio=connect(new ChannelInitializer<>(){protected void initChannel(Channel c){c.pipeline().addLast(new ScrcpyMediaDecoder(),ah);}});
  control=connect(new ChannelInitializer<>(){protected void initChannel(Channel c){c.pipeline().addLast(new DeviceMessageDecoder(),new ControlMessageEncoder(),ch);}});
 }
 public String deviceName(){return deviceName;}public Channel control(){return control;}
 public void close(){for(var c:new Channel[]{video,audio,control})if(c!=null)c.close();group.shutdownGracefully(0,2,TimeUnit.SECONDS);}
}