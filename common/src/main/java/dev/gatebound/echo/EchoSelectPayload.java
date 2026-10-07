package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the select key was pressed (bind the soul body beside you, or select the echo beside you). */
public record EchoSelectPayload() implements CustomPacketPayload {
    public static final Type<EchoSelectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoSelectPayload> CODEC = StreamCodec.unit(new EchoSelectPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
