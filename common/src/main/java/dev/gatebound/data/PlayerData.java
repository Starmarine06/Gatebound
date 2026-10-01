package dev.gatebound.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/** Per-player progression. Mutate it, then persist through Progression.save. */
public final class PlayerData {
    public static final int MAX_LEVEL = 100;
    public static final int STAT_POINTS_PER_LEVEL = 3;

    public static final Codec<PlayerData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("level", 1).forGetter(d -> d.level),
            Codec.INT.optionalFieldOf("xp", 0).forGetter(d -> d.xp),
            Codec.INT.optionalFieldOf("stat_points", 0).forGetter(d -> d.statPoints),
            Codec.INT.listOf().optionalFieldOf("stats", List.of()).forGetter(d -> toList(d.stats)),
            Codec.STRING.optionalFieldOf("class", "").forGetter(d -> d.classId)
    ).apply(i, PlayerData::new));

    public int level;
    public int xp;
    public int statPoints;
    public final int[] stats = new int[Stat.values().length];
    public String classId;

    public PlayerData() {
        this(1, 0, 0, List.of(), "");
    }

    private PlayerData(int level, int xp, int statPoints, List<Integer> savedStats, String classId) {
        this.level = Math.max(1, Math.min(MAX_LEVEL, level));
        this.xp = Math.max(0, xp);
        this.statPoints = Math.max(0, statPoints);
        this.classId = classId;
        for (int n = 0; n < stats.length; n++) {
            stats[n] = n < savedStats.size() ? savedStats.get(n) : Stat.BASE_VALUE;
        }
    }

    private static List<Integer> toList(int[] arr) {
        List<Integer> out = new ArrayList<>(arr.length);
        for (int v : arr) {
            out.add(v);
        }
        return out;
    }

    public int get(Stat stat) {
        return stats[stat.ordinal()];
    }

    public static int xpToNext(int level) {
        return 50 + level * level * 10;
    }

    public int xpToNext() {
        return xpToNext(level);
    }

    /** Adds XP and levels up as needed. Returns how many levels were gained. */
    public int addXp(int amount) {
        if (amount <= 0 || level >= MAX_LEVEL) {
            return 0;
        }
        xp += amount;
        int gained = 0;
        while (level < MAX_LEVEL && xp >= xpToNext()) {
            xp -= xpToNext();
            level++;
            statPoints += STAT_POINTS_PER_LEVEL;
            gained++;
        }
        if (level >= MAX_LEVEL) {
            xp = 0;
        }
        return gained;
    }

    /** Sets the level directly. Unspent points are recomputed from the level minus points already spent. */
    public void setLevel(int newLevel) {
        newLevel = Math.max(1, Math.min(MAX_LEVEL, newLevel));
        int spent = 0;
        for (Stat s : Stat.values()) {
            spent += get(s) - Stat.BASE_VALUE;
        }
        level = newLevel;
        xp = 0;
        statPoints = Math.max(0, (newLevel - 1) * STAT_POINTS_PER_LEVEL - spent);
    }

    public boolean spend(Stat stat, int points) {
        if (points <= 0 || points > statPoints) {
            return false;
        }
        statPoints -= points;
        stats[stat.ordinal()] += points;
        return true;
    }
}
