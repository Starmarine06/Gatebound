package dev.gatebound.dungeon;

/** Tunable values for gates and the Spire. Plain statics, like CombatConfig / MagicConfig (saved by GateboundConfig). */
public final class DungeonConfig {
    private DungeonConfig() {
    }

    // ------------------------------------------------------------- natural gates
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
    /** Relative weights for ranks E, D, C, B, A, S (which floor band a natural gate leads to). */
    public static int[] rankWeights = {50, 25, 14, 7, 3, 1};
    /** Within a band a gate picks floor = low + span * u^bias; higher bias favours the lower floors of the band. */
    public static double gateFloorBias = 1.8;
    /** A gate closes after this many game ticks (30 minutes). */
    public static long gateLifetimeTicks = 36000;
    /** A gate closes after this many entries. */
    public static int gateMaxUses = 8;
    /** Ticks between gate/exit re-trigger checks for the same player. */
    public static int portalCooldownTicks = 60;

    // ------------------------------------------------------------- gravitational pull
    public static boolean gatePull = true;
    /** Pull radius in blocks. */
    public static double pullRadius = 14.0;
    /** Maximum acceleration per tick at the gate centre (blocks/tick). */
    public static double pullStrength = 0.12;
    public static boolean pullItems = true;
    public static boolean pullMobs = true;
    /** Ticks a player is immune to the pull after leaving a gate or arriving from one. */
    public static int pullImmunityTicks = 100;
    public static boolean pullParticles = true;

    // ------------------------------------------------------------- the tower (floors)
    public static int towerMinFloors = 25;
    public static int towerMaxFloors = 60;
    /** Side length of each floor in blocks (rounded to a multiple of 16, clamped 96..256). */
    public static int floorSize = 160;
    /** Block placements per tick shared by all build jobs. */
    public static int buildBlocksPerTick = 2500;
    /** Soft wall-clock limit per tick for build jobs, in milliseconds. */
    public static int buildMillisPerTick = 12;
    /** Maximum ticks to wait for the chunks of a floor to load before building anyway. */
    public static int buildChunkWaitTicks = 300;
    /** Mobs spawned per tick while populating a floor. */
    public static int mobsPerTick = 4;
    public static int mobBase = 24;
    public static double mobPerFloor = 0.8;
    public static int mobMax = 90;
    public static int chestsPerFloor = 5;
    /** Per-floor mob scaling: health = 1 + hpPerFloor*floor, times 1 + hpPerLevel*playerLevel. */
    public static double hpPerFloor = 0.03;
    public static double dmgPerFloor = 0.015;
    public static double hpPerLevel = 0.02;
    /** A cleared floor refills with mobs and loot after this long once someone returns (20 minutes). */
    public static long repopulateTicks = 24000;
    /** Same for boss floors (1 hour). */
    public static long bossRepopulateTicks = 72000;
    /** Number of escape rifts (arrival points for gates) per floor. */
    public static int riftsPerFloor = 6;
    public static boolean restFloorHealing = true;

    // ------------------------------------------------------------- tower landmark in the overworld
    public static boolean towerEnabled = true;
    public static int towerRadius = 24;
    public static int towerHeight = 110;
    /** Offset from world spawn where the tower is built. */
    public static int towerOffsetX = 70;
    public static int towerOffsetZ = 0;
    /** Survival players cannot break or place blocks inside the tower footprint. */
    public static boolean towerProtect = true;

    // ------------------------------------------------------------- progression
    public static int bossXpBase = 400;
    public static int bossXpPerFloor = 150;
    public static int finalBossXp = 12000;
    public static int floorClearXpBase = 20;
    public static int floorClearXpPerFloor = 6;
    /** 0 = respawn at the tower entrance on the surface, 1 = respawn at the last rest floor reached. */
    public static int deathRespawnMode = 1;

    // ------------------------------------------------------------- testing
    /** When true, a headless self-test builds a few floors and the tower shortly after the server starts, and logs it. */
    public static boolean selfTest = false;
    /** With selfTest: also join a fake player that walks the gate, stairs, boss reward and death paths (Fabric only). */
    public static boolean selfTestBot = false;
}
