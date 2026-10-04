package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: give one echo (slot) or all echoes (slot -1) an order: 0 follow, 1 guard, 2 seek a gate. */
public record EchoOrderPayload(int slot, int mode) implements CustomPacketPayload {
    public static final Type<EchoOrderPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoOrderPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoOrderPayload decode(RegistryFriendlyByteBuf buf) {
            return new EchoOrderPayload(buf.readVarInt() - 1, buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoOrderPayload p) {
            buf.writeVarInt(p.slot + 1);
            buf.writeVarInt(p.mode);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
