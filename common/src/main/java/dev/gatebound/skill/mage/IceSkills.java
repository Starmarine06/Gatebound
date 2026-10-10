package dev.gatebound.skill.mage;

import dev.gatebound.skill.fx.FormFx;
import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.gatebound.data.Element;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.skill.fx.CastFx;
import dev.gatebound.skill.fx.Circles;
import dev.gatebound.skill.fx.Fx;
import dev.gatebound.skill.fx.Palette;
import dev.gatebound.skill.fx.Props;
import net.minecraft.core.BlockPos;
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
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ice: Chill. Every ice hit adds a Chill stack; at three the target is Frozen solid for a few seconds. Frozen targets are
 * what Shatter spends.
 */
public final class IceSkills {
    public static final int SHARD_COST = 9;
    public static final int NOVA_COST = 22;
    public static final int ARMOR_COST = 16;
    public static final int PATH_COST = 24;
    public static final int BARRAGE_COST = 30;
    public static final int SHATTER_COST = 40;
    public static final int BLIZZARD_COST = 65;
    public static final int ZERO_COST = 95;
    public static final int SOVEREIGN_COST = 90;

    private static final String ARMOR = "ice_armor";

    private static final class Chill {
        int stacks;
        long until;
    }

    private static final Map<UUID, Chill> CHILL = new HashMap<>();
    private static final Map<UUID, Long> FROZEN = new HashMap<>();
    private static final Map<UUID, Props.Prop> SHELLS = new HashMap<>();
    private static final Palette ICE = Palette.of(Element.ICE);

    private IceSkills() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(IceSkills::onHurt);
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            CHILL.clear();
            FROZEN.clear();
            SHELLS.clear();
        });
        // A frozen enemy that dies takes its shell with it.
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            breakShell(entity.getUUID());
            return EventResult.pass();
        });
    }

    // ---------------------------------------------------------------- chill and freeze

    private static boolean frozen(LivingEntity e) {
        Long until = FROZEN.get(e.getUUID());
        return until != null && until > e.level().getServer().getTickCount();
    }

    /** Adds Chill stacks; reaching the limit freezes the target solid. */
    private static void chill(ServerLevel level, LivingEntity target, int stacks) {
        long now = level.getServer().getTickCount();
        Chill c = CHILL.computeIfAbsent(target.getUUID(), k -> new Chill());
        if (c.until <= now) {
            c.stacks = 0;
        }
        c.stacks += stacks;
        c.until = now + MageConfig.chillTicks;
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MageConfig.chillTicks, Math.min(3, c.stacks)));
        MageCore.particles(level, ParticleTypes.SNOWFLAKE, target.position().add(0, target.getBbHeight() * 0.5, 0), 6, 0.4, 0.02);
        if (c.stacks >= MageConfig.chillToFreeze) {
            CHILL.remove(target.getUUID());
            freeze(level, target, MageConfig.freezeTicks);
        }
    }

    private static void freeze(ServerLevel level, LivingEntity target, int ticks) {
        FROZEN.put(target.getUUID(), (long) level.getServer().getTickCount() + ticks);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 10));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 3));
        target.setTicksFrozen(target.getTicksRequiredToFreeze() + ticks);
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.5, 0);
        MageCore.particles(level, ParticleTypes.SNOWFLAKE, mid, 30, 0.5, 0.05);
        Fx.sphere(level, ICE.accent(), mid, 14, 0.2);
        Fx.ring(level, Fx.dust(ICE.rgb1(), 1.1f), target.position().add(0, 0.1, 0), target.getBbWidth() + 0.4, 18);
        MageCore.sound(level, target.position(), GateboundSounds.of(SoundEvents.GLASS_BREAK), 0.7f, 1.8f);
        // The target is encased in a block of ice until it thaws or is shattered.
        Props.Prop old = SHELLS.remove(target.getUUID());
        if (old != null) {
            old.remove();
        }
        double w = target.getBbWidth() + 0.5;
        double h = target.getBbHeight() + 0.3;
        Props.Prop shell = Props.block(level, target.position().add(0, -0.1, 0), "minecraft:ice", 0.1, 0.1, 0.1, ticks + 5);
        shell.to(Vec3.ZERO, w, h, w, 0, 4); // the ice grows around it
        SHELLS.put(target.getUUID(), shell);
    }

    /** Breaks the ice shell around a target, if it has one. */
    private static void breakShell(UUID id) {
        Props.Prop shell = SHELLS.remove(id);
        if (shell != null) {
            shell.remove();
        }
    }

    private static void hit(ServerPlayer p, ServerLevel level, LivingEntity target, float damage, int stacks) {
        MageCore.strike(p, level, target, damage);
        chill(level, target, stacks);
    }

    // ---------------------------------------------------------------- events

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (MageCore.applying() || amount <= 0 || !(victim.level() instanceof ServerLevel level)) {
            return EventResult.pass();
        }
        if (victim instanceof ServerPlayer p) {
            if ((MageCore.buffed(p, ARMOR) || MageCore.inForm(p, Element.ICE))
                    && source.getEntity() instanceof LivingEntity attacker && !(attacker instanceof ServerPlayer)) {
                chill(level, attacker, 1);
            }
            return EventResult.pass();
        }
        if (source.getEntity() instanceof ServerPlayer p && MageCore.inForm(p, Element.ICE)) {
            chill(level, victim, MageConfig.chillToFreeze);
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- skills

    public static boolean frostShard(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, SHARD_COST);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GLASS_BREAK), 0.8f, 1.6f);
        Props.Prop[] shard = {null};
        Vec3 aim = p.getLookAngle();
        MageCore.bolt(p, level, p.getEyePosition().add(p.getLookAngle().scale(0.5)), p.getLookAngle(), 1.7, 36, 0.6,
                (lv, a, b, tick) -> {
                    if (shard[0] == null) {
                        shard[0] = Props.sprite(lv, a, "ice_shard", 80); // a model of a crystal, pointing the way it flies
                    }
                    shard[0].pose(b.subtract(shard[0].origin()), Circles.face(aim, tick * 0.5), 0.55, 1.1, 0.55, 1);
                    Fx.trail(lv, ICE, a, b, tick);
                    lv.sendParticles(ICE.accent(), b.x, b.y, b.z, 2, 0.12, 0.12, 0.12, 0.02); // the shard's glittering head
                },
                (lv, at, target) -> {
                    if (shard[0] != null) {
                        shard[0].remove();
                    }
                    if (target != null) {
                        Circles.onTarget(lv, Element.ICE, p.position(), target, 2.4, 14);
                    }
                    Fx.impact(lv, ICE, at, 1.4);
                    Fx.sphere(lv, ICE.accent(), at, 16, 0.3);
                    if (target != null) {
                        hit(p, lv, target, MageCore.dmg(data, 4.5), 1);
                    }
                });
        return true;
    }

    public static boolean frostNova(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, NOVA_COST);
        for (LivingEntity e : MageCore.foes(p, level, 5.5)) {
            hit(p, level, e, MageCore.dmg(data, 3), 2);
        }
        Fx.shockwave(level, ICE, p.position(), 5.5, 8);
        Circles.ground(level, Element.ICE, p.position(), 11.5, 24);
        Fx.sphere(level, ParticleTypes.SNOWFLAKE, p.position().add(0, 1, 0), 50, 0.5);
        // Frost spikes thrust up around the edge of the ring.
        for (int i = 0; i < 10; i++) {
            double a = Math.PI * 2 * i / 10;
            Vec3 at = p.position().add(Math.cos(a) * 4.2, 0, Math.sin(a) * 4.2);
            MageCore.after(2 + i % 3, server -> spike(level, at, 1.6 + level.getRandom().nextDouble(), 30));
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GLASS_BREAK), 0.8f, 0.6f);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.PLAYER_HURT_FREEZE), 1.0f, 0.8f);
        return true;
    }

    /** A spike of ice that shoots up out of the ground, stays a moment, then sinks away. */
    private static void spike(ServerLevel level, Vec3 base, double height, int life) {
        // The spike model is centred, so its middle sits half its height above the ground.
        Props.Prop p = Props.sprite(level, base, "ice_spike", life + 12);
        Quaternionf up = Circles.face(new Vec3(0, 1, 0), level.getRandom().nextDouble() * 3);
        p.pose(new Vec3(0, 0.05, 0), up, 0.3, 0.1, 0.3, 0);
        MageCore.after(1, server -> p.pose(new Vec3(0, height * 0.5, 0), up, height * 0.5, height, height * 0.5, 3));
        Fx.ring(level, ICE.accent(), base.add(0, 0.1, 0), 0.5, 8);
        MageCore.after(life, server -> p.pose(new Vec3(0, 0.05, 0), up, 0.3, 0.1, 0.3, 6));
        level.sendParticles(ICE.main(), base.x, base.y + height, base.z, 8, 0.2, 0.2, 0.2, 0.05);
    }

    public static boolean iceArmor(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, ARMOR_COST);
        int ticks = MageConfig.buffSeconds * 20;
        MageCore.buff(p, ARMOR, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 0));
        Fx.spiral(level, ICE.main(), p.position(), 0.9, 2.2, 3, 14);
        Fx.sphere(level, ICE.accent(), p.position().add(0, 1, 0), 24, 0.25);
        // Shards of ice orbit the caster while the armour holds.
        MageCore.run((server, now) -> {
            if (!MageCore.buffed(p, ARMOR) || !p.isAlive() || p.isRemoved()) {
                return true;
            }
            if (now % 2 == 0) {
                Fx.swirl(p.level(), ICE.accent(), p.position(), 1.0, 2.0, now, 3);
                Fx.swirl(p.level(), Fx.dust(ICE.rgb1(), 0.8f), p.position(), 0.8, 1.8, now + 6, 2);
            }
            return false;
        });
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.0f, 0.6f);
        Compat.overlay(p, Component.literal("Frost armours you."));
        return true;
    }

    public static boolean glacialPath(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, PATH_COST);
        Vec3 flat = MageCore.flatLook(p);
        Vec3 side = new Vec3(-flat.z, 0, flat.x);
        int y = (int) Math.floor(p.getY()) - 1;
        int placed = 0;
        for (int step = 1; step <= 14; step++) {
            for (int w = -1; w <= 1; w++) {
                Vec3 q = p.position().add(flat.scale(step)).add(side.scale(w));
                BlockPos pos = new BlockPos((int) Math.floor(q.x), y, (int) Math.floor(q.z));
                if (MageCore.tempBlock(level, pos, Blocks.PACKED_ICE.defaultBlockState(), MageConfig.pathSeconds * 20)) {
                    placed++;
                    int s = step;
                    // The ice spreads out from the caster one step at a time.
                    MageCore.after(s, server -> {
                        level.sendParticles(ParticleTypes.SNOWFLAKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.02);
                        level.sendParticles(ICE.accent(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.3, 0.1, 0.3, 0.05);
                        if (s % 3 == 0) {
                            MageCore.sound(level, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), GateboundSounds.of(SoundEvents.GLASS_PLACE), 0.4f, 1.3f);
                        }
                    });
                }
            }
        }
        Fx.shockwave(level, ICE, p.position(), 3.0, 5);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GLASS_PLACE), 1.0f, 0.7f);
        return placed > 0;
    }

    public static boolean icicleBarrage(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, BARRAGE_COST);
        for (int i = 0; i < 7; i++) {
            int n = i;
            MageCore.after(i * 2, server -> {
                ServerPlayer o = MageCore.playerOf(server, p.getUUID());
                if (o == null) {
                    return;
                }
                // A fan across the view, each shard slightly off true.
                Vec3 look = o.getLookAngle();
                Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
                Vec3 dir = look.add(side.scale((n - 3) * 0.06)).add(0, (o.getRandom().nextDouble() - 0.5) * 0.05, 0).normalize();
                MageCore.sound(level, o.position(), GateboundSounds.of(SoundEvents.GLASS_BREAK), 0.5f, 1.8f);
                Fx.put(level, ICE.accent(), o.getEyePosition().add(look.scale(0.5)));
                Props.Prop[] icicle = {null};
                MageCore.bolt(o, level, o.getEyePosition().add(look.scale(0.5)), dir, 1.8, 32, 0.5,
                        (lv, a, b, tick) -> {
                            if (icicle[0] == null) {
                                icicle[0] = Props.sprite(lv, a, "ice_shard", 60);
                            }
                            icicle[0].pose(b.subtract(icicle[0].origin()), Circles.face(dir, tick * 0.6), 0.4, 0.9, 0.4, 1);
                            Fx.trail(lv, ICE, a, b, tick);
                        },
                        (lv, at, target) -> {
                            if (icicle[0] != null) {
                                icicle[0].remove();
                            }
                            Fx.sphere(lv, ICE.accent(), at, 8, 0.2);
                            MageCore.particles(lv, ICE.main(), at, 10, 0.3, 0.05);
                            if (target != null) {
                                hit(o, lv, target, MageCore.dmg(data, 2.8), 1);
                            }
                        });
            });
        }
        return true;
    }

    public static boolean shatter(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, SHATTER_COST);
        int count = 0;
        for (LivingEntity e : MageCore.foes(p, level, 18)) {
            if (frozen(e)) {
                count++;
                FROZEN.remove(e.getUUID());
                breakShell(e.getUUID());
                e.removeEffect(MobEffects.SLOWNESS);
                e.removeEffect(MobEffects.WEAKNESS);
                e.setTicksFrozen(0);
                Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0);
                // The ice bursts into flying shards.
                Fx.sphere(level, ICE.accent(), at, 60, 0.55);
                Fx.sphere(level, ParticleTypes.SNOWFLAKE, at, 40, 0.4);
                Fx.sphere(level, ParticleTypes.ITEM_SNOWBALL, at, 20, 0.3);
                Fx.shockwave(level, ICE, e.position(), 3.5, 5);
                Circles.onTarget(level, Element.ICE, p.position(), e, 2.6, 14);
                MageCore.flash(level, at.x, at.y, at.z);
                MageCore.sound(level, at, GateboundSounds.of(SoundEvents.GLASS_BREAK), 1.2f, 0.5f);
                MageCore.sound(level, at, GateboundSounds.of(SoundEvents.GLASS_BREAK), 1.0f, 1.2f);
                MageCore.strike(p, level, e, MageCore.dmg(data, 14));
                for (LivingEntity n : MageCore.foesAt(p, level, e.position(), 3)) {
                    if (n != e) {
                        MageCore.strike(p, level, n, MageCore.dmg(data, 3));
                    }
                }
            }
        }
        return count > 0;
    }

    public static boolean blizzard(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, BLIZZARD_COST);
        Vec3 spot = MageCore.aimSpot(p, level, 30);
        MageCore.zone(p, level, spot, 8, null, 200, 10,
                in -> {
                    for (LivingEntity e : in) {
                        hit(p, level, e, MageCore.dmg(data, 1.8), 1);
                    }
                },
                lv -> {
                    // A storm of snow driven round the field, with a few big flakes streaking down.
                    long tick = lv.getServer().getTickCount();
                    for (int i = 0; i < 14; i++) {
                        double a = lv.getRandom().nextDouble() * Math.PI * 2;
                        double r = Math.sqrt(lv.getRandom().nextDouble()) * 8;
                        lv.sendParticles(ParticleTypes.SNOWFLAKE, spot.x + Math.cos(a) * r, spot.y + 1 + lv.getRandom().nextDouble() * 5,
                                spot.z + Math.sin(a) * r, 1, 0.1, 0.1, 0.1, 0.02);
                    }
                    Fx.swirl(lv, ICE.main(), spot, 7.0, 6.0, tick, 6);
                    Fx.swirl(lv, Fx.dust(ICE.rgb2(), 1.3f), spot, 4.5, 5.0, tick + 9, 4);
                    lv.sendParticles(ICE.alt(), spot.x, spot.y + 4, spot.z, 6, 5, 1, 5, 0.01);
                });
        Circles.ground(level, Element.ICE, spot, 17, 200);
        Fx.shockwave(level, ICE, spot, 8.5, 10);
        Fx.pillar(level, ICE, spot, 7, 2, 10);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.PLAYER_HURT_FREEZE), 1.5f, 0.6f);
        MageCore.sound(level, spot, GateboundSounds.of(SoundEvents.GLASS_BREAK), 1.0f, 0.5f);
        return true;
    }

    public static boolean absoluteZero(ServerPlayer p, ServerLevel level, PlayerData data) {
        MageCore.freeCast(p, Element.ICE, ZERO_COST);
        List<LivingEntity> foes = MageCore.foes(p, level, 20);
        for (LivingEntity e : foes) {
            MageCore.strike(p, level, e, MageCore.dmg(data, 6));
            freeze(level, e, MageConfig.freezeTicks + 40);
        }
        Circles.ground(level, Element.ICE, p.position(), 26, 40);
        Fx.shockwave(level, ICE, p.position(), 20, 16);
        Fx.sphere(level, ParticleTypes.SNOWFLAKE, p.position().add(0, 1, 0), 120, 0.9);
        for (LivingEntity e : foes) {
            Vec3 at = e.position();
            MageCore.after(4 + (int) (at.distanceTo(p.position()) * 0.6), server -> spike(level, at, 2.0, 50));
            Fx.arc(level, ICE.main(), p.getEyePosition().add(0, -0.3, 0), at.add(0, 1, 0), 0.6);
            if (e.distanceToSqr(p) < 400 && foes.indexOf(e) < 10) {
                Circles.onTarget(level, Element.ICE, p.position(), e, 2.4, 20);
            }
        }
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.PLAYER_HURT_FREEZE), 2.0f, 0.5f);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.GLASS_BREAK), 1.5f, 0.5f);
        return true;
    }

    public static boolean frostSovereign(ServerPlayer p, ServerLevel level, PlayerData data) {
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MageConfig.formSeconds * 20, 1));
        MageCore.form(p, Element.ICE, 10, o -> {
            ServerLevel lv = o.level();
            for (LivingEntity e : MageCore.foes(o, lv, 4.5)) {
                chill(lv, e, 1);
            }
        });
        CastFx.transform(p, Element.ICE);
        FormFx.start(p, Element.ICE);
        MageCore.sound(level, p.position(), GateboundSounds.of(SoundEvents.PLAYER_HURT_FREEZE), 1.5f, 0.7f);
        Compat.overlay(p, Component.literal("You become the Frost Sovereign."));
        return true;
    }
}
