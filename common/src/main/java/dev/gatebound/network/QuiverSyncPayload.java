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

/** Server to client: which players are wearing a quiver on their back right now, so every client can draw it. */
public record QuiverSyncPayload(List<UUID> carriers) implements CustomPacketPayload {
    public static final Type<QuiverSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "quiver_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuiverSyncPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), QuiverSyncPayload::carriers,
            QuiverSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
