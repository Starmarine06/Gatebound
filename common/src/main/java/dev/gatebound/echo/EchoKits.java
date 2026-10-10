package dev.gatebound.echo;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.HashMap;
import java.util.Map;

/**
 * How each kind of bound mob fights. Anything not listed is a plain melee fighter. Ranged kits shoot an instant bolt
 * (a particle trail that hits on the spot) so they work for every mob without needing projectile entities.
 */
final class EchoKits {
    enum Special {NONE, HEALER, EXPLODER, TELEPORTER}

    /**
     * @param range       0 for melee, otherwise the distance it shoots from
     * @param cooldown    ticks between attacks
     * @param damageMult  multiplier on the echo's attack damage
     * @param trail       particle drawn along a ranged bolt (null for melee)
     * @param effect      status effect put on the target, may be null
     * @param fireSeconds seconds the target is set alight
     */
    record Kit(double range, int cooldown, double damageMult, ParticleOptions trail, Holder<MobEffect> effect,
               int effectTicks, int amplifier, int fireSeconds, Special special, boolean knockback) {
        boolean ranged() {
            return range > 0;
        }
    }

    private static final Kit MELEE = new Kit(0, 20, 1.0, null, null, 0, 0, 0, Special.NONE, false);
    private static final Map<String, Kit> KITS = new HashMap<>();

    static {
        // Archers and casters
        ranged("skeleton", 14, 30, 1.0, ParticleTypes.CRIT, null, 0, 0);
        ranged("stray", 14, 35, 1.0, ParticleTypes.CRIT, MobEffects.SLOWNESS, 80, 0);
        ranged("bogged", 14, 35, 1.0, ParticleTypes.CRIT, MobEffects.POISON, 80, 0);
        ranged("pillager", 14, 40, 1.2, ParticleTypes.CRIT, null, 0, 0);
        ranged("drowned", 9, 40, 1.0, ParticleTypes.CRIT, null, 0, 0);
        ranged("blaze", 14, 30, 0.9, ParticleTypes.FLAME, null, 0, 4);
        ranged("ghast", 22, 60, 2.0, ParticleTypes.FLAME, null, 0, 5);
        ranged("evoker", 12, 50, 1.3, ParticleTypes.WITCH, null, 0, 0);
        ranged("snow_golem", 11, 25, 0.5, ParticleTypes.SNOWFLAKE, MobEffects.SLOWNESS, 60, 0);
        ranged("warden", 16, 50, 3.0, ParticleTypes.SONIC_BOOM, null, 0, 0);
        ranged("guardian", 12, 40, 1.0, ParticleTypes.CRIT, null, 0, 0);
        ranged("elder_guardian", 14, 40, 1.4, ParticleTypes.CRIT, MobEffects.SLOWNESS, 60, 0);
        ranged("shulker", 14, 50, 0.8, ParticleTypes.WITCH, MobEffects.LEVITATION, 40, 0);
        ranged("llama", 8, 40, 0.5, ParticleTypes.CRIT, null, 0, 0);
        ranged("trader_llama", 8, 40, 0.5, ParticleTypes.CRIT, null, 0, 0);
        // The witch also heals: see Special.HEALER
        KITS.put("witch", new Kit(11, 40, 0.8, ParticleTypes.WITCH, MobEffects.POISON, 100, 0, 0, Special.HEALER, false));
        KITS.put("allay", new Kit(6, 50, 0.4, ParticleTypes.HEART, null, 0, 0, 0, Special.HEALER, false));
        // Melee with a twist
        melee("wither_skeleton", MobEffects.WITHER, 80, false);
        melee("husk", MobEffects.HUNGER, 100, false);
        melee("cave_spider", MobEffects.POISON, 80, false);
        melee("bee", MobEffects.POISON, 80, false);
        melee("spider", MobEffects.SLOWNESS, 40, false);
        melee("zombified_piglin", null, 0, false);
        melee("vindicator", null, 0, true);
        melee("magma_cube", null, 0, true);
        melee("slime", MobEffects.SLOWNESS, 30, true);
        melee("silverfish", MobEffects.POISON, 40, false);
        melee("phantom", MobEffects.WEAKNESS, 60, false);
        melee("iron_golem", null, 0, true);
        melee("ravager", null, 0, true);
        melee("hoglin", null, 0, true);
        melee("zoglin", null, 0, true);
        KITS.put("creeper", new Kit(0, 20, 1.0, null, null, 0, 0, 0, Special.EXPLODER, false));
        KITS.put("enderman", new Kit(0, 20, 1.2, null, null, 0, 0, 0, Special.TELEPORTER, false));
    }

    /** Static holder; not instantiable. */
    private EchoKits() {
    }

    /** Registers a ranged kit for a mob type. */
    private static void ranged(String id, double range, int cooldown, double mult, ParticleOptions trail,
                               Holder<MobEffect> effect, int effectTicks, int fire) {
        KITS.put(id, new Kit(range, cooldown, mult, trail, effect, effectTicks, 0, fire, Special.NONE, false));
    }

    /** Registers a melee kit for a mob type. */
    private static void melee(String id, Holder<MobEffect> effect, int effectTicks, boolean knockback) {
        KITS.put(id, new Kit(0, 20, 1.0, null, effect, effectTicks, 0, 0, Special.NONE, knockback));
    }

    /** The kit for a mob type id (melee by default). */
    static Kit of(String typeId) {
        String path = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return KITS.getOrDefault(path, MELEE);
    }
}
