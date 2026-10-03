package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: toggle summon/dismiss of all Echoes. The server does all the checks. */
public record EchoTogglePayload() implements CustomPacketPayload {
    public static final Type<EchoTogglePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_toggle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoTogglePayload> CODEC = StreamCodec.unit(new EchoTogglePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
