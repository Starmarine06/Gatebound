package dev.gatebound.skill;

/** Tunable magic numbers. Mutable at runtime through /gatebound magic so odds can be tested live. */
public final class MagicConfig {
    /** Chance that a Mage also awakens to a second element when they pick their first. */
    public static volatile double secondElementChance = 0.35;
    /** Chance, given a second element, of a third. */
    public static volatile double thirdElementChance = 0.12;

    /** Static holder; not instantiable. */
    private MagicConfig() {
    }
}
