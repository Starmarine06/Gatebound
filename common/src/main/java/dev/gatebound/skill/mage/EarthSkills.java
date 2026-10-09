package dev.gatebound.skill.mage;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Earth: Bulwark. Defence and terrain. Each earth skill adds a stone Armor stack to the caster (up to five): every stack
 * grants absorption and makes earth skills hit 10% harder. Stacks fade after thirty seconds without a new one.
 */
public final class EarthSkills {
    public static final int SPIKE_COST = 10;
    public static final int SKIN_COST = 18;
    public static final int WALL_COST = 24;
    public static final int TREMOR_COST = 26;
    public static final int BOULDER_COST = 32;
    public static final int BEDROCK_COST = 40;
    public static final int QUAKE_COST = 70;
    public static final int LANDSLIDE_COST = 90;
    public static final int TITAN_COST = 90;

    private static final int MAX_STACKS = 5;

    private static final class Armor {
        int stacks;
        long until;
    }

    private static final Map<UUID, Armor> ARMOR = new HashMap<>();

    private EarthSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(EarthSkills::onHurt);
        LifecycleEvent.SERVER_STOPPED.register(server -> ARMOR.clear());
    }

    // ---------------------------------------------------------------- helpers

    private static ParticleOptions rock() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    }

    private static void gainArmor(ServerPlayer p, int stacks) {
        long now = p.level().getServer().getTickCount();
        Armor a = ARMOR.computeIfAbsent(p.getUUID(), k -> new Armor());
        if (a.until <= now) {
            a.stacks = 0;
        }
        a.stacks = Math.min(MAX_STACKS, a.stacks + stacks);
        a.until = now + 600;
        p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), a.stacks * 2.0f));
    }

    private static float earthDmg(ServerPlayer p, PlayerData data, double base) {
        Armor a = ARMOR.get(p.getUUID());
        int stacks = a != null && a.until > p.level().getServer().getTickCount() ? a.stacks : 0;
        return (float) (MageCore.dmg(data, base) * (1 + 0.1 * stacks));
    }

    private static void smash(ServerPlayer p, ServerLevel level, PlayerData data, LivingEntity e, double base, double up, int slowTicks) {
        MageCore.strike(p, level, e, earthDmg(p, data, base));
        if (up > 0) {
            e.setDeltaMovement(e.getDeltaMovement().x, up, e.getDeltaMovement().z);
            e.needsSync = true;
        }
        if (slowTicks > 0) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, slowTicks, 10));
        }
        MageCore.particles(level, rock(), e.position().add(0, 0.3, 0), 14, 0.4, 0.15);
    }

    // ---------------------------------------------------------------- events

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || victim instanceof ServerPlayer || !(victim.level() instanceof ServerLevel level)
                || !(source.getEntity() instanceof ServerPlayer p) || !MageCore.inForm(p, Element.EARTH)) {
            return EventResult.pass();
        }
        PlayerData data = dev.gatebound.data.PlayerDataStore.of(p);
        for (LivingEntity e : MageCore.foesAt(p, level, victim.position(), 3)) {
            smash(p, level, data, e, 2, 0.35, 0);
            if (e != victim) {
                MageCore.knock(e, victim.position(), 0.6, 0);
            }
        }
        MageCore.sound(level, victim.position(), GateboundSounds.of(SoundEvents.STONE_BREAK), 1.0f, 0.6f);
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- skills

    public static boolean stoneSpike(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, SPIKE_COST);
        gainArmor(p, 1);
        Vec3 flat = MageCore.flatLook(p);
        Set<UUID> hit = new HashSet<>();
        for (int i = 1; i <= 12; i++) {
            Vec3 at = p.position().add(flat.scale(i));
            MageCore.after(i, server -> {
                level.sendParticles(rock(), at.x, at.y + 0.4, at.z, 20, 0.25, 0.5, 0.25, 0.15);
                level.sendParticles(ParticleTypes.CRIT, at.x, at.y + 0.8, at.z, 4, 0.2, 0.3, 0.2, 0.2);
                MageCore.sound(level, at, GateboundSounds.of(SoundEvents.STONE_BREAK), 0.5f, 0.8f);
                ServerPlayer o = MageCore.playerOf(server, p.getUUID());
                if (o != null) {
                    for (LivingEntity e : MageCore.foesAt(o, level, at, 1.3)) {
                        if (hit.add(e.getUUID())) {
                            smash(o, level, data, e, 5.5, 0.6, 40);
                        }
                    }
                }
            });
        }
        return true;
    }

    public static boolean stoneSkin(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, SKIN_COST);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1));
        gainArmor(p, 2);
        level.sendParticles(rock(), p.getX(), p.getY() + 1, p.getZ(), 30, 0.5, 0.8, 0.5, 0.1);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.STONE_PLACE), 1.0f, 0.7f);
        return true;
    }

    public static boolean stoneWall(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, WALL_COST);
        gainArmor(p, 1);
        Vec3 flat = MageCore.flatLook(p);
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        Vec3 center = p.position().add(flat.scale(3));
        int placed = 0;
        for (int w = -2; w <= 2; w++) {
            for (int h = 0; h < 3; h++) {
                Vec3 q = center.add(side.scale(w));
                BlockPos pos = BlockPos.containing(q.x, p.getY() + h, q.z);
                if (MageCore.tempBlock(level, pos, Blocks.COBBLESTONE.defaultBlockState(), 200)) {
                    placed++;
                    level.sendParticles(rock(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
        MageCore.sound(level, center, GateboundSounds.of(SoundEvents.STONE_PLACE), 1.2f, 0.6f);
        return placed > 0;
    }

    public static boolean tremor(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, TREMOR_COST);
        gainArmor(p, 1);
        for (LivingEntity e : MageCore.foes(p, level, 7)) {
            smash(p, level, data, e, 3, 0.3, 50);
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 50, 2));
        }
        level.sendParticles(rock(), p.getX(), p.getY() + 0.2, p.getZ(), 80, 3.5, 0.2, 3.5, 0.2);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.6f, 0.5f);
        return true;
    }

    public static boolean boulder(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, BOULDER_COST);
        gainArmor(p, 1);
        Vec3 flat = MageCore.flatLook(p);
        MageCore.bolt(p, level, p.position().add(0, 1.0, 0).add(flat.scale(1.2)), flat, 0.7, 22, 1.3, rock(),
                (lv, at, target) -> {
                    MageCore.particles(lv, rock(), at, 40, 0.8, 0.2);
                    MageCore.sound(lv, at, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.8f, 0.6f);
                    for (LivingEntity e : MageCore.foesAt(p, lv, at, 2.5)) {
                        smash(p, lv, data, e, e == target ? 11 : 6, 0.5, 0);
                        MageCore.knock(e, at, 0.9, 0);
                    }
                });
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.STONE_BREAK), 1.0f, 0.5f);
        return true;
    }

    public static boolean bedrock(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, BEDROCK_COST);
        gainArmor(p, MAX_STACKS);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 2));
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 3));
        level.sendParticles(rock(), p.getX(), p.getY() + 1, p.getZ(), 60, 0.7, 1.0, 0.7, 0.1);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.ANVIL_LAND), 0.8f, 0.6f);
        Compat.overlay(p, Component.literal("You are bedrock."));
        return true;
    }

    public static boolean earthquake(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, QUAKE_COST);
        gainArmor(p, 2);
        Vec3 center = p.position();
        MageCore.zone(p, level, center, 13, null, 120, 10,
                in -> {
                    for (LivingEntity e : in) {
                        smash(p, level, data, e, 4.5, 0.55, 15);
                    }
                    MageCore.sound(level, center, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 0.7f, 0.4f);
                },
                lv -> {
                    for (int i = 0; i < 16; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        double r = Math.sqrt(lv.getRandom().nextDouble()) * 13;
                        lv.sendParticles(rock(), center.x + Math.cos(a) * r, center.y + 0.2, center.z + Math.sin(a) * r, 2, 0.2, 0.3, 0.2, 0.1);
                    }
                });
        return true;
    }

    public static boolean landslide(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.EARTH, LANDSLIDE_COST);
        gainArmor(p, 2);
        Vec3 flat = MageCore.flatLook(p);
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        Set<UUID> hit = new HashSet<>();
        for (int i = 1; i <= 22; i++) {
            int d = i;
            MageCore.after(i, server -> {
                Vec3 front = p.position().add(flat.scale(d + 1));
                for (int w = -3; w <= 3; w++) {
                    Vec3 q = front.add(side.scale(w));
                    level.sendParticles(rock(), q.x, q.y + 0.6, q.z, 8, 0.3, 0.6, 0.3, 0.15);
                    for (LivingEntity e : MageCore.foesAt(p, level, q, 1.3)) {
                        if (hit.add(e.getUUID())) {
                            smash(p, level, data, e, 11, 0.6, 0);
                            e.push(flat.x * 1.5, 0, flat.z * 1.5);
                        }
                    }
                }
                if (d % 3 == 0) {
                    MageCore.sound(level, front, GateboundSounds.of(SoundEvents.STONE_BREAK), 0.8f, 0.5f);
                }
            });
        }
        return true;
    }

    public static boolean titanForm(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = MageConfig.formSeconds * 20;
        p.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, ticks, 4));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 2));
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 1));
        p.heal(20.0f);
        gainArmor(p, MAX_STACKS);
        MageCore.form(p, Element.EARTH, 20, o -> {
            ServerLevel lv = o.level();
            lv.sendParticles(rock(), o.getX(), o.getY() + 1, o.getZ(), 10, 0.6, 0.9, 0.6, 0.05);
            gainArmor(o, 1);
        });
        level.sendParticles(rock(), p.getX(), p.getY() + 1, p.getZ(), 80, 1.0, 1.0, 1.0, 0.2);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.ANVIL_LAND), 1.0f, 0.4f);
        Compat.overlay(p, Component.literal("You become a Titan."));
        return true;
    }
}
