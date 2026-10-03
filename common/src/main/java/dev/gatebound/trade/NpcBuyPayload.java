package dev.gatebound.trade;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: buy one unit of an NPC offer. The server validates distance, stock and funds. */
public record NpcBuyPayload(int entityId, int offerIndex) implements CustomPacketPayload {
    public static final Type<NpcBuyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "npc_buy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcBuyPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NpcBuyPayload::entityId,
            ByteBufCodecs.VAR_INT, NpcBuyPayload::offerIndex,
            NpcBuyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
