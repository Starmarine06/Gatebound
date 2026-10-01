package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client. Carries a message the client shows in chat. Used to verify S2C networking. */
public record PingPayload(String message) implements CustomPacketPayload {
    public static final Type<PingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "ping"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PingPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PingPayload::message, PingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
