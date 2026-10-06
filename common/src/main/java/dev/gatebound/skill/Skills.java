package dev.gatebound.skill;

import dev.gatebound.data.Element;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.Stat;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The skill registry. Non-mage classes have two skills each (levels 1 and 5).
 * Mages get two skills per element they hold, so a Mage with several elements can have up to six or more.
 */
public final class Skills {
    public static final List<Skill> ALL = new ArrayList<>();

    // Warrior
    public static final Skill SHOCKWAVE = add(new Skill("shockwave", GateboundClass.WARRIOR, null, 1, 12, 160, Skills::shockwave));
    public static final Skill WAR_CRY = add(new Skill("war_cry", GateboundClass.WARRIOR, null, 5, 20, 400, Skills::warCry));
    // Rogue
    public static final Skill SHADOW_STEP = add(new Skill("shadow_step", GateboundClass.ROGUE, null, 1, 8, 100, Skills::shadowStep));
    public static final Skill VANISH = add(new Skill("vanish", GateboundClass.ROGUE, null, 5, 18, 360, Skills::vanish));
    // Ranger
    public static final Skill EAGLE_EYE = add(new Skill("eagle_eye", GateboundClass.RANGER, null, 1, 8, 300, Skills::eagleEye));
    public static final Skill PIERCING_SHOT = add(new Skill("piercing_shot", GateboundClass.RANGER, null, 5, 16, 120, Skills::piercingShot));
    // Summoner
    public static final Skill SPIRIT_WARD = add(new Skill("spirit_ward", GateboundClass.SUMMONER, null, 1, 14, 240, Skills::spiritWard));
    public static final Skill SCOUTS_EYE = add(new Skill("scouts_eye", GateboundClass.SUMMONER, null, 3, 12, 0, dev.gatebound.echo.EchoView::cast));
    public static final Skill SOUL_DRAIN = add(new Skill("soul_drain", GateboundClass.SUMMONER, null, 5, 16, 140, Skills::soulDrain));
    public static final Skill RALLY = add(new Skill("rally", GateboundClass.SUMMONER, null, 7, dev.gatebound.echo.EchoSkills.RALLY_COST, 100, dev.gatebound.echo.EchoSkills::rally));
    public static final Skill COMMAND_STRIKE = add(new Skill("command_strike", GateboundClass.SUMMONER, null, 10, dev.gatebound.echo.EchoSkills.COMMAND_COST, 120, dev.gatebound.echo.EchoSkills::commandStrike));
    public static final Skill EMPOWER = add(new Skill("empower", GateboundClass.SUMMONER, null, 13, dev.gatebound.echo.EchoSkills.EMPOWER_COST, 400, dev.gatebound.echo.EchoSkills::empower));
    public static final Skill SOUL_LINK = add(new Skill("soul_link", GateboundClass.SUMMONER, null, 16, dev.gatebound.echo.EchoSkills.LINK_COST, 0, dev.gatebound.echo.EchoSkills::soulLink));
    public static final Skill SACRIFICE = add(new Skill("sacrifice", GateboundClass.SUMMONER, null, 20, dev.gatebound.echo.EchoSkills.SACRIFICE_COST, 0, dev.gatebound.echo.EchoSkills::sacrifice));
    public static final Skill SOUL_SWAP = add(new Skill("soul_swap", GateboundClass.SUMMONER, null, 28, dev.gatebound.echo.EchoSkills.SWAP_COST, 0, dev.gatebound.echo.EchoSkills::soulSwap));
    public static final Skill BIND_CHAINS = add(new Skill("bind_chains", GateboundClass.SUMMONER, null, 35, dev.gatebound.echo.EchoSkills.CHAINS_COST, 0, dev.gatebound.echo.EchoSkills::bindChains));
    public static final Skill LEGION_CALL = add(new Skill("legion_call", GateboundClass.SUMMONER, null, 45, dev.gatebound.echo.EchoSkills.LEGION_COST, 0, dev.gatebound.echo.EchoSkills::legionCall));
    public static final Skill SOUL_STORM = add(new Skill("soul_storm", GateboundClass.SUMMONER, null, 60, dev.gatebound.echo.EchoSkills.STORM_COST, 0, dev.gatebound.echo.EchoSkills::soulStorm));
    public static final Skill ASCENSION = add(new Skill("ascension", GateboundClass.SUMMONER, null, 80, dev.gatebound.echo.EchoSkills.ASCENSION_COST, 0, dev.gatebound.echo.EchoSkills::ascension));
    public static final Skill SOVEREIGNS_DOMAIN = add(new Skill("sovereigns_domain", GateboundClass.SUMMONER, null, 100, dev.gatebound.echo.EchoSkills.DOMAIN_COST, 0, dev.gatebound.echo.EchoSkills::sovereignsDomain));

    // Mage: Fire
    public static final Skill FIREBALL = add(new Skill("fireball", GateboundClass.MAGE, Element.FIRE, 1, 10, 60, Skills::fireball));
    public static final Skill FLAME_BURST = add(new Skill("flame_burst", GateboundClass.MAGE, Element.FIRE, 5, 22, 220, Skills::flameBurst));
    // Mage: Ice
    public static final Skill FROST_SHARD = add(new Skill("frost_shard", GateboundClass.MAGE, Element.ICE, 1, 9, 50, Skills::frostShard));
    public static final Skill FROST_NOVA = add(new Skill("frost_nova", GateboundClass.MAGE, Element.ICE, 5, 22, 240, Skills::frostNova));
    // Mage: Lightning
    public static final Skill ARC_BOLT = add(new Skill("arc_bolt", GateboundClass.MAGE, Element.LIGHTNING, 1, 10, 60, Skills::arcBolt));
    public static final Skill THUNDER_STORM = add(new Skill("thunder_storm", GateboundClass.MAGE, Element.LIGHTNING, 5, 24, 260, Skills::thunderStorm));
    // Mage: Wind
    public static final Skill GUST = add(new Skill("gust", GateboundClass.MAGE, Element.WIND, 1, 8, 70, Skills::gust));
    public static final Skill CYCLONE = add(new Skill("cyclone", GateboundClass.MAGE, Element.WIND, 5, 20, 220, Skills::cyclone));
    // Mage: Earth
    public static final Skill STONE_SPIKE = add(new Skill("stone_spike", GateboundClass.MAGE, Element.EARTH, 1, 10, 70, Skills::stoneSpike));
    public static final Skill STONE_SKIN = add(new Skill("stone_skin", GateboundClass.MAGE, Element.EARTH, 5, 18, 360, Skills::stoneSkin));
    // Mage: Light
    public static final Skill RADIANT_BOLT = add(new Skill("radiant_bolt", GateboundClass.MAGE, Element.LIGHT, 1, 10, 60, Skills::radiantBolt));
    public static final Skill SANCTUARY = add(new Skill("sanctuary", GateboundClass.MAGE, Element.LIGHT, 5, 20, 300, Skills::sanctuary));

    private Skills() {
    }

    private static Skill add(Skill s) {
        ALL.add(s);
        return s;
    }

    public static Skill byId(String id) {
        for (Skill s : ALL) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Skills the given class, elements and level can use, in slot order.
     * Mages: grouped by element in the order the elements were gained, lowest level first within each.
     */
    public static List<Skill> available(String classId, List<String> elements, int level) {
        GateboundClass cls = GateboundClass.byId(classId);
        List<Skill> out = new ArrayList<>();
        if (cls == null) {
            return out;
        }
        if (cls != GateboundClass.MAGE) {
            for (Skill s : ALL) {
                if (s.owner() == cls && level >= s.minLevel()) {
                    out.add(s);
                }
            }
            return out;
        }
        for (String elementId : elements) {
            Element element = Element.byId(elementId);
            for (Skill s : ALL) {
                if (s.owner() == GateboundClass.MAGE && s.element() == element && level >= s.minLevel()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ---- Effects ---------------------------------------------------------------------------------------------

    private static DamageSource magic(ServerPlayer player, ServerLevel level) {
        return level.damageSources().indirectMagic(player, player);
    }

    private static void sound(ServerPlayer player, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    private static float intel(PlayerData data, double base, double scale) {
        return (float) (base + scale * data.get(Stat.INTELLECT));
    }

    // Warrior

    private static boolean shockwave(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = (float) (4 + 0.5 * data.get(Stat.STRENGTH));
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 4)) {
            e.hurtServer(level, magic(player, level), damage);
            Vec3 away = e.position().subtract(player.position());
            Vec3 dir = new Vec3(away.x, 0, away.z).normalize();
            e.push(dir.x * 0.9, 0.35, dir.z * 0.9);
            e.needsSync = true;
        }
        level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 0.2, player.getZ(), 4, 1.5, 0.1, 1.5, 0);
        level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 0.3, player.getZ(), 30, 2, 0.2, 2, 0.3);
        sound(player, level, SoundEvents.GENERIC_EXPLODE.value(), 1.2f);
        return true;
    }

    private static boolean warCry(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 0));
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 0));
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 1.8, player.getZ(), 8, 0.5, 0.3, 0.5, 0);
        sound(player, level, SoundEvents.RAVAGER_ROAR, 1.0f);
        return true;
    }

    // Rogue

    private static boolean shadowStep(ServerPlayer player, ServerLevel level, PlayerData data) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        player.setDeltaMovement(flat.x * 1.9, 0.2, flat.z * 1.9);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 0.5, player.getZ(), 15, 0.3, 0.4, 0.3, 0.02);
        sound(player, level, SoundEvents.ENDERMAN_TELEPORT, 1.4f);
        return true;
    }

    private static boolean vanish(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0));
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 25, 0.4, 0.6, 0.4, 0.02);
        sound(player, level, SoundEvents.ENDERMAN_TELEPORT, 0.8f);
        return true;
    }

    // Ranger

    private static boolean eagleEye(ServerPlayer player, ServerLevel level, PlayerData data) {
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 24)) {
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 160, 0));
        }
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.8, player.getZ(), 10, 0.3, 0.2, 0.3, 0.02);
        sound(player, level, SoundEvents.ARROW_HIT_PLAYER, 0.7f);
        return true;
    }

    private static boolean piercingShot(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 40, 3, ParticleTypes.CRIT);
        float damage = (float) (6 + 0.6 * data.get(Stat.AGILITY));
        for (LivingEntity e : hits) {
            e.hurtServer(level, magic(player, level), damage);
        }
        sound(player, level, SoundEvents.ARROW_SHOOT, 0.8f);
        return !hits.isEmpty();
    }

    // Summoner

    private static boolean spiritWard(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.heal(intel(data, 6, 0.5));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1, player.getZ(), 20, 0.5, 0.8, 0.5, 0);
        sound(player, level, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f);
        return true;
    }

    private static boolean soulDrain(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 16, 1, ParticleTypes.SOUL);
        sound(player, level, SoundEvents.SOUL_ESCAPE.value(), 1.0f);
        if (hits.isEmpty()) {
            return false;
        }
        float damage = intel(data, 4, 0.6);
        hits.get(0).hurtServer(level, magic(player, level), damage);
        player.heal(damage * 0.5f);
        return true;
    }

    // Mage: Fire

    private static boolean fireball(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 24, 1, ParticleTypes.FLAME);
        sound(player, level, SoundEvents.BLAZE_SHOOT, 1.0f);
        if (hits.isEmpty()) {
            return false;
        }
        LivingEntity target = hits.get(0);
        target.hurtServer(level, magic(player, level), intel(data, 5, 0.7));
        target.setRemainingFireTicks(80);
        level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY(0.5), target.getZ(), 1, 0, 0, 0, 0);
        return true;
    }

    private static boolean flameBurst(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = intel(data, 3, 0.4);
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 5)) {
            e.hurtServer(level, magic(player, level), damage);
            e.setRemainingFireTicks(100);
        }
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.8, player.getZ(), 70, 2.5, 0.5, 2.5, 0.08);
        sound(player, level, SoundEvents.FIRECHARGE_USE, 0.8f);
        return true;
    }

    // Mage: Ice

    private static boolean frostShard(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 24, 1, ParticleTypes.SNOWFLAKE);
        sound(player, level, SoundEvents.GLASS_BREAK, 1.6f);
        if (hits.isEmpty()) {
            return false;
        }
        LivingEntity target = hits.get(0);
        target.hurtServer(level, magic(player, level), intel(data, 4, 0.6));
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
        return true;
    }

    private static boolean frostNova(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = intel(data, 3, 0.4);
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 5)) {
            e.hurtServer(level, magic(player, level), damage);
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2));
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, player.getX(), player.getY() + 0.8, player.getZ(), 60, 2.5, 0.5, 2.5, 0.05);
        sound(player, level, SoundEvents.GLASS_BREAK, 0.6f);
        return true;
    }

    // Mage: Lightning

    private static boolean arcBolt(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 24, 1, ParticleTypes.ELECTRIC_SPARK);
        sound(player, level, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.0f);
        if (hits.isEmpty()) {
            return false;
        }
        hits.get(0).hurtServer(level, magic(player, level), intel(data, 5, 0.8));
        return true;
    }

    private static boolean thunderStorm(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = intel(data, 4, 0.5);
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 6)) {
            e.hurtServer(level, magic(player, level), damage);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY(0.5), e.getZ(), 25, 0.3, 0.8, 0.3, 0.3);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1, player.getZ(), 60, 3, 1, 3, 0.2);
        sound(player, level, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.4f);
        return true;
    }

    // Mage: Wind

    private static boolean gust(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 10, 3, ParticleTypes.CLOUD);
        sound(player, level, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.0f);
        Vec3 dir = player.getLookAngle();
        for (LivingEntity e : hits) {
            e.hurtServer(level, magic(player, level), intel(data, 2, 0.3));
            e.push(dir.x * 1.6, 0.4, dir.z * 1.6);
            e.needsSync = true;
        }
        return !hits.isEmpty();
    }

    private static boolean cyclone(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = intel(data, 3, 0.4);
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 5)) {
            e.hurtServer(level, magic(player, level), damage);
            e.push(0, 1.1, 0);
            e.needsSync = true;
        }
        level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(), 80, 2.5, 1.2, 2.5, 0.1);
        sound(player, level, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 0.7f);
        return true;
    }

    // Mage: Earth

    private static boolean stoneSpike(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 16, 1, ParticleTypes.CAMPFIRE_COSY_SMOKE);
        sound(player, level, SoundEvents.STONE_BREAK, 0.7f);
        if (hits.isEmpty()) {
            return false;
        }
        LivingEntity target = hits.get(0);
        target.hurtServer(level, magic(player, level), intel(data, 5, 0.5));
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 3));
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.2), target.getZ(), 15, 0.3, 0.3, 0.3, 0.2);
        return true;
    }

    private static boolean stoneSkin(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 1));
        level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1, player.getZ(), 20, 0.5, 0.8, 0.5, 0.1);
        sound(player, level, SoundEvents.STONE_PLACE, 0.8f);
        return true;
    }

    // Mage: Light

    private static boolean radiantBolt(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 20, 1, ParticleTypes.END_ROD);
        sound(player, level, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f);
        if (hits.isEmpty()) {
            return false;
        }
        hits.get(0).hurtServer(level, magic(player, level), intel(data, 4, 0.6));
        player.heal(2.0f);
        return true;
    }

    private static boolean sanctuary(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.heal(intel(data, 8, 0.6));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 30, 0.6, 0.9, 0.6, 0.03);
        sound(player, level, SoundEvents.BEACON_ACTIVATE, 1.4f);
        return true;
    }
}
