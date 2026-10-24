package com.jian.scrcpy.net;
import io.netty.buffer.Unpooled;import io.netty.channel.embedded.EmbeddedChannel;import org.junit.jupiter.api.Test;import java.nio.charset.StandardCharsets;import static org.junit.jupiter.api.Assertions.*;
class DeviceInfoDecoderTest {
 @Test void reads64ByteName(){byte[] b=new byte[64];byte[] n="Pixel 9".getBytes(StandardCharsets.UTF_8);System.arraycopy(n,0,b,0,n.length);var c=new EmbeddedChannel(new DeviceInfoDecoder());assertTrue(c.writeInbound(Unpooled.wrappedBuffer(b)));var d=(DeviceInfoDecoder.DeviceInfo)c.readInbound();assertEquals("Pixel 9",d.name());c.finishAndReleaseAll();}
}