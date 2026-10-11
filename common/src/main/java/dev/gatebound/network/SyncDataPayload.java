package dev.gatebound.network;

import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server to client snapshot of the player progression. */
public record SyncDataPayload(int level, int xp, int xpToNext, int statPoints, List<Integer> stats, String classId,
                              List<String> elements, List<String> picks, Map<String, Integer> uses) implements CustomPacketPayload {
    public static final Type<SyncDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "sync_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDataPayload> CODEC = new StreamCodec<>() {
        @Override
        public SyncDataPayload decode(RegistryFriendlyByteBuf buf) {
            int level = buf.readVarInt();
            int xp = buf.readVarInt();
            int xpToNext = buf.readVarInt();
            int points = buf.readVarInt();
            int statCount = buf.readVarInt();
            List<Integer> stats = new ArrayList<>(statCount);
            for (int i = 0; i < statCount; i++) {
                stats.add(buf.readVarInt());
            }
            String classId = buf.readUtf();
            int elementCount = buf.readVarInt();
            List<String> elements = new ArrayList<>(elementCount);
            for (int i = 0; i < elementCount; i++) {
                elements.add(buf.readUtf());
            }
            int pickCount = buf.readVarInt();
            List<String> picks = new ArrayList<>(pickCount);
            for (int i = 0; i < pickCount; i++) {
                picks.add(buf.readUtf());
            }
            int useCount = buf.readVarInt();
            Map<String, Integer> uses = new HashMap<>();
            for (int i = 0; i < useCount; i++) {
                uses.put(buf.readUtf(), buf.readVarInt());
            }
            return new SyncDataPayload(level, xp, xpToNext, points, stats, classId, elements, picks, uses);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SyncDataPayload p) {
            buf.writeVarInt(p.level);
            buf.writeVarInt(p.xp);
            buf.writeVarInt(p.xpToNext);
            buf.writeVarInt(p.statPoints);
            buf.writeVarInt(p.stats.size());
            for (int v : p.stats) {
                buf.writeVarInt(v);
            }
            buf.writeUtf(p.classId);
            buf.writeVarInt(p.elements.size());
            for (String e : p.elements) {
                buf.writeUtf(e);
            }
            buf.writeVarInt(p.picks.size());
            for (String s : p.picks) {
                buf.writeUtf(s);
            }
            buf.writeVarInt(p.uses.size());
            for (Map.Entry<String, Integer> e : p.uses.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeVarInt(e.getValue());
            }
        }
    };

    /** Builds a sync payload from player data. */
    public static SyncDataPayload of(PlayerData data) {
        List<Integer> stats = new ArrayList<>();
        for (int v : data.stats) {
            stats.add(v);
        }
        return new SyncDataPayload(data.level, data.xp, data.xpToNext(), data.statPoints, stats, data.classId,
                new ArrayList<>(data.elements), new ArrayList<>(data.picks), new HashMap<>(data.uses));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
