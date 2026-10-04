package dev.gatebound.echo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/** A player's saved Echoes (bound mobs), which group each belongs to, and each group's order. Lives inside PlayerData. */
public final class EchoRoster {
    public static final int GROUPS = 4;

    /** Orders a group can be given. */
    public static final int FOLLOW = 0;
    public static final int GUARD = 1;
    public static final int SEEK = 2;
    public static final int GUIDE = 3;
    /** Eye-only order: fly straight to the selected gate. Never stored on a group. */
    public static final int GOTO = 4;

    /** One bound mob: its entity type id (e.g. minecraft:zombie), the display name captured at binding, and its group. */
    public record Entry(String type, String name, int group) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Entry::type),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Entry::name),
                Codec.INT.optionalFieldOf("group", 0).forGetter(Entry::group)
        ).apply(i, Entry::new));

        public Entry(String type, String name) {
            this(type, name, 0);
        }

        public Entry withGroup(int g) {
            return new Entry(type, name, Math.max(0, Math.min(GROUPS - 1, g)));
        }
    }

    public static final Codec<EchoRoster> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(r -> r.entries),
            Codec.INT.listOf().optionalFieldOf("group_modes", List.of()).forGetter(r -> {
                List<Integer> l = new ArrayList<>();
                for (int m : r.groupModes) {
                    l.add(m);
                }
                return l;
            }),
            Codec.INT.optionalFieldOf("bonus_slots", 0).forGetter(r -> r.bonusSlots)
    ).apply(i, EchoRoster::new));

    public final List<Entry> entries = new ArrayList<>();
    /** Current order of each group. */
    public final int[] groupModes = new int[GROUPS];
    /** Extra echo slots from Echo Shards. */
    public int bonusSlots;

    public EchoRoster() {
        resetModes();
    }

    private void resetModes() {
        for (int i = 0; i < GROUPS; i++) {
            groupModes[i] = i; // out of the box: group 1 follows, 2 guards, 3 seeks gates, 4 guides
        }
    }

    /** Defensive copy; codec defaults are shared objects. */
    public EchoRoster copy() {
        EchoRoster r = new EchoRoster();
        r.entries.addAll(entries);
        System.arraycopy(groupModes, 0, r.groupModes, 0, GROUPS);
        r.bonusSlots = bonusSlots;
        return r;
    }

    private EchoRoster(List<Entry> saved, List<Integer> modes, int bonus) {
        resetModes();
        this.entries.addAll(saved);
        this.bonusSlots = Math.max(0, bonus);
        for (int i = 0; i < GROUPS && i < modes.size(); i++) {
            this.groupModes[i] = Math.max(FOLLOW, Math.min(GUIDE, modes.get(i)));
        }
    }
}
