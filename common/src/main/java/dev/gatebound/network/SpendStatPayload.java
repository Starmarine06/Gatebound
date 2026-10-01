package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: spend unspent stat points. The server validates stat id and amount. */
public record SpendStatPayload(String statId, int points) implements CustomPacketPayload {
    public static final Type<SpendStatPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "spend_stat"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpendStatPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SpendStatPayload::statId,
            ByteBufCodecs.VAR_INT, SpendStatPayload::points,
            SpendStatPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
