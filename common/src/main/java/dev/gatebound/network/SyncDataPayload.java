package dev.gatebound.network;

import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server to client snapshot of the player progression. */
public record SyncDataPayload(int level, int xp, int xpToNext, int statPoints, List<Integer> stats, String classId)
        implements CustomPacketPayload {
    public static final Type<SyncDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "sync_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDataPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncDataPayload::level,
            ByteBufCodecs.VAR_INT, SyncDataPayload::xp,
            ByteBufCodecs.VAR_INT, SyncDataPayload::xpToNext,
            ByteBufCodecs.VAR_INT, SyncDataPayload::statPoints,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), SyncDataPayload::stats,
            ByteBufCodecs.STRING_UTF8, SyncDataPayload::classId,
            SyncDataPayload::new);

    public static SyncDataPayload of(PlayerData data) {
        List<Integer> stats = new ArrayList<>();
        for (int v : data.stats) {
            stats.add(v);
        }
        return new SyncDataPayload(data.level, data.xp, data.xpToNext(), data.statPoints, stats, data.classId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
