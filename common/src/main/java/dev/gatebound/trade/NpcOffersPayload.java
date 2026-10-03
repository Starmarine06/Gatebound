package dev.gatebound.trade;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Server to client: the offer list of an NPC; opens the trade screen, or refreshes it after a purchase. */
public record NpcOffersPayload(int entityId, boolean broker, List<OfferView> offers) implements CustomPacketPayload {
    public static final Type<NpcOffersPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "npc_offers"));

    /** One line of the shop: you pay {@code price} of {@code payId} and receive {@code count} of {@code itemId}. */
    public record OfferView(String itemId, int count, String payId, int price, int stock, int maxStock) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OfferView> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OfferView::itemId,
                ByteBufCodecs.VAR_INT, OfferView::count,
                ByteBufCodecs.STRING_UTF8, OfferView::payId,
                ByteBufCodecs.VAR_INT, OfferView::price,
                ByteBufCodecs.VAR_INT, OfferView::stock,
                ByteBufCodecs.VAR_INT, OfferView::maxStock,
                OfferView::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcOffersPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NpcOffersPayload::entityId,
            ByteBufCodecs.BOOL, NpcOffersPayload::broker,
            OfferView.CODEC.apply(ByteBufCodecs.list()), NpcOffersPayload::offers,
            NpcOffersPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
