package dev.gatebound.combat;

/** Tunable combat numbers. Mutable at runtime through /gatebound combat so timing can be tested live. */
public final class CombatConfig {
    /** How many ticks after pressing the key a hit still counts as a parry (20 ticks = 1 second). */
    public static volatile int parryWindowTicks = 6;
    /** Ticks after a press before another parry can be started. Stops button mashing. */
    public static volatile int parryCooldownTicks = 14;
    /** Posture a mob needs to fill before it is staggered. */
    public static volatile double postureMax = 100;
    /** Posture added to the attacker by a successful parry. */
    public static volatile double parryPosture = 40;
    /** Posture added to a mob by a normal player hit. */
    public static volatile double hitPosture = 8;
    /** Ticks without being hit before posture starts to recover. */
    public static volatile int postureRecoveryDelay = 40;
    /** Posture recovered per tick once recovery starts. */
    public static volatile double postureRecoveryPerTick = 0.3;
    /** How long a staggered mob stays open to a finisher, in ticks. */
    public static volatile int staggerTicks = 60;
    /** Finisher damage as a fraction of the mob's max health. */
    public static volatile double finisherFraction = 0.35;
    /** Ticks of Strength given to the player after a successful parry (the counter window). */
    public static volatile int counterTicks = 40;

    /** Mana restored by a successful parry. */
    public static volatile double parryMana = 6;

    // ---- Combat 4b: dodge, stamina, telegraphed attacks, player posture ----
    /** Stamina spent by a dodge. */
    public static volatile double dodgeCost = 20;
    /** Ticks after a dodge before another can start. */
    public static volatile int dodgeCooldownTicks = 20;
    /** Ticks of invulnerability to melee and projectiles after a dodge starts. */
    public static volatile int iframeTicks = 8;
    /** Horizontal dash speed of a dodge (blocks per tick). */
    public static volatile double dodgeSpeed = 1.1;
    /** Stamina refunded by a perfect dodge. */
    public static volatile double perfectDodgeRefund = 15;
    /** Ticks shaved off every skill cooldown by a perfect dodge. */
    public static volatile int perfectDodgeCooldownCut = 30;
    /** Stamina regenerated per tick, after the regen delay. */
    public static volatile double staminaRegenPerTick = 0.6;
    /** Ticks after spending stamina before it regenerates. */
    public static volatile int staminaRegenDelay = 20;

    /** Master switch for the mob wind-up system. */
    public static volatile boolean telegraphEnabled = true;
    /** Ticks a normal mob attack winds up before landing. */
    public static volatile int windupTicks = 10;
    /** Ticks a heavy mob attack winds up before landing. */
    public static volatile int heavyWindupTicks = 16;
    /** Chance (0 to 1) a wind-up is a heavy, unparryable attack. */
    public static volatile double heavyChance = 0.2;
    /** Base ticks a mob rests after swinging before it can wind up again. */
    public static volatile int attackRecoverTicks = 18;

    /** Player posture needed to be staggered. */
    public static volatile double playerPostureMax = 100;
    /** Player posture from a hit that came just after a parry press (bad timing). */
    public static volatile double lateParryPosture = 20;
    /** Player posture from trying to parry a heavy attack. */
    public static volatile double heavyParryPosture = 35;
    /** Ticks without posture damage before the player's posture recovers. */
    public static volatile int playerPostureRecoveryDelay = 60;
    /** Player posture recovered per tick. */
    public static volatile double playerPostureRecoveryPerTick = 0.4;
    /** How long a staggered player cannot parry or dodge. */
    public static volatile int playerStaggerTicks = 20;

    /** Static holder; not instantiable. */
    private CombatConfig() {
    }
}
