package dev.gatebound.skill;

import java.util.HashSet;
import java.util.Set;

/** Tunables for the Rogue's Shadow / Mark / Ambush system. Section "rogue" in config/gatebound.json. */
public final class RogueConfig {
    private RogueConfig() {
    }

    /** Rogue max mana = 20 + 4 * Agility instead of Intellect. Turn off to keep the old formula for every class. */
    public static boolean agilityMana = true;

    // Ambush
    /** Ambush multiplier = ambushBase + ambushPerLevel * level. */
    public static double ambushBase = 2.5;
    public static double ambushPerLevel = 0.01;
    /** Extra multiplier for an ambush from behind the victim. */
    public static double behindMult = 1.5;
    /** Extra multiplier for an ambush launched from inside a Shadow Meld. */
    public static double meldMult = 1.5;
    /** Ambush multiplier while Shadow Dance is running. */
    public static double danceAmbush = 2.0;

    // Marks
    /** Extra damage a marked enemy takes from the player who marked it (0.25 = +25%). */
    public static double markBonus = 0.25;
    public static int markSeconds = 15;
    /** Mana refunded by an ambush on a marked enemy. */
    public static double markedAmbushMana = 6;

    /** Damage multiplier for a knife hit from behind the victim. */
    public static double knifeBehindMult = 1.5;

    // Passives (Rogue only)
    public static double critPerLuck = 0.005;
    public static double maxCritChance = 0.6;
    public static double critBase = 1.5;
    public static double critDamagePerAgility = 0.01;
    public static double maxCritMult = 3.0;

    /** Upward speed of the Rogue's R + Space power jump (a normal jump is 0.42, a Rogue's about 0.53). */
    public static double powerJump = 0.95;

    // Shadow Meld / Shadow Walk
    public static double meldRange = 24;
    public static int meldBaseSeconds = 12;
    public static double walkRange = 24;
    public static double coneDegrees = 25;
    public static int darkLightLevel = 4;
    public static double hopCost = 4;
    public static double drainPerSecond = 1;
    /** Ticks between two Shadow Walk hops. */
    public static int hopDelayTicks = 8;
    /** Blocks per tick a Shadow Walk hop travels. */
    public static double hopSpeed = 1.2;
    /** Entities (comma separated ids) a Rogue cannot meld into. */
    public static String bossBlacklist = "minecraft:wither,minecraft:ender_dragon,minecraft:warden";

    // Shadow Possession
    public static double possessionRange = 20;
    public static int possessionMaxBound = 5;
    public static int possessionArms = 4;
    public static int armIntervalTicks = 10;
    public static double deflectMana = 2;
    /** Bosses listed here get Slowness and Weakness instead of a full bind, and cannot be pulled or possessed. */
    public static String possessionBossBlacklist = "minecraft:wither,minecraft:ender_dragon,minecraft:warden";

    private static String cachedMeld = null;
    private static Set<String> meldSet = Set.of();
    private static String cachedPossess = null;
    private static Set<String> possessSet = Set.of();

    private static Set<String> parse(String csv) {
        Set<String> out = new HashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public static boolean meldBlocked(String entityId) {
        if (!bossBlacklist.equals(cachedMeld)) {
            cachedMeld = bossBlacklist;
            meldSet = parse(bossBlacklist);
        }
        return meldSet.contains(entityId);
    }

    public static boolean possessionBlocked(String entityId) {
        if (!possessionBossBlacklist.equals(cachedPossess)) {
            cachedPossess = possessionBossBlacklist;
            possessSet = parse(possessionBossBlacklist);
        }
        return possessSet.contains(entityId);
    }
}
