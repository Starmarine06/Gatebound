package dev.gatebound.dungeon;

/** Tunable values for gates and dungeon runs. Plain statics, like CombatConfig / MagicConfig. */
public final class DungeonConfig {
    private DungeonConfig() {
    }

    /** Master switch for natural overworld gate spawning. */
    public static boolean naturalSpawn = true;
    /** Ticks between spawn rolls (per server, one random overworld player is rolled each time). */
    public static int spawnCheckTicks = 600;
    /** Chance per roll that a gate appears near the chosen player. */
    public static double spawnChance = 0.02;
    /** Maximum natural gates that may exist at once in the world. */
    public static int maxNaturalGates = 8;
    /** A player will not get a new gate while another natural gate is within this many blocks. */
    public static int perPlayerRadius = 160;
    public static int spawnMinDistance = 24;
    public static int spawnMaxDistance = 64;
    /** Relative weights for ranks E, D, C, B, A, S. */
    public static int[] rankWeights = {50, 25, 14, 7, 3, 1};

    /** A run that has not been cleared is closed after this many game ticks (1 hour). */
    public static long runTimeoutTicks = 72000;
    /** After the boss dies, stragglers are sent home and the plot freed after this long (5 minutes). */
    public static long clearedLingerTicks = 6000;
    /** An uncleared run that has been empty for this long is reset (5 minutes). */
    public static long abandonedTicks = 6000;
    /** Ticks between gate/exit re-trigger checks for the same player. */
    public static int portalCooldownTicks = 60;
}
