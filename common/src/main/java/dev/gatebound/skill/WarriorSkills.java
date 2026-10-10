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
import dev.gatebound.item.KnifeItems;
import dev.gatebound.skill.fx.FxState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import dev.gatebound.skill.fx.WarriorFx;
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

    /** A delayed server action due at tick `at`. */
    private record Task(long at, Consumer<MinecraftServer> run) {
    }

    private static final class Charge {
        final long until;
        final Set<UUID> hit = new HashSet<>();

        Charge(long until) {
            this.until = until;
        }
    }

    /** An in-flight Leap Slam or Earthbreaker jump. */
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

    /** Static holder; not instantiable. */
    private WarriorSkills() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Registers the Warrior tick and hurt hooks. */
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

    /** Drops all of a player's Warrior state. */
    private static void clearPlayer(UUID id) {
        CHARGE.remove(id);
        LEAP.remove(id);
        RAGE.remove(id);
        AVATAR.remove(id);
        BULWARK.remove(id);
    }

    // ---------------------------------------------------------------- helpers

    /** Current game time for the player's level. */
    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    /** Plays a sound at the player. */
    private static void sound(ServerPlayer player, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    /** True while Berserker Rage is active. */
    private static boolean enraged(ServerPlayer p, long now) {
        return RAGE.getOrDefault(p.getUUID(), 0L) > now || AVATAR.containsKey(p.getUUID());
    }

    /** Skill damage for a base value: scales with Strength and level. */
    private static float dmg(PlayerData data, double base) {
        return (float) ((base + WarriorConfig.damagePerStrength * data.get(Stat.STRENGTH)) * (1 + WarriorConfig.damagePerLevel * data.level));
    }

    /** Deals magic damage from the player, ignoring invulnerability frames. */
    private static void strike(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        Compat.setInvulnerableTime(target, 0);
        target.hurtServer(level, level.damageSources().indirectMagic(p, p), amount);
    }

    /** Knocks an entity away from a point. */
    private static void knock(LivingEntity e, Vec3 from, double power, double up) {
        Vec3 away = e.position().subtract(from);
        Vec3 dir = new Vec3(away.x, 0, away.z);
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 0) : dir.normalize();
        e.push(dir.x * power, up, dir.z * power);
        e.needsSync = true;
    }

    /** Stuns an entity with heavy Slowness and Weakness. */
    private static void stun(LivingEntity e, int ticks) {
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 10));
        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 2));
    }

    /** Runs an action for the player after some ticks. */
    private static void after(ServerPlayer p, int ticks, Consumer<MinecraftServer> run) {
        TASKS.add(new Task(now(p) + ticks, run));
    }

    /** True if the entity is a valid Warrior target. */
    private static boolean valid(LivingEntity e) {
        return e.isAlive() && !(e instanceof Mob mob && EchoManager.isEcho(mob));
    }

    /** Valid targets around the player within a radius. */
    private static List<LivingEntity> foes(ServerPlayer p, ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : SkillTargeting.creaturesAround(p, level, radius)) {
            if (valid(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The player's look direction flattened onto the horizontal plane. */
    private static Vec3 flatLook(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** Syncs the player's skill state to the client. */
    private static void send(ServerPlayer p) {
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    /** Removes harmful effects from the player. */
    private static void cleanse(ServerPlayer p) {
        for (MobEffectInstance inst : new ArrayList<>(p.getActiveEffects())) {
            if (!inst.getEffect().value().isBeneficial()) {
                p.removeEffect(inst.getEffect());
            }
        }
    }

    /** Shared ground-slam: damage, knockback, stun and visuals in a radius. */
    private static void slamEffects(ServerPlayer p, ServerLevel level, double radius, float damage, double knock, double up, int stunTicks) {
        for (LivingEntity e : foes(p, level, radius)) {
            strike(p, level, e, damage);
            knock(e, p.position(), knock, up);
            if (stunTicks > 0) {
                stun(e, stunTicks);
            }
        }
        WarriorFx.slam(p, level, radius, stunTicks > 0 || radius >= 5);
        sound(p, level, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.9f);
    }

    // ---------------------------------------------------------------- events

    /** Hurt hook: Bulwark reflect, passive Warrior strike and Rage/Avatar lifesteal. */
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
                WarriorFx.bulwarkReflect(level, p, attacker);
            }
            return EventResult.pass();
        }
        if (source.getEntity() instanceof ServerPlayer w && isWarrior(w) && CombatHandler.isMelee(source) && victim instanceof Mob mob
                && !EchoManager.isEcho(mob)) {
            passiveStrike(w, level, mob, amount);
        }
        if (source.getEntity() instanceof ServerPlayer p && enraged(p, now) && !EchoManager.isEcho(victim)) {
            double steal = AVATAR.containsKey(p.getUUID()) ? WarriorConfig.avatarLifesteal : WarriorConfig.rageLifesteal;
            p.heal((float) (amount * steal));
        }
        return EventResult.pass();
    }

    /** Per-tick driver for tasks, charges, leaps and Avatars. */
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

    /** Looks a player up by UUID. */
    private static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && p.isAlive() ? p : null;
    }

    /** Per-tick upkeep of active Charges. */
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
                    WarriorFx.hitSlash(level, e, p.position());
                    sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), 1.0f);
                }
            }
            if (now % 2 == 0) {
                WarriorFx.streak(p);
            }
        }
    }

    /** Per-tick upkeep of active leaps and their landings. */
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
                if (!leap.earth() && now % 2 == 0) {
                    WarriorFx.streak(p);
                }
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

    /** Per-tick upkeep of Avatar of War (aura, pulses, end). */
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
            if (now >= a.nextPulse) {
                a.nextPulse = now + 20;
                for (LivingEntity e : foes(p, level, WarriorConfig.avatarPulseRadius)) {
                    strike(p, level, e, dmg(data, 3));
                    knock(e, p.position(), 0.5, 0.15);
                }
                WarriorFx.avatarPulse(p, WarriorConfig.avatarPulseRadius);
                sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 0.8f);
            }
        }
    }

    // ---------------------------------------------------------------- skills

    /** Shockwave: slams the ground, damaging and hurling back enemies around you. */
    public static boolean shockwave(ServerPlayer p, ServerLevel level, PlayerData data) {
        slamEffects(p, level, 4, dmg(data, 4), 0.9, 0.35, 0);
        return true;
    }

    /** Charge: rush forward untouchable, bowling over and stunning enemies touched. */
    public static boolean charge(ServerPlayer p, ServerLevel level, PlayerData data) {
        Vec3 flat = flatLook(p);
        p.setDeltaMovement(flat.x * WarriorConfig.chargeSpeed, 0.15, flat.z * WarriorConfig.chargeSpeed);
        send(p);
        PlayerCombat.grantIFrames(p, 6);
        CHARGE.put(p.getUUID(), new Charge(now(p) + WarriorConfig.chargeTicks));
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_STEP), 0.8f);
        return true;
    }

    /** War Cry: Strength and Resistance for you; nearby enemies turn on you. */
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
        WarriorFx.warCry(p);
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_ROAR), 1.0f);
        return true;
    }

    /** Cleave: wide sweep hitting every enemy in front. */
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
            if (hits <= 6) {
                WarriorFx.hitSlash(level, e, p.position());
            }
        }
        WarriorFx.cleave(p);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 0.9f);
        return hits > 0;
    }

    /** Iron Skin: long Resistance II and Absorption. */
    public static boolean ironSkin(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (8 + data.level / 8) * 20;
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, 1 + data.level / 50));
        WarriorFx.ironSkin(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.ANVIL_PLACE), 1.2f);
        return true;
    }

    /** Leap Slam: leap forward and crash down, launching enemies where you land. */
    public static boolean leapSlam(ServerPlayer p, ServerLevel level, PlayerData data) {
        Vec3 flat = flatLook(p);
        p.setDeltaMovement(flat.x * WarriorConfig.leapForward, WarriorConfig.leapUp, flat.z * WarriorConfig.leapForward);
        send(p);
        PlayerCombat.grantIFrames(p, 4);
        LEAP.put(p.getUUID(), new Leap(now(p), false));
        WarriorFx.leapStart(p);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), 0.7f);
        return true;
    }

    /** Rending Strike: heavy blow that leaves the target bleeding. */
    public static boolean rendingStrike(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 4, 1, ParticleTypes.CRIT);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 0.8f);
        if (hits.isEmpty() || !valid(hits.get(0))) {
            return miss(p, RENDING_COST);
        }
        LivingEntity target = hits.get(0);
        strike(p, level, target, dmg(data, 7));
        WarriorFx.rending(p, target, 100);
        float bleed = dmg(data, 1.5);
        for (int i = 1; i <= 5; i++) {
            after(p, i * 20, server -> {
                if (target.isAlive()) {
                    strike(p, target.level() instanceof ServerLevel sl ? sl : level, target, bleed);
                    WarriorFx.bleedTick(level, target);
                }
            });
        }
        return true;
    }

    /** Bulwark: Resistance III and part of melee damage is struck back. */
    public static boolean bulwark(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.bulwarkSeconds + data.level / 20) * 20;
        BULWARK.put(p.getUUID(), now(p) + ticks);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 2));
        WarriorFx.bulwark(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.SHIELD_BLOCK.value()), 0.8f);
        return true;
    }

    /** Whirlwind: spin, hitting everything around you several times. */
    public static boolean whirlwind(ServerPlayer p, ServerLevel level, PlayerData data) {
        WarriorFx.whirlwind(p);
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
                sound(p, l, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 0.8f);
            });
        }
        return true;
    }

    /** Second Wind: heals a third of health, cleanses and grants speed. */
    public static boolean secondWind(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.heal((float) (p.getMaxHealth() * (0.3 + data.level / 500.0)));
        cleanse(p);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
        WarriorFx.secondWind(p);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_LEVELUP), 1.2f);
        return true;
    }

    /** Crushing Blow: devastating stunning hit, stronger on wounded enemies. */
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
        WarriorFx.crushing(p, target);
        return true;
    }

    /** Berserker Rage: Strength II, Speed and lifesteal. */
    public static boolean berserkerRage(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.rageBaseSeconds + data.level / 10) * 20;
        RAGE.put(p.getUUID(), now(p) + ticks);
        FxState.set(p, "bar:rage:" + ticks, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0));
        WarriorFx.rage(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.RAVAGER_ROAR), 0.7f);
        return true;
    }

    /** Earthbreaker: spring up and break the earth on landing. */
    public static boolean earthbreaker(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.setDeltaMovement(0, 1.0, 0);
        send(p);
        PlayerCombat.grantIFrames(p, 6);
        LEAP.put(p.getUUID(), new Leap(now(p), true));
        WarriorFx.earthbreakStart(p);
        sound(p, level, GateboundSounds.of(SoundEvents.MACE_SMASH_AIR), 1.0f);
        return true;
    }

    /** Landing of Earthbreaker: three shockwaves that launch and stun. */
    private static void earthbreak(ServerPlayer p, ServerLevel level, PlayerData data) {
        double r = WarriorConfig.earthbreakerRadius;
        for (int wave = 0; wave < 3; wave++) {
            double radius = r * (wave + 1) / 3.0;
            float pitch = 0.7f + wave * 0.2f;
            int waveNo = wave;
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
                WarriorFx.earthbreakWave(p, l, radius, waveNo);
                sound(p, l, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), pitch);
            });
        }
    }

    /** Avatar of War: ultimate form with huge buffs, pulses, lifesteal and a final slam. */
    public static boolean avatarOfWar(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (WarriorConfig.avatarBaseSeconds + data.level / 10) * 20;
        long now = now(p);
        AVATAR.put(p.getUUID(), new Avatar(now + ticks, now + 20));
        FxState.set(p, "bar:avatar:" + ticks, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 2));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 1));
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
        WarriorFx.avatarStart(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDER_DRAGON_GROWL), 0.8f);
        return true;
    }

    // ---------------------------------------------------------------- gear and passives

    private static final Set<String> WEAPON_SKILLS = Set.of("shockwave", "charge", "cleave", "leap_slam", "rending_strike", "whirlwind",
            "crushing_blow", "earthbreaker");

    private static final Set<net.minecraft.world.item.Item> HEAVY = Set.of(
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);

    /** How many iron, diamond or netherite armour pieces the player wears. */
    public static int heavyPieces(ServerPlayer p) {
        int n = 0;
        for (EquipmentSlot slot : KnifeItems.ARMOR_SLOTS) {
            if (HEAVY.contains(p.getItemBySlot(slot).getItem())) {
                n++;
            }
        }
        return n;
    }

    /** Called by SkillHandler before any mana is spent: Warrior skills that hit need a melee weapon in hand. */
    public static boolean canUse(ServerPlayer p, Skill skill) {
        if (skill.owner() != GateboundClass.WARRIOR || !WarriorConfig.requireWeapon || !WEAPON_SKILLS.contains(skill.id())
                || KnifeItems.isMeleeWeapon(p.getMainHandItem())) {
            return true;
        }
        Compat.overlay(p, net.minecraft.network.chat.Component.literal("A Warrior's strikes need a melee weapon in hand.")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        return false;
    }

    /** Every melee hit lands a second one: Strength and heavy armour add to it. Applied a tick later (i-frames would eat it now). */
    private static void passiveStrike(ServerPlayer p, ServerLevel level, LivingEntity victim, float amount) {
        PlayerData data = PlayerDataStore.of(p);
        double bonus = Math.min(WarriorConfig.meleeCap, WarriorConfig.meleePerStrength * data.get(Stat.STRENGTH))
                + WarriorConfig.heavyPieceBonus * heavyPieces(p);
        float extra = (float) (amount * bonus);
        if (extra < 0.05f) {
            return;
        }
        after(p, 1, server -> {
            if (victim.isAlive()) {
                applying = true;
                try {
                    Compat.setInvulnerableTime(victim, 0);
                    victim.hurtServer(level, level.damageSources().indirectMagic(p, p), extra);
                } finally {
                    applying = false;
                }
            }
        });
    }

    /** The nearest enemies within a radius, for effects that launch from the body. */
    public static List<LivingEntity> nearestFoes(ServerPlayer p, ServerLevel level, double radius, int max) {
        List<LivingEntity> list = new ArrayList<>(foes(p, level, radius));
        list.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return list.size() > max ? list.subList(0, max) : list;
    }

    /** Damage dealt by something launched from the body (Avatar of War's slash waves). */
    public static void formHit(ServerPlayer p, LivingEntity target, double base) {
        if (target.isAlive() && target.level() instanceof ServerLevel level) {
            strike(p, level, target, dmg(PlayerDataStore.of(p), base) * 0.8f);
        }
    }

    /** Fails a cast with a message and no cost. */
    private static boolean miss(ServerPlayer p, int cost) {
        ManaState.add(p, cost * 0.5);
        Compat.overlay(p, net.minecraft.network.chat.Component.literal("No target in reach."));
        return false;
    }

    /** True if the player is a Warrior. */
    public static boolean isWarrior(ServerPlayer p) {
        return GateboundClass.WARRIOR.id().equals(PlayerDataStore.of(p).classId);
    }
}
