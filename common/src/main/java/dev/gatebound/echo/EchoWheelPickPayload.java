package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the entry chosen on the C wheel for the given soul body or echo. */
public record EchoWheelPickPayload(int entityId, int choice) implements CustomPacketPayload {
    public static final Type<EchoWheelPickPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_wheel_pick"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoWheelPickPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoWheelPickPayload decode(RegistryFriendlyByteBuf buf) {
            return new EchoWheelPickPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoWheelPickPayload p) {
            buf.writeVarInt(p.entityId);
            buf.writeVarInt(p.choice);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
