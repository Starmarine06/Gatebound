package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server. kind 0: set group a (-1 = all) to order b (0 follow, 1 guard, 2 seek, 3 guide);
 * kind 1: put echo slot a into group b; kind 2: select found gate a.
 */
public record EchoOrderPayload(int kind, int a, int b) implements CustomPacketPayload {
    public static final Type<EchoOrderPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoOrderPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoOrderPayload decode(RegistryFriendlyByteBuf buf) {
            return new EchoOrderPayload(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoOrderPayload p) {
            buf.writeVarInt(p.kind);
            buf.writeVarInt(p.a + 1);
            buf.writeVarInt(p.b);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
