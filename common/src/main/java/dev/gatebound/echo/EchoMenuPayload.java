package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: ask for the Echoes screen. The server answers with the roster. */
public record EchoMenuPayload() implements CustomPacketPayload {
    public static final Type<EchoMenuPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoMenuPayload> CODEC = StreamCodec.unit(new EchoMenuPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
