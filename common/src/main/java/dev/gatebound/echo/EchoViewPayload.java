package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: look through the entity with this id, or back through your own eyes when the id is -1. */
public record EchoViewPayload(int entityId) implements CustomPacketPayload {
    public static final Type<EchoViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoViewPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoViewPayload decode(RegistryFriendlyByteBuf buf) {
            return new EchoViewPayload(buf.readVarInt() - 1);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoViewPayload p) {
            buf.writeVarInt(p.entityId + 1);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
