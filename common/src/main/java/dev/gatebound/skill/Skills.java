package dev.gatebound.skill;

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

/** The skill registry. Two skills per class: one at level 1 and one at level 5. */
public final class Skills {
    public static final List<Skill> ALL = new ArrayList<>();

    // Warrior
    public static final Skill SHOCKWAVE = add(new Skill("shockwave", GateboundClass.WARRIOR, 1, 12, 160, Skills::shockwave));
    public static final Skill WAR_CRY = add(new Skill("war_cry", GateboundClass.WARRIOR, 5, 20, 400, Skills::warCry));
    // Rogue
    public static final Skill SHADOW_STEP = add(new Skill("shadow_step", GateboundClass.ROGUE, 1, 8, 100, Skills::shadowStep));
    public static final Skill VANISH = add(new Skill("vanish", GateboundClass.ROGUE, 5, 18, 360, Skills::vanish));
    // Mage
    public static final Skill ARC_BOLT = add(new Skill("arc_bolt", GateboundClass.MAGE, 1, 10, 60, Skills::arcBolt));
    public static final Skill FROST_NOVA = add(new Skill("frost_nova", GateboundClass.MAGE, 5, 22, 240, Skills::frostNova));
    // Ranger
    public static final Skill EAGLE_EYE = add(new Skill("eagle_eye", GateboundClass.RANGER, 1, 8, 300, Skills::eagleEye));
    public static final Skill PIERCING_SHOT = add(new Skill("piercing_shot", GateboundClass.RANGER, 5, 16, 120, Skills::piercingShot));
    // Summoner
    public static final Skill SPIRIT_WARD = add(new Skill("spirit_ward", GateboundClass.SUMMONER, 1, 14, 240, Skills::spiritWard));
    public static final Skill SOUL_DRAIN = add(new Skill("soul_drain", GateboundClass.SUMMONER, 5, 16, 140, Skills::soulDrain));

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

    /** Skills the given class and level can use, in slot order (lowest level first). */
    public static List<Skill> available(String classId, int level) {
        GateboundClass cls = GateboundClass.byId(classId);
        List<Skill> out = new ArrayList<>();
        if (cls == null) {
            return out;
        }
        for (Skill s : ALL) {
            if (s.owner() == cls && level >= s.minLevel()) {
                out.add(s);
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

    private static boolean arcBolt(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 24, 1, ParticleTypes.ELECTRIC_SPARK);
        if (hits.isEmpty()) {
            return false;
        }
        hits.get(0).hurtServer(level, magic(player, level), (float) (5 + 0.8 * data.get(Stat.INTELLECT)));
        sound(player, level, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.0f);
        return true;
    }

    private static boolean frostNova(ServerPlayer player, ServerLevel level, PlayerData data) {
        float damage = (float) (3 + 0.4 * data.get(Stat.INTELLECT));
        for (LivingEntity e : SkillTargeting.enemiesAround(player, level, 5)) {
            e.hurtServer(level, magic(player, level), damage);
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2));
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, player.getX(), player.getY() + 0.8, player.getZ(), 60, 2.5, 0.5, 2.5, 0.05);
        sound(player, level, SoundEvents.GLASS_BREAK, 0.6f);
        return true;
    }

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
        if (hits.isEmpty()) {
            return false;
        }
        float damage = (float) (6 + 0.6 * data.get(Stat.AGILITY));
        for (LivingEntity e : hits) {
            e.hurtServer(level, magic(player, level), damage);
        }
        sound(player, level, SoundEvents.ARROW_SHOOT, 0.8f);
        return true;
    }

    private static boolean spiritWard(ServerPlayer player, ServerLevel level, PlayerData data) {
        player.heal((float) (6 + 0.5 * data.get(Stat.INTELLECT)));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1, player.getZ(), 20, 0.5, 0.8, 0.5, 0);
        sound(player, level, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f);
        return true;
    }

    private static boolean soulDrain(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(player, level, 16, 1, ParticleTypes.SOUL);
        if (hits.isEmpty()) {
            return false;
        }
        float damage = (float) (4 + 0.6 * data.get(Stat.INTELLECT));
        hits.get(0).hurtServer(level, magic(player, level), damage);
        player.heal(damage * 0.5f);
        sound(player, level, SoundEvents.SOUL_ESCAPE.value(), 1.0f);
        return true;
    }
}
