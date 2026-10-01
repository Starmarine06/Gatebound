package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: open the class selection screen. */
public record OpenAwakeningPayload() implements CustomPacketPayload {
    public static final Type<OpenAwakeningPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "open_awakening"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAwakeningPayload> CODEC = StreamCodec.unit(new OpenAwakeningPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
