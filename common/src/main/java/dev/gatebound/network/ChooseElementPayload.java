package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the element a Mage picked as their main affinity. The server validates it and rolls bonus elements. */
public record ChooseElementPayload(String elementId) implements CustomPacketPayload {
    public static final Type<ChooseElementPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "choose_element"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseElementPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ChooseElementPayload::elementId, ChooseElementPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
