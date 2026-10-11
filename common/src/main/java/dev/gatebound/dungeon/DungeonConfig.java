package dev.gatebound.dungeon;

/** Tunable values for gates and the Spire. Plain statics, like CombatConfig / MagicConfig (saved by GateboundConfig). */
public final class DungeonConfig {
    /** Static holder; not instantiable. */
    private DungeonConfig() {
    }

    // ------------------------------------------------------------- natural gates
    /** Master switch for natural overworld gate spawning. */
    public static boolean naturalSpawn = true;
    /** Ticks between spawn rolls (every overworld player is rolled each time). */
    public static int spawnCheckTicks = 400;
    /** Chance per roll that a gate appears near each rolled player. */
    public static double spawnChance = 0.1;
    /** Maximum natural gates that may exist at once in the world. */
    public static int maxNaturalGates = 16;
    /** An open gate leaks monsters until its guardian is killed from inside. */
    public static boolean gateLeaks = true;
    /** Ticks between leaks at first; it shortens as the gate ages, down to leakMinInterval. */
    public static int leakInterval = 400;
    public static int leakMinInterval = 120;
    /** Most leaked monsters alive near one gate; rises with its rank. */
    public static int leakCap = 8;
    /** Chance that a natural gate is placed in the nearest village (within villageSearchChunks) instead of at a random spot. */
    public static double villageGateChance = 0.75;
    public static int villageSearchChunks = 8;
    /** A player will not get a new gate while another natural gate is within this many blocks. */
    public static int perPlayerRadius = 96;
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
    /** After a gate guardian falls the dungeon collapses; anyone still inside when it does dies. */
    public static boolean gateCollapse = true;
    /** Multiplier on each rank's collapse time (E 180 s down to S 100 s). */
    public static double gateCollapseScale = 1.0;
    /** Players caught in a collapse lose everything they carry (otherwise they die normally and leave a grave). */
    public static boolean gateCollapseLoseItems = true;
    /** Monsters that leaked out of a gate vanish when the gate closes. */
    public static boolean leakDespawnOnClose = true;
    /** Ticks of damage immunity after arriving in a gate dungeon (the countdown shows on the action bar). */
    public static int gateSpawnProtectTicks = 100;
    /** Ticks between gate/exit re-trigger checks for the same player. */
    public static int portalCooldownTicks = 60;

    // ------------------------------------------------------------- gate dungeon difficulty
    /** Regular monsters in a gate dungeon by rank E..S (each gate rolls 75-125 percent of this). An E gate matches the old A gate. */
    public static int[] gateMobCounts = {45, 70, 100, 140, 190, 250};
    /** Level a player should have to take each rank alone (A is meant for level 90+). */
    public static int[] gateRecommendedLevels = {1, 15, 35, 60, 90, 100};
    /** Extra monster health / damage per level the strongest player is under the gate's recommended level. */
    public static double gateUnderlevelHealth = 0.05;
    public static double gateUnderlevelDamage = 0.025;

    // ------------------------------------------------------------- mana crystals
    /** Crystals in a gate dungeon: base + perRank * rank index (E 2 ... S 12). */
    public static int gateCrystalBase = 2;
    public static int gateCrystalPerRank = 2;
    /** Crystals on an ordinary Spire floor: base + perBand * band. */
    public static int floorCrystalBase = 1;
    public static int floorCrystalPerBand = 1;
    /** Break hardness of a crystal while its guardian lives (obsidian is 50; a diamond pickaxe needs about 28 seconds at 150). */
    public static double crystalHardness = 150.0;
    /** Break hardness of a crystal once its guardian has fallen (about 17 seconds with a diamond pickaxe at 90). */
    public static double crystalWeakHardness = 90.0;
    /** Mining speed is capped here so Efficiency and Haste cannot make crystals trivial (a diamond pickaxe is 8). */
    public static double crystalMaxMineSpeed = 12.0;
    /** Mana crystals dropped per broken crystal (min, max). */
    public static int crystalDropMin = 1;
    public static int crystalDropMax = 2;

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
    /** Boss floors reset this long after the guardian falls (15 minutes), plus up to the jitter below (5 more). */
    public static long bossRepopulateTicks = 18000;
    public static long bossRepopulateJitter = 6000;
    /** An unfinished gate dungeon copy that nobody has been in for this long (10 minutes) is released. */
    public static long gateInstanceIdleTicks = 12000;
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
    /** Also stop creative players from editing the tower and the Spire (set false to build freely while testing). */
    public static boolean protectFromCreative = true;

    // ------------------------------------------------------------- progression
    public static int bossXpBase = 400;
    public static int bossXpPerFloor = 150;
    public static int finalBossXp = 12000;
    public static int floorClearXpBase = 20;
    public static int floorClearXpPerFloor = 6;
    /** XP per slain floor monster (base + per floor). */
    public static int mobKillXpBase = 3;
    public static int mobKillXpPerFloor = 1;
    /** Trail markers last this long (5 minutes), and a player can have this many out at once. */
    public static long markerTicks = 6000;
    public static int markerMax = 16;
    /** Floors up to this have custom bosses and vanilla regular mobs; above it every mob is custom. */
    public static int customMobFloor = 50;
    /** Chance a slain monster drops a material of its rank: custom monsters versus the vanilla ones on lower floors. */
    public static double customMaterialChance = 0.6;
    public static double vanillaMaterialChance = 0.15;
    /** A grave can be opened only by its owner for this long (5 minutes). */
    public static long graveOwnerTicks = 6000;
    /** 0 = respawn at the tower entrance on the surface, 1 = respawn at the last rest floor reached. */
    public static int deathRespawnMode = 1;

    // ------------------------------------------------------------- testing
    /** When true, a headless self-test builds a few floors and the tower shortly after the server starts, and logs it. */
    public static boolean selfTest = false;
    /** With selfTest: also join a fake player that walks the gate, stairs, boss reward and death paths (Fabric only). */
    public static boolean selfTestBot = false;
}
