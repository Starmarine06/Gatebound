package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server reply to a {@link PingPayload}. Used to verify C2S networking. */
public record PongPayload() implements CustomPacketPayload {
    public static final Type<PongPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "pong"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PongPayload> CODEC = StreamCodec.unit(new PongPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
