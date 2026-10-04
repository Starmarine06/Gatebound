package dev.gatebound.echo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/** A player's saved Echoes (bound mobs) plus their behaviour mode. Lives inside PlayerData. */
public final class EchoRoster {
    /** One bound mob: its entity type id (e.g. minecraft:zombie) and the display name captured at binding. */
    public record Entry(String type, String name, int mode) {
        public static final int FOLLOW = 0;
        public static final int GUARD = 1;
        public static final int SEEK = 2;

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Entry::type),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Entry::name),
                Codec.INT.optionalFieldOf("mode", FOLLOW).forGetter(Entry::mode)
        ).apply(i, Entry::new));

        public Entry(String type, String name) {
            this(type, name, FOLLOW);
        }

        public Entry withMode(int newMode) {
            return new Entry(type, name, Math.max(FOLLOW, Math.min(SEEK, newMode)));
        }
    }

    public static final Codec<EchoRoster> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(r -> r.entries),
            Codec.BOOL.optionalFieldOf("guard", false).forGetter(r -> r.guard)
    ).apply(i, EchoRoster::new));

    public final List<Entry> entries = new ArrayList<>();
    /** False = follow the owner, true = hold position near where summoned. */
    public boolean guard;

    public EchoRoster() {
    }

    /** Defensive copy; codec defaults are shared objects. */
    public EchoRoster copy() {
        return new EchoRoster(entries, guard);
    }

    private EchoRoster(List<Entry> saved, boolean guard) {
        this.entries.addAll(saved);
        this.guard = guard;
    }
}
