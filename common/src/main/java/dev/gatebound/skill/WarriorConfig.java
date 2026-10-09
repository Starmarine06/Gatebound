package dev.gatebound.skill;

/** Tunables for the Warrior's skills and Rage. Section "warrior" in config/gatebound.json. */
public final class WarriorConfig {
    private WarriorConfig() {
    }

    /** Warrior max mana = 20 + 4 * Strength instead of Intellect. Turn off to keep the old formula for every class. */
    public static boolean strengthMana = true;

    /** Skill damage = (base + perStrength * Strength) * (1 + perLevel * level). */
    public static double damagePerLevel = 0.012;
    public static double damagePerStrength = 0.5;

    // Rage (Berserker Rage and Avatar of War)
    /** Fraction of damage dealt that heals the Warrior during Berserker Rage. */
    public static double rageLifesteal = 0.15;
    public static double avatarLifesteal = 0.25;
    public static int rageBaseSeconds = 15;
    public static int avatarBaseSeconds = 20;
    public static double avatarPulseRadius = 6;

    // Bulwark
    /** Share of melee damage reflected back at the attacker while Bulwark is up. */
    public static double bulwarkReflect = 0.3;
    public static int bulwarkSeconds = 6;

    // Charge / Leap
    public static double chargeSpeed = 1.6;
    public static int chargeTicks = 8;
    public static double leapForward = 1.3;
    public static double leapUp = 0.75;

    public static double earthbreakerRadius = 8;
}
