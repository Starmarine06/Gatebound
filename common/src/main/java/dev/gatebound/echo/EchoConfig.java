package dev.gatebound.echo;

/** Tunables for the Echo system. Plain statics so they are easy to tweak or hook to a config later. */
public final class EchoConfig {
    private EchoConfig() {
    }

    /** Op override for the bind chance in percent (0-100). Negative means use the formula. */
    public static volatile double chanceOverridePercent = -1;
    /** Formula: base + perIntellect * Intellect + perLevel * level, in percent, capped at maxChance. */
    public static double baseChance = 5;
    public static double chancePerIntellect = 0.5;
    public static double chancePerLevel = 0.1;
    public static double maxChance = 60;

    /** Slots = baseSlots + Intellect / intellectPerSlot. */
    public static int baseSlots = 2;
    public static int intellectPerSlot = 10;
    public static int hardSlotCap = 12;

    /** Mana spent per echo summoned. Never refunded on dismiss. */
    public static int summonMana = 8;

    /** Health multiplier = 1 + level*perLevel + Intellect*perIntellect; damage uses half of that bonus. */
    public static double healthPerLevel = 0.03;
    public static double healthPerIntellect = 0.04;

    /** Fraction of normal kill XP the owner receives for echo kills. */
    public static double echoXpFraction = 0.35;

    /** Distance at which a following echo is teleported to its owner. */
    public static double teleportDistance = 24;

    public static double chancePercent(int intellect, int level) {
        if (chanceOverridePercent >= 0) {
            return Math.min(100, chanceOverridePercent);
        }
        return Math.min(maxChance, baseChance + chancePerIntellect * intellect + chancePerLevel * level);
    }

    public static int slots(int intellect) {
        return Math.min(hardSlotCap, baseSlots + Math.max(0, intellect) / Math.max(1, intellectPerSlot));
    }
}
