package dev.gatebound.skill;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import dev.gatebound.echo.EchoManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Warrior skills and the Rage system. Rage (Berserker Rage, Avatar of War) heals the Warrior from the damage they deal,
 * and Bulwark reflects part of the melee damage taken. All state is in memory only and cleared on quit, death and server stop.
 */
public final class WarriorSkills {
    public static final int SHOCKWAVE_COST = 12;
    public static final int CHARGE_COST = 10;
    public static final int WAR_CRY_COST = 20;
    public static final int CLEAVE_COST = 14;
    public static final int IRON_SKIN_COST = 16;
    public static final int LEAP_COST = 18;
    public static final int RENDING_COST = 16;
    public static final int BULWARK_COST = 18;
    public static final int WHIRLWIND_COST = 24;
    public static final int SECOND_WIND_COST = 30;
    public static final int CRUSHING_COST = 35;
    public static final int RAGE_COST = 45;
    public static final int EARTHBREAKER_COST = 60;
    public static final int AVATAR_COST = 80;

    private record Task(long at, Consumer<MinecraftServer> run) {
    }

    private static final class Charge {
        final long until;
        final Set<UUID> hit = new HashSet<>();

        Charge(long until) {
            this.until = until;
        }
    }

    private record Leap(long start, boolean earth) {
    }

    private static final class Avatar {
        final long until;
        long nextPulse;

        Avatar(long until, long nextPulse) {
            this.until = until;
            this.nextPulse = nextPulse;
        }
    }

    private static final Map<UUID, Charge> CHARGE = new HashMap<>();
    private static final Map<UUID, Leap> LEAP = new HashMap<>();
    private static final Map<UUID, Long> RAGE = new HashMap<>();
    private static final Map<UUID, Avatar> AVATAR = new HashMap<>();
    private static final Map<UUID, Long> BULWARK = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();

    /** Guards our own damage (reflect) from re-entering the hurt event. */
    private static boolean applying;

    private WarriorSkills() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static void init() {
        TickEvent.SERVER_POST.register(WarriorSkills::tick);
        EntityEvent.LIVING_HURT.register(WarriorSkills::onHurt);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                clearPlayer(player.getUUID());
            }
            return EventResult.pass();
        });
        PlayerEvent.PLAYER_QUIT.register(player -> clearPlayer(player.getUUID()));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> clearPlayer(player.getUUID()));
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            CHARGE.clear();
            LEAP.clear();
            RAGE.clear();
            AVATAR.clear();
            BULWARK.clear();
            TASKS.clear();
        });
    }

    private static void clearPlayer(UUID id) {
        CHARGE.remove(id);
        LEAP.remove(id);
        RAGE.remove(id);
        AVATAR.remove(id);
        BULWARK.remove(id);
    }

    // ---------------------------------------------------------------- helpers

    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    private static void sound(ServerPlayer player, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    private static boolean enraged(ServerPlayer p, long now) {
        return RAGE.getOrDefault(p.getUUID(), 0L) > now || AVATAR.containsKey(p.getUUID());
    }

    /** Skill damage for a base value: scales with Strength and level. */
    private static float dmg(PlayerData data, double base) {
        return (float) ((base + WarriorConfig.damagePerStrength * data.get(Stat.STRENGTH)) * (1 + WarriorConfig.damagePerLevel * data.level));
    }

    private static void strike(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        Compat.setInvulnerableTime(target, 0);
        target.hurtServer(level, level.damageSources().indirectMagic(p, p), amount);
    }

    private static void knock(LivingEntity e, Vec3 from, double power, double up) {
        Vec3 away = e.position().subtract(from);
        Vec3 dir = new Vec3(away.x, 0, away.z);
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 0) : dir.normalize();
        e.push(dir.x * power, up, dir.z * power);
        e.needsSync = true;
    }

    private static void stun(LivingEntity e, int ticks) {
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 10));
        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 2));
    }

    private static void after(ServerPlayer p, int ticks, Consumer<MinecraftServer> run) {
        TASKS.add(new Task(now(p) + ticks, run));
    }

    private static boolean valid(LivingEntity e) {
        return e.isAlive() && !(e instanceof Mob mob && EchoManager.isEcho(mob));
    }

    private static List<LivingEntity> foes(ServerPlayer p, ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : SkillTargeting.enemiesAround(p, level, radius)) {
            if (valid(e)) {
                out.add(e);
            }
        }
        return out;
    }

    private static Vec3 flatLook(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    private static void send(ServerPlayer p) {
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    private static void cleanse(ServerPlayer p) {
        for (MobEffectInstance inst : new ArrayList<>(p.getActiveEffects())) {
            if (!inst.getEffect().value().isBeneficial()) {
                p.removeEffect(inst.getEffect());
            }
        }
    }

    private static void slamEffects(ServerPlayer p, ServerLevel level, double radius, float damage, double knock, double up, int stunTicks) {
        for (LivingEntity e : foes(p, level, radius)) {
            strike(p, level, e, damage);
            knock(e, p.position(), knock, up);
            if (stunTicks > 0) {
                stun(e, stunTicks);
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.2, p.getZ(), 6, radius / 3, 0.1, radius / 3, 0);
        level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 0.3, p.getZ(), 40, radius / 2, 0.2, radius / 2, 0.3);
        sound(p, level, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.9f);
    }

    // ---------------------------------------------------------------- events

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (applying || amount <= 0 || !(victim.level() instanceof ServerLevel level)) {
            return EventResult.pass();
        }
        long now = level.getServer().getTickCount();
        if (victim instanceof ServerPlayer p) {
            if (BULWARK.getOrDefault(p.getUUID(), 0L) > now && CombatHandler.isMelee(source)
                    && source.getEntity() instanceof LivingEntity attacker && !(attacker instanceof ServerPlayer)) {
                applying = true;
                try {
                    attacker.hurtServer(level, level.damageSources().indirectMagic(p, p), (float) (amount * WarriorConfig.bulwarkReflect));
                } finally {
                    applying = false;
                }
                level.sendParticles(ParticleTypes.CRIT, attacker.getX(), attacker.getY(0.5), attacker.getZ(), 8, 0.2, 0.3, 0.2, 0.2);
            }
            return EventResult.pass();
        }
        if (source.getEntity() instanceof ServerPlayer p && enraged(p, now) && !EchoManager.isEcho(victim)) {
            double steal = AVATAR.containsKey(p.getUUID()) ? WarriorConfig.avatarLifesteal : WarriorConfig.rageLifesteal;
            p.heal((float) (amount * steal));
        }
        return EventResult.pass();
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            for (Task t : TASKS) {
                if (t.at() <= now) {
                    due.add(t);
                }
            }
            TASKS.removeAll(due);
            for (Task t : due) {
                t.run().accept(server);
            }
        }
        if (now % 20 == 0) {
            RAGE.values().removeIf(until -> until <= now);
            BULWARK.values().removeIf(until -> until <= now);
        }
        if (!CHARGE.isEmpty()) {
            tickCharges(server, now);
        }
        if (!LEAP.isEmpty()) {
            tickLeaps(server, now);
        }
        if (!AVATAR.isEmpty()) {
            tickAvatars(server, now);
        }
    }

    private static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && p.isAlive() ? p : null;
    }

    private static void tickCharges(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(CHARGE.keySet())) {
            Charge c = CHARGE.get(id);
            ServerPlayer p = playerOf(server, id);
            if (p == null || c.until <= now) {
                CHARGE.remove(id);
                continue;
            }
            ServerLevel level = p.level();
            for (LivingEntity e : foes(p, level, 2.2)) {
                if (c.hit.add(e.getUUID())) {
                    PlayerData data = PlayerDataStore.of(p);
                    strike(p, level, e, dmg(data, 3));
                    knock(e, p.position(), 1.2, 0.3);
                    stun(e, 20);
                    level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY(0.5), e.getZ(), 10, 0.3, 0.4, 0.3, 0.2);
                    sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), 1.0f);
                }
            }
            level.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 3, 0.3, 0.1, 0.3, 0.02);
        }
    }

    private static void tickLeaps(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(LEAP.keySet())) {
            Leap leap = LEAP.get(id);
            ServerPlayer p = playerOf(server, id);
            if (p == null) {
                LEAP.remove(id);
                continue;
            }
            boolean landed = now - leap.start() > 4 && p.onGround();
            if (!landed && now - leap.start() < 60) {
                continue;
            }
            LEAP.remove(id);
            PlayerData data = PlayerDataStore.of(p);
            ServerLevel level = p.level();
            if (leap.earth()) {
                earthbreak(p, level, data);
            } else {
                slamEffects(p, level, 4.5, dmg(data, 6), 1.0, 0.45, 0);
            }
        }
    }

    private static void tickAvatars(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(AVATAR.keySet())) {
            Avatar a = AVATAR.get(id);
            ServerPlayer p = playerOf(server, id);
            if (p == null) {
                AVATAR.remove(id);
                continue;
            }
            ServerLevel level = p.level();
            PlayerData data = PlayerDataStore.of(p);
            if (now >= a.until) {
                AVATAR.remove(id);
                slamEffects(p, level, WarriorConfig.avatarPulseRadius + 2, dmg(data, 10), 1.6, 0.6, 40);
                continue;
            }
            level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 2, 0.4, 0.8, 0.4, 0.01);
            if (now >= a.nextPulse) {
                a.nextPulse = now + 20;
                for (LivingEntity e : foes(p, level, WarriorConfig.avatarPulseRadius)) {
                    strike(p, level, e, dmg(data, 3));
                    knock(e, p.position(), 0.5, 0.15);
                }
                level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 0.3, p.getZ(), 25, 3, 0.2, 3, 0.3);
                sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 0.8f);
            }
        }
    }

    // ---------------------------------------------------------------- skills

    public static boolean shockwave(ServerPlayer p, ServerLevel level, PlayerData data) {
        slamEffects(p, level, 4, dmg(data, 4), 0.9, 0.35, 0);
        return true;
    }

    public static boolean charge(ServerPlayer p, ServerLevel level, PlayerData data) {
        Vec3 flat = flatLook(p);
        p.setDeltaMovement(flat.x * WarriorConfig.chargeSpeed, 0.15, flat.z * WarriorConfig.chargeSpeed);
        send(p);
        PlayerCombat.grantIFrames(p, 6);
        CHARGE.put(p.getUUID(), new Charge(now(p) + WarriorConfig.chargeTicks));
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_STEP), 0.8f);
        return true;
    }

    public static boolean warCry(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (10 + data.level / 10) * 20;
        int amp = Math.min(2, data.level / 40);
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, amp));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, Math.min(1, amp)));
        for (LivingEntity e : foes(p, level, 12)) {
            if (e instanceof Mob mob) {
                mob.setTarget(p);
            }
        }
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 1.8, p.getZ(), 10, 0.5, 0.3, 0.5, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_ROAR), 1.0f);
        return true;
    }

    public static boolean cleave(ServerPlayer p, ServerLevel level, PlayerData data) {
        Vec3 look = flatLook(p);
        int hits = 0;
        for (LivingEntity e : foes(p, level, 4.5)) {
            Vec3 to = e.position().subtract(p.position());
            Vec3 flat = new Vec3(to.x, 0, to.z);
            if (flat.lengthSqr() > 1.0e-4 && flat.normalize().dot(look) < Math.cos(Math.toRadians(70))) {
                continue;
            }
            strike(p, level, e, dmg(data, 5));
            knock(e, p.position(), 0.4, 0.1);
            hits++;
        }
        Vec3 c = p.position().add(look.scale(2));
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, p.getY() + 1, c.z, 3, 0.8, 0.2, 0.8, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 0.9f);
        return hits > 0;
    }

    public static boolean ironSkin(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (8 + data.level / 8) * 20;
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, 1 + data.level / 50));
        level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1, p.getZ(), 20, 0.5, 0.8, 0.5, 0.1);
        sound(p, level, GateboundSounds.of(SoundEvents.ANVIL_PLACE), 1.2f);
        return true;
    }

    public static boolean leapSlam(ServerPlayer p, ServerLevel level, PlayerData data) {
        Vec3 flat = flatLook(p);
        p.setDeltaMovement(flat.x * WarriorConfig.leapForward, WarriorConfig.leapUp, flat.z * WarriorConfig.leapForward);
        send(p);
        PlayerCombat.grantIFrames(p, 4);
        LEAP.put(p.getUUID(), new Leap(now(p), false));
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), 0.7f);
        return true;
    }

    public static boolean rendingStrike(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 4, 1, ParticleTypes.CRIT);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 0.8f);
        if (hits.isEmpty() || !valid(hits.get(0))) {
            return miss(p, RENDING_COST);
        }
        LivingEntity target = hits.get(0);
        strike(p, level, target, dmg(data, 7));
        float bleed = dmg(data, 1.5);
        for (int i = 1; i <= 5; i++) {
            after(p, i * 20, server -> {
                if (target.isAlive()) {
                    strike(p, target.level() instanceof ServerLevel sl ? sl : level, target, bleed);
                    level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY(0.6), target.getZ(), 4, 0.2, 0.3, 0.2, 0.1);
                }
            });
        }
        return true;
    }

    public static boolean bulwark(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.bulwarkSeconds + data.level / 20) * 20;
        BULWARK.put(p.getUUID(), now(p) + ticks);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 2));
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY() + 1, p.getZ(), 25, 0.6, 0.8, 0.6, 0.1);
        sound(p, level, GateboundSounds.of(SoundEvents.SHIELD_BLOCK.value()), 0.8f);
        return true;
    }

    public static boolean whirlwind(ServerPlayer p, ServerLevel level, PlayerData data) {
        for (int i = 0; i < 4; i++) {
            after(p, i * 5, server -> {
                if (!p.isAlive()) {
                    return;
                }
                ServerLevel l = p.level();
                for (LivingEntity e : foes(p, l, 4)) {
                    strike(p, l, e, dmg(data, 3));
                    knock(e, p.position(), 0.5, 0.1);
                }
                l.sendParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 6, 1.6, 0.2, 1.6, 0);
                sound(p, l, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 0.8f);
            });
        }
        return true;
    }

    public static boolean secondWind(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.heal((float) (p.getMaxHealth() * (0.3 + data.level / 500.0)));
        cleanse(p);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 25, 0.5, 0.8, 0.5, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_LEVELUP), 1.2f);
        return true;
    }

    public static boolean crushingBlow(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 4.5, 1, ParticleTypes.CRIT);
        sound(p, level, GateboundSounds.of(SoundEvents.MACE_SMASH_GROUND), 1.0f);
        if (hits.isEmpty() || !valid(hits.get(0))) {
            return miss(p, CRUSHING_COST);
        }
        LivingEntity target = hits.get(0);
        float amount = dmg(data, 4) * (float) (3 + data.level / 25.0);
        if (target.getHealth() < target.getMaxHealth() * 0.5f) {
            amount *= 1.5f;
        }
        strike(p, level, target, amount);
        stun(target, 30);
        level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY(0.5), target.getZ(), 2, 0.2, 0.2, 0.2, 0);
        return true;
    }

    public static boolean berserkerRage(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.rageBaseSeconds + data.level / 10) * 20;
        RAGE.put(p.getUUID(), now(p) + ticks);
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0));
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 1.8, p.getZ(), 12, 0.5, 0.4, 0.5, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_ROAR), 0.7f);
        return true;
    }

    public static boolean earthbreaker(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.setDeltaMovement(0, 1.0, 0);
        send(p);
        PlayerCombat.grantIFrames(p, 6);
        LEAP.put(p.getUUID(), new Leap(now(p), true));
        sound(p, level, GateboundSounds.of(SoundEvents.MACE_SMASH_AIR), 1.0f);
        return true;
    }

    private static void earthbreak(ServerPlayer p, ServerLevel level, PlayerData data) {
        double r = WarriorConfig.earthbreakerRadius;
        for (int wave = 0; wave < 3; wave++) {
            double radius = r * (wave + 1) / 3.0;
            float pitch = 0.7f + wave * 0.2f;
            after(p, wave * 4, server -> {
                if (!p.isAlive()) {
                    return;
                }
                ServerLevel l = p.level();
                for (LivingEntity e : foes(p, l, radius)) {
                    if (e.distanceToSqr(p) < (radius - r / 3.0) * (radius - r / 3.0)) {
                        continue;
                    }
                    strike(p, l, e, dmg(data, 9));
                    knock(e, p.position(), 0.7, 0.8);
                    stun(e, 40);
                }
                l.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.2, p.getZ(), 4, radius / 2, 0.1, radius / 2, 0);
                l.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX(), p.getY() + 0.3, p.getZ(), 30, radius / 2, 0.1, radius / 2, 0.05);
                sound(p, l, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), pitch);
            });
        }
    }

    public static boolean avatarOfWar(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.avatarBaseSeconds + data.level / 10) * 20;
        long now = now(p);
        AVATAR.put(p.getUUID(), new Avatar(now + ticks, now + 20));
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 2));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
        level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 60, 0.8, 1.0, 0.8, 0.05);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDER_DRAGON_GROWL), 0.8f);
        return true;
    }

    private static boolean miss(ServerPlayer p, int cost) {
        ManaState.add(p, cost * 0.5);
        Compat.overlay(p, net.minecraft.network.chat.Component.literal("No target in reach."));
        return false;
    }

    public static boolean isWarrior(ServerPlayer p) {
        return GateboundClass.WARRIOR.id().equals(PlayerDataStore.of(p).classId);
    }
}
