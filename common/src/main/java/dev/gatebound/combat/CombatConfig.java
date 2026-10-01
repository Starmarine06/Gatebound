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

    private CombatConfig() {
    }
}
