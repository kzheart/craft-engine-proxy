package net.momirealms.craftengine.proxy.velocity.network.inject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.momirealms.craftengine.proxy.common.network.ChannelConnection;
import net.momirealms.craftengine.proxy.common.network.protocol.ConnectionState;
import net.momirealms.craftengine.proxy.common.network.protocol.PacketSide;
import net.momirealms.craftengine.proxy.common.util.ProxyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolStateSynchronizerTest {
    enum State { HANDSHAKE, LOGIN, CONFIG, PLAY }
    static class NativeEncoder extends ChannelOutboundHandlerAdapter { private State state = State.CONFIG; }
    static class NativeDecoder extends ChannelInboundHandlerAdapter { private State state = State.CONFIG; }

    @Test
    void preparedConfigurationFinishDoesNotMisreadEntitySpawnAsPluginMessage() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NativeEncoder encoder = new NativeEncoder();
        NativeDecoder decoder = new NativeDecoder();
        channel.pipeline().addLast("minecraft-decoder", decoder);
        channel.pipeline().addLast("minecraft-encoder", encoder);
        ChannelConnection connection = new ChannelConnection(channel, null);
        connection.setConnectionState(ConnectionState.CONFIGURATION);
        List<ConnectionState> observed = new ArrayList<>();
        PacketPipelineInjector.addTo(channel, (current, player, side, buffer) -> {
            ConnectionState state = current.getConnectionState(side);
            observed.add(state);
            // CONFIG 0x01 is a plugin message; PLAY 0x01 is an entity spawn.
            if (side == PacketSide.SERVER && state == ConnectionState.CONFIGURATION) {
                ProxyByteBuf payload = new ProxyByteBuf(buffer.duplicate());
                payload.readVarInt();
                payload.readKey();
            }
            return buffer;
        }, connection);
        // Deliberately oversized string length makes the wrong CONFIG route fail.
        ByteBuf malformed = Unpooled.buffer().writeByte(1).writeByte(0xd1).writeByte(0xfd).writeByte(3).writeZero(47);
        ProxyByteBuf wrongRoute = new ProxyByteBuf(malformed.duplicate());
        wrongRoute.readVarInt();
        assertThrows(IndexOutOfBoundsException.class, wrongRoute::readKey);

        // Limbo changes the codec without routing its prepared finish packet through CE.
        encoder.state = State.PLAY;
        assertTrue(channel.writeOutbound(malformed));
        ByteBuf delivered = channel.readOutbound();
        assertEquals(51, delivered.readableBytes());
        assertEquals(1, delivered.getUnsignedByte(0));
        delivered.release();
        assertEquals(List.of(ConnectionState.PLAY), observed);
        assertEquals(ConnectionState.CONFIGURATION, connection.getConnectionState(PacketSide.CLIENT));

        // A subsequent server switch must follow CONFIG again, not stay pinned to PLAY.
        encoder.state = State.CONFIG;
        ByteBuf pluginMessage = Unpooled.buffer().writeByte(1).writeByte(9).writeBytes("test:ping".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(channel.writeOutbound(pluginMessage));
        ((ByteBuf) channel.readOutbound()).release();
        assertEquals(ConnectionState.CONFIGURATION, observed.getLast());
        decoder.state = State.PLAY;
        assertTrue(channel.writeInbound(Unpooled.buffer().writeByte(0x30)));
        ((ByteBuf) channel.readInbound()).release();
        assertEquals(ConnectionState.PLAY, observed.getLast());
        assertEquals(ConnectionState.CONFIGURATION, connection.getConnectionState(PacketSide.SERVER));
        assertFalse(channel.finishAndReleaseAll());
    }
}
