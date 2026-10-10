package dev.gatebound.skill;

/** Tunables for the Ranger's bow skills and Quarry system. Section "ranger" in config/gatebound.json. */
public final class RangerConfig {
    /** Static holder; not instantiable. */
    private RangerConfig() {
    }

    /** Ranger max mana = 20 + 4 * Agility instead of Intellect. Turn off to keep the old formula for every class. */
    public static boolean agilityMana = true;

    /** Extra damage the Ranger deals to their quarry (0.3 = +30%). */
    public static double quarryBonus = 0.3;
    /** Mana refunded when the quarry dies. */
    public static double quarryKillMana = 10;
    public static int quarryGlowSeconds = 30;

    /** Damage bonus at full range (0.5 = +50% at rangeFullBlocks or more, growing linearly from zero). */
    public static double rangeBonus = 0.5;
    public static double rangeFullBlocks = 24;
    /** A shot that lands from at least this far away plays the hit "ding" for the shooter (vanilla only does so for players). */
    public static double dingDistance = 12;
    /** Extra damage per Agility point on the Ranger's own bow shots (0.01 = +1%). */
    public static double agilityShotBonus = 0.01;

    /** Extra damage per Strength point on every arrow any player looses (0.03 = +3% per point). */
    public static double strengthBowBonus = 0.03;

    /** Skill damage =(base + perAgility * Agility) * (1 + perLevel * level). */
    public static double damagePerLevel = 0.012;
    public static double damagePerAgility = 0.5;

    // Skill arrows
    public static double arrowSpeed = 3.2;
    /** Ticks a skill arrow may exist before it is removed. */
    public static int arrowLifeTicks = 300;
    /** Ticks a skill arrow may lie in the ground before it is removed. */
    public static int arrowGroundTicks = 40;
    /** Share of the current heading replaced by the direction to the target each tick for seeking arrows. */
    public static double homing = 0.3;
    public static double homingRange = 24;
    /** Homing Shot: how far the lock-on cast reaches, and how sharply a locked arrow turns toward its target each tick. */
    public static double homingLockRange = 96;
    public static double homingLock = 0.4;

    // Bow handling
    /** Extra ticks of draw progress a Ranger gains every tick while drawing (1 = twice as fast). Server and client. 0 = vanilla. */
    public static int drawExtraTicks = 1;
    /** Seconds a cast bow skill waits for the Ranger's next shot. */
    public static int primedSeconds = 30;
    /** While drawing: nearby enemies are slowed hard, enemy missiles hang in the air, and the Ranger takes no damage. */
    public static boolean bulletTime = true;
    public static double bulletRadius = 24;
    public static int bulletSlowAmp = 5;

    // Rain of Arrows: arrows fall from a low ring all around the landing spot
    public static int rainWaves = 8;
    public static int rainPerWave = 4;
    public static double rainRingRadius = 8;
    public static double rainHeight = 5;
    public static double rainSpread = 5;

    // Hunter's Trance / Apex Predator
    public static int tranceBaseSeconds = 12;
    public static int apexBaseSeconds = 20;
    public static double apexRadius = 32;
    /** Mana Apex Predator drains every second it runs; it ends the moment the pool is empty. */
    public static double apexManaPerSecond = 6;
    public static double barrageRadius = 30;
    public static int barrageArrows = 20;
    /** Maximum health above which Headshot will not execute a target under 20% health. */
    public static double headshotBossHealth = 150;
}
