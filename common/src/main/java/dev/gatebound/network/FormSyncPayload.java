package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/** Server to client: which players are in a level 100 Mage form right now and which element, so every client can skin them. */
public record FormSyncPayload(List<UUID> players, List<String> elements) implements CustomPacketPayload {
    public static final Type<FormSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "form_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FormSyncPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), FormSyncPayload::players,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), FormSyncPayload::elements,
            FormSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
