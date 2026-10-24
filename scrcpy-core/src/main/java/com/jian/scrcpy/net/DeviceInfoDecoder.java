package com.jian.scrcpy.net;
import io.netty.buffer.ByteBuf;import io.netty.channel.ChannelHandlerContext;import io.netty.handler.codec.ByteToMessageDecoder;import java.nio.charset.StandardCharsets;import java.util.List;
public final class DeviceInfoDecoder extends ByteToMessageDecoder {
 public record DeviceInfo(String name) {}
 private boolean done;
 @Override protected void decode(ChannelHandlerContext ctx,ByteBuf in,List<Object> out){
  if(done){if(in.isReadable())out.add(in.readRetainedSlice(in.readableBytes()));return;}
  if(in.readableBytes()<64)return;
  byte[] b=new byte[64];in.readBytes(b);int n=0;while(n<b.length&&b[n]!=0)n++;
  done=true;out.add(new DeviceInfo(new String(b,0,n,StandardCharsets.UTF_8)));
  ctx.pipeline().remove(this);
 }
}