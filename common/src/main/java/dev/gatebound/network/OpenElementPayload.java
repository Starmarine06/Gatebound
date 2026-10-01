package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: open the Mage element selection screen. */
public record OpenElementPayload() implements CustomPacketPayload {
    public static final Type<OpenElementPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "open_element"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenElementPayload> CODEC = StreamCodec.unit(new OpenElementPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
