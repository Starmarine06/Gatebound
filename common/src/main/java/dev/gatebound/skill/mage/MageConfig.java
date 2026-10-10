package dev.gatebound.skill.mage;

/** Tunables for the Mage ladders. */
public final class MageConfig {
    /** Static holder; not instantiable. */
    private MageConfig() {
    }

    /** Skill damage = (base + perIntellect * Intellect) * (1 + perLevel * level). */
    public static double damagePerLevel = 0.012;
    public static double damagePerIntellect = 0.5;

    /** Fire: extra damage fire skills deal to a burning enemy (0.25 = +25%). */
    public static double scorchBonus = 0.25;
    /** Fire: how long a mage-set fire stays tracked, so a death inside it still explodes. */
    public static int scorchTicks = 160;

    /** Ice: Chill stacks needed to freeze, and how long a freeze lasts. */
    public static int chillToFreeze = 3;
    public static int freezeTicks = 60;
    /** How long Chill stacks last before fading. */
    public static int chillTicks = 100;

    /** Minimum ticks between a Mage's casts (20 = one spell per second). */
    public static int castGapTicks = 20;
    /** Share of max stamina each cast costs. */
    public static double strainCost = 0.15;
    /** Below this share of stamina a Mage is slowed; below the second, slowed hard. */
    public static double slowBelow = 0.4;
    public static double heavySlowBelow = 0.15;
    /** Casting with no stamina left hurts: this share of max health (at least 2), never lethal. */
    public static double backlashFraction = 0.15;

    /** Seconds the 100-level forms last. */
    public static int formSeconds = 20;
    /** Seconds the short buffs last. */
    public static int buffSeconds = 30;
    /** Seconds a Glacial Path stays frozen. */
    public static int pathSeconds = 8;
}
