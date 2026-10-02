package net.momirealms.craftengine.proxy.velocity.network.inject;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.momirealms.craftengine.proxy.common.network.ChannelConnection;
import net.momirealms.craftengine.proxy.common.network.protocol.ConnectionState;
import net.momirealms.craftengine.proxy.common.network.protocol.PacketSide;

import java.lang.reflect.Field;
import java.util.Optional;

/** Uses the native codec state, including transitions made by Limbo's prepared packets. */
final class ProtocolStateSynchronizer {
    private static final ClassValue<Optional<Field>> STATE_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField("state");
                    return field.trySetAccessible() ? Optional.of(field) : Optional.empty();
                } catch (NoSuchFieldException ignored) {
                    // Native codec subclasses may inherit this field.
                }
            }
            return Optional.empty();
        }
    };

    private ProtocolStateSynchronizer() {}

    static void synchronize(ChannelPipeline pipeline, ChannelConnection connection, PacketSide side) {
        ChannelHandler codec = pipeline.get(side == PacketSide.SERVER ? "minecraft-encoder" : "minecraft-decoder");
        if (codec == null) return;
        Optional<Field> field = STATE_FIELDS.get(codec.getClass());
        if (field.isEmpty()) return;
        try {
            Object value = field.get().get(codec);
            if (!(value instanceof Enum<?> state)) return;
            String name = state.name();
            // LimboAPI allocates unnamed StateRegistry instances backed by PLAY registries.
            if (name == null && state.getDeclaringClass().getName()
                    .equals("com.velocitypowered.proxy.protocol.StateRegistry")) name = "PLAY";
            if (name == null) return;
            ConnectionState mapped = switch (name) {
                case "HANDSHAKE" -> ConnectionState.HANDSHAKING;
                case "STATUS" -> ConnectionState.STATUS;
                case "LOGIN" -> ConnectionState.LOGIN;
                case "CONFIG" -> ConnectionState.CONFIGURATION;
                case "PLAY" -> ConnectionState.PLAY;
                default -> null;
            };
            if (mapped == null) return;
            if (side == PacketSide.SERVER) connection.setEncoderState(mapped);
            else connection.setDecoderState(mapped);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read Velocity codec protocol state", exception);
        }
    }
}
