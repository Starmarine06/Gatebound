package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the player pressed the parry key. Timing is judged on the server. */
public record ParryPayload() implements CustomPacketPayload {
    public static final Type<ParryPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "parry"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ParryPayload> CODEC = StreamCodec.unit(new ParryPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
