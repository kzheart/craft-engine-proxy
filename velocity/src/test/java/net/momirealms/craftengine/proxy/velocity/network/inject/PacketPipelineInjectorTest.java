package net.momirealms.craftengine.proxy.velocity.network.inject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.momirealms.craftengine.proxy.common.network.ChannelConnection;
import net.momirealms.craftengine.proxy.common.network.protocol.PacketSide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketPipelineInjectorTest {
    @Test
    void restoresRawPacketCaptureAfterLoginReinsertsCompressionWithoutEvent() {
        EmbeddedChannel channel = new EmbeddedChannel();
        List<Integer> outboundIds = new ArrayList<>();
        List<Integer> inboundIds = new ArrayList<>();
        channel.pipeline().addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
        ChannelConnection connection = new ChannelConnection(channel, null);
        PacketPipelineInjector.addTo(channel, (ignored, player, side, buffer) -> {
            (side == PacketSide.SERVER ? outboundIds : inboundIds)
                    .add((int) buffer.getUnsignedByte(buffer.readerIndex()));
            return buffer;
        }, connection);

        installCompression(channel);
        PacketPipelineInjector.relocate(channel.pipeline());
        assertRawPackets(channel, outboundIds, inboundIds);

        // LimboAPI removes and reinserts compression immediately before native codecs.
        // It does not emit Velocity's COMPRESSION_ENABLED event during this restoration.
        channel.pipeline().remove("compression-encoder");
        channel.pipeline().remove("compression-decoder");
        installCompression(channel);
        assertTrue(channel.pipeline().names().indexOf("craftengine_proxy_packet_encoder")
                < channel.pipeline().names().indexOf("compression-encoder"));
        channel.eventLoop().execute(() -> PacketPipelineInjector.relocate(channel.pipeline()));
        channel.runPendingTasks();
        assertRawPackets(channel, outboundIds, inboundIds);
        assertTrue(channel.finishAndReleaseAll() == false);
    }

    @Test
    void cancelledPacketReleasesBufferAndCompletesWritePromise() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelConnection connection = new ChannelConnection(channel, null);
        channel.pipeline().addLast(new PacketEncoder((ignored, player, side, buffer)
                -> Unpooled.EMPTY_BUFFER, connection));
        ByteBuf packet = Unpooled.buffer().writeByte(0x4f);
        ChannelPromise promise = channel.newPromise();
        channel.writeAndFlush(packet, promise);
        assertTrue(promise.isSuccess());
        assertEquals(0, packet.refCnt());
        channel.finishAndReleaseAll();
    }

    private static void installCompression(EmbeddedChannel channel) {
        channel.pipeline().addBefore("minecraft-encoder", "compression-encoder", new MessageToByteEncoder<ByteBuf>() {
            @Override
            protected void encode(ChannelHandlerContext context, ByteBuf packet, ByteBuf output) {
                output.writeByte(0);
                output.writeBytes(packet);
            }
        });
        channel.pipeline().addBefore("minecraft-decoder", "compression-decoder", new MessageToMessageDecoder<ByteBuf>() {
            @Override
            protected void decode(ChannelHandlerContext context, ByteBuf packet, List<Object> output) {
                packet.skipBytes(1);
                output.add(packet.retain());
            }
        });
    }

    private static void assertRawPackets(EmbeddedChannel channel, List<Integer> outboundIds, List<Integer> inboundIds) {
        channel.writeOutbound(Unpooled.buffer().writeByte(0x4f).writeInt(123));
        ByteBuf encoded = channel.readOutbound();
        assertEquals(0, encoded.readUnsignedByte());
        assertEquals(0x4f, encoded.readUnsignedByte());
        encoded.release();
        channel.writeInbound(Unpooled.buffer().writeByte(0).writeByte(0x30).writeInt(321));
        ByteBuf decoded = channel.readInbound();
        assertEquals(0x30, decoded.readUnsignedByte());
        decoded.release();
        assertEquals(0x4f, outboundIds.getLast());
        assertEquals(0x30, inboundIds.getLast());
    }
}
