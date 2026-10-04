package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: bind the soul body with this entity id as an Echo in the given group. */
public record EchoBindPayload(int entityId, int group) implements CustomPacketPayload {
    public static final Type<EchoBindPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_bind"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoBindPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoBindPayload decode(RegistryFriendlyByteBuf buf) {
            return new EchoBindPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoBindPayload p) {
            buf.writeVarInt(p.entityId);
            buf.writeVarInt(p.group);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
