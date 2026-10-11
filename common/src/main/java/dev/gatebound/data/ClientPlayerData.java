package dev.gatebound.data;

import dev.gatebound.network.SyncDataPayload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-side mirror of the local player progression, filled by SyncDataPayload. Read-only for UI and HUD. */
public final class ClientPlayerData {
    public static int level = 1;
    public static int xp;
    public static int xpToNext = PlayerData.xpToNext(1);
    public static int statPoints;
    public static final int[] STATS = new int[Stat.values().length];
    public static String classId = "";
    public static List<String> elements = new ArrayList<>();
    public static List<String> picks = new ArrayList<>();
    public static Map<String, Integer> uses = new HashMap<>();

    static {
        Arrays.fill(STATS, Stat.BASE_VALUE);
    }

    /** Static holder; not instantiable. */
    private ClientPlayerData() {
    }

    /** Stores the latest player data sync. */
    public static void update(SyncDataPayload p) {
        level = p.level();
        xp = p.xp();
        xpToNext = p.xpToNext();
        statPoints = p.statPoints();
        for (int n = 0; n < STATS.length; n++) {
            STATS[n] = n < p.stats().size() ? p.stats().get(n) : Stat.BASE_VALUE;
        }
        classId = p.classId();
        elements = new ArrayList<>(p.elements());
        picks = new ArrayList<>(p.picks());
        uses = new HashMap<>(p.uses());
    }

    /** Mana cost of a skill for the local player, after the proficiency discount. */
    public static int skillCost(dev.gatebound.skill.Skill skill) {
        return dev.gatebound.skill.Proficiency.cost(skill.manaCost(), uses.getOrDefault(skill.id(), 0));
    }

    /** Proficiency rank of a skill for the local player. */
    public static int skillRank(dev.gatebound.skill.Skill skill) {
        return dev.gatebound.skill.Proficiency.rank(uses.getOrDefault(skill.id(), 0));
    }
}
