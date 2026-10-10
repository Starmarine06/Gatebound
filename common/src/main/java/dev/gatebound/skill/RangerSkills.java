package dev.gatebound.skill;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import dev.gatebound.echo.EchoManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 * Ranger skills and the Quarry system. Most skills loose real arrows from the bow in hand (tagged, never picked up, removed
 * after a short while); a few are instant ray shots. The Ranger marks one Quarry, who takes extra damage and is the target
 * that seeking arrows chase. Damage grows with distance. All state is in memory only and cleared on quit, death and server stop.
 */
public final class RangerSkills {
    public static final int EAGLE_COST = 8;
    public static final int HOMING_COST = 10;
    public static final int PIERCING_COST = 16;
    public static final int QUARRY_COST = 12;
    public static final int ROLL_COST = 10;
    public static final int POISON_COST = 14;
    public static final int SNARE_COST = 16;
    public static final int MULTISHOT_COST = 18;
    public static final int RICOCHET_COST = 22;
    public static final int RAIN_COST = 30;
    public static final int HEADSHOT_COST = 35;
    public static final int TRANCE_COST = 45;
    public static final int BARRAGE_COST = 60;
    public static final int APEX_COST = 80;

    private static final String ARROW_TAG = "gatebound_ranger_arrow";

    /** A delayed server action due at tick `at`. */
    private record Task(long at, Consumer<MinecraftServer> run) {
    }

    /** What the next arrow the Ranger looses will do. Set by casting a bow skill. */
    private enum Kind { HOMING, PIERCING, POISON, SNARE, MULTISHOT, RAIN, RICOCHET, HEADSHOT, BARRAGE }

    /** Every bow skill cast since the last shot (they stack), until they run out. */
    private record Primed(java.util.Set<Kind> kinds, long until, UUID target, net.minecraft.core.BlockPos block) {
    }

    private static final class Tracked {
        final UUID owner;
        final ResourceKey<Level> dim;
        final long born;
        final boolean seek;
        /** Particle trail left behind an imbued arrow, or null. */
        ParticleOptions trail;
        /** Runs where the arrow lands (hit or ground), once. */
        Consumer<Vec3> onLand;
        Vec3 lastPos;
        /** Launch speed; seeking arrows keep it so air drag cannot make a long shot crawl. 0 = unknown. */
        double speed;
        /** A homing arrow locked onto this entity or this block when it was cast. */
        UUID lockEntity;
        net.minecraft.core.BlockPos lockBlock;
        /** The glowing arrow model riding this arrow, and what kind of skill arrow it is (for the impact effect). */
        dev.gatebound.skill.fx.Props.Prop glow;
        String kind;

        Tracked(UUID owner, ResourceKey<Level> dim, long born, boolean seek) {
            this.owner = owner;
            this.dim = dim;
            this.born = born;
            this.seek = seek;
        }
    }

    private static final class Apex {
        long until;
        long nextStrike;

        Apex(long until, long nextStrike) {
            this.until = until;
            this.nextStrike = nextStrike;
        }
    }

    private static final Map<UUID, UUID> QUARRY = new HashMap<>();
    private static final Map<UUID, Long> TRANCE = new HashMap<>();
    private static final Map<UUID, Primed> PRIMED = new HashMap<>();
    private static final Map<UUID, Apex> APEX = new HashMap<>();
    private static final Map<UUID, Tracked> ARROWS = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();

    /** Guards our own damage from re-entering the hurt event. */
    private static boolean applying;

    /** Static holder; not instantiable. */
    private RangerSkills() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Registers Ranger tick, hurt, death and arrow-fired hooks. */
    public static void init() {
        TickEvent.SERVER_POST.register(RangerSkills::tick);
        EntityEvent.LIVING_HURT.register(RangerSkills::onHurt);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity, source);
            return EventResult.pass();
        });
        // While Trance or Apex runs, the Ranger's own bow shots seek too.
        EntityEvent.ADD.register((entity, world) -> {
            if (entity instanceof AbstractArrow arrow && arrow.getOwner() instanceof ServerPlayer p && world instanceof ServerLevel level
                    && !arrow.entityTags().contains(ARROW_TAG) && isRanger(p)) {
                Primed primed = PRIMED.get(p.getUUID());
                if (primed != null && primed.until() > now(p)) {
                    PRIMED.remove(p.getUUID());
                    return fired(p, level, arrow, primed);
                }
                if (!ARROWS.containsKey(arrow.getUUID()) && buffed(p, now(p))) {
                    ARROWS.put(arrow.getUUID(), new Tracked(p.getUUID(), level.dimension(), now(p), true));
                }
            }
            return EventResult.pass();
        });
        PlayerEvent.PLAYER_QUIT.register(player -> clearPlayer(player.getUUID()));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> clearPlayer(player.getUUID()));
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            QUARRY.clear();
            TRANCE.clear();
            PRIMED.clear();
            APEX.clear();
            ARROWS.clear();
            dev.gatebound.skill.fx.RangerFx.reset();
            TASKS.clear();
        });
    }

    /** Drops all of a player's Ranger state. */
    private static void clearPlayer(UUID id) {
        QUARRY.remove(id);
        PRIMED.remove(id);
        TRANCE.remove(id);
        APEX.remove(id);
    }

    /** Clears quarry and tracked arrows when a relevant entity dies. */
    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer player) {
            clearPlayer(player.getUUID());
            return;
        }
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(QUARRY.entrySet())) {
            if (e.getValue().equals(entity.getUUID())) {
                QUARRY.remove(e.getKey());
                ServerPlayer owner = entity.level().getServer().getPlayerList().getPlayer(e.getKey());
                if (owner != null) {
                    ManaState.add(owner, RangerConfig.quarryKillMana);
                    Compat.overlay(owner, Component.literal("Quarry down."));
                }
            }
        }
        if (source.getEntity() instanceof ServerPlayer killer) {
            Apex a = APEX.get(killer.getUUID());
            if (a != null) {
                a.until += 20;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Current game time for the player's level. */
    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    /** True if the player is a Ranger. */
    private static boolean isRanger(ServerPlayer p) {
        return GateboundClass.RANGER.id().equals(PlayerDataStore.of(p).classId);
    }

    /** True while Hunter's Trance is active. */
    private static boolean tranced(ServerPlayer p, long now) {
        return TRANCE.getOrDefault(p.getUUID(), 0L) > now;
    }

    /** True while Apex Predator is active. */
    private static boolean apexed(ServerPlayer p, long now) {
        Apex a = APEX.get(p.getUUID());
        return a != null && a.until > now;
    }

    /** True if any Ranger buff makes arrows seek. */
    private static boolean buffed(ServerPlayer p, long now) {
        return tranced(p, now) || apexed(p, now);
    }

    /** Plays a sound at the player. */
    private static void sound(ServerPlayer p, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, p.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    /** True if the player holds a bow or crossbow. */
    private static boolean hasBow(ServerPlayer p) {
        return p.getMainHandItem().getItem() instanceof BowItem || p.getMainHandItem().getItem() instanceof CrossbowItem;
    }

    /** Called at the start of every skill: Trance makes the cast free. Returns false (and refunds) if a bow is needed but missing. */
    private static boolean begin(ServerPlayer p, int cost, boolean needsBow) {
        if (needsBow && !hasBow(p)) {
            ManaState.add(p, cost);
            Compat.overlay(p, Component.literal("You need a bow in your hand."));
            return false;
        }
        if (tranced(p, now(p))) {
            ManaState.add(p, cost);
        }
        return true;
    }

    /** Fails a cast with a message and no cost. */
    private static boolean miss(ServerPlayer p, int cost, String message) {
        ManaState.add(p, cost * 0.5);
        Compat.overlay(p, Component.literal(message));
        return false;
    }

    /** Skill damage scaled by the player's level and stats. */
    private static float dmg(PlayerData data, double base) {
        return (float) ((base + RangerConfig.damagePerAgility * data.get(Stat.AGILITY)) * (1 + RangerConfig.damagePerLevel * data.level));
    }

    /** True if the entity is the player's marked quarry. */
    private static boolean isQuarry(ServerPlayer p, LivingEntity e, long now) {
        if (e.getUUID().equals(QUARRY.get(p.getUUID()))) {
            return true;
        }
        Apex a = APEX.get(p.getUUID());
        return a != null && a.until > now && e.distanceToSqr(p) <= RangerConfig.apexRadius * RangerConfig.apexRadius;
    }

    /** Distance and quarry multiplier applied to a hit on the target. */
    private static double multiplier(ServerPlayer p, LivingEntity target, long now) {
        double range = 1 + RangerConfig.rangeBonus * Math.min(1.0, p.distanceTo(target) / RangerConfig.rangeFullBlocks);
        return range * (isQuarry(p, target, now) ? 1 + RangerConfig.quarryBonus : 1);
    }

    /** The bell-like hit sound the game plays when you shoot a player, for any target hit from far away. */
    private static void ding(ServerPlayer shooter, LivingEntity target) {
        if (shooter.distanceToSqr(target) >= RangerConfig.dingDistance * RangerConfig.dingDistance) {
            //? if >=26.1 {
            shooter.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND, 0.0f));
            //?} else {
            /*shooter.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                    net.minecraft.network.protocol.game.ClientboundGameEventPacket.ARROW_HIT_PLAYER, 0.0f));
            *///?}
        }
    }

    /** True if the entity is a valid target for Ranger skills. */
    private static boolean valid(LivingEntity e) {
        return e.isAlive() && !(e instanceof Player) && !(e instanceof Mob mob && EchoManager.isEcho(mob));
    }

    /** Valid targets around the player within a radius. */
    private static List<LivingEntity> foes(ServerPlayer p, ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : SkillTargeting.enemiesAround(p, level, radius)) {
            if (valid(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Nearest valid target within a radius. */
    private static LivingEntity nearest(ServerPlayer p, ServerLevel level, double radius) {
        LivingEntity best = null;
        for (LivingEntity e : foes(p, level, radius)) {
            if (best == null || e.distanceToSqr(p) < best.distanceToSqr(p)) {
                best = e;
            }
        }
        return best;
    }

    /** Runs an action for the player after some ticks. */
    private static void after(ServerPlayer p, int ticks, Consumer<MinecraftServer> run) {
        TASKS.add(new Task(now(p) + ticks, run));
    }

    /** Instant hit used by ray shots: bonuses are applied here because there is no arrow for the hurt event to see. */
    private static void rayHit(ServerPlayer p, ServerLevel level, LivingEntity target, float base) {
        ding(p, target);
        applying = true;
        try {
            Compat.setInvulnerableTime(target, 0);
            target.hurtServer(level, level.damageSources().indirectMagic(p, p), (float) (base * multiplier(p, target, now(p))));
        } finally {
            applying = false;
        }
    }

    /** Draws a particle line between two points. */
    private static void line(ServerLevel level, Vec3 a, Vec3 b, ParticleOptions particle) {
        Vec3 step = b.subtract(a);
        int points = (int) Math.max(2, step.length() * 2);
        for (int i = 1; i <= points; i++) {
            Vec3 q = a.add(step.scale(i / (double) points));
            level.sendParticles(particle, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
    }

    /** Centre of an entity's body. */
    private static Vec3 center(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.6, 0);
    }

    /** Looses one real arrow. Damage is the arrow's own hit before the quarry and range bonuses. */
    private static Arrow shoot(ServerPlayer p, ServerLevel level, PlayerData data, Vec3 dir, double speed, float damage, boolean seek) {
        Arrow arrow = new Arrow(level, p, new ItemStack(Items.ARROW), p.getMainHandItem());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(damage / speed);
        arrow.setCritArrow(false);
        arrow.shoot(dir.x, dir.y, dir.z, (float) speed, 0.0f);
        track(p, level, arrow, seek || buffed(p, now(p)));
        level.addFreshEntity(arrow);
        sound(p, level, GateboundSounds.of(SoundEvents.ARROW_SHOOT), 1.0f);
        return arrow;
    }

    /** Fires a tracked arrow from a point in a direction. */
    private static Arrow shootFrom(ServerPlayer p, ServerLevel level, Vec3 from, Vec3 dir, double speed, float damage, boolean seek) {
        Arrow arrow = new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), p.getMainHandItem());
        arrow.setOwner(p);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(damage / speed);
        arrow.shoot(dir.x, dir.y, dir.z, (float) speed, 0.0f);
        track(p, level, arrow, seek);
        level.addFreshEntity(arrow);
        return arrow;
    }

    // ---------------------------------------------------------------- bow skills: imbue the next shot

    /** Adds a skill to what the next arrow will do, keeping whatever was primed before it. */
    private static void addPrime(ServerPlayer p, Kind kind, UUID target, net.minecraft.core.BlockPos block) {
        Primed old = PRIMED.get(p.getUUID());
        java.util.Set<Kind> kinds = java.util.EnumSet.noneOf(Kind.class);
        UUID lockEntity = target;
        net.minecraft.core.BlockPos lockBlock = block;
        if (old != null && old.until() > now(p)) {
            kinds.addAll(old.kinds());
            if (lockEntity == null && lockBlock == null) {
                lockEntity = old.target();
                lockBlock = old.block();
            }
        }
        kinds.add(kind);
        PRIMED.put(p.getUUID(), new Primed(kinds, now(p) + RangerConfig.primedSeconds * 20L, lockEntity, lockBlock));
    }

    /** Readable list of the bow skills currently primed. */
    private static String stackLabel(ServerPlayer p) {
        Primed pr = PRIMED.get(p.getUUID());
        StringBuilder sb = new StringBuilder();
        if (pr != null) {
            for (Kind k : pr.kinds()) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(k.name().charAt(0)).append(k.name().substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /** Primes a bow skill onto the next arrow (costs mana). */
    private static boolean prime(ServerPlayer p, int cost, Kind kind, String label) {
        if (!begin(p, cost, true)) {
            return false;
        }
        addPrime(p, kind, null, null);
        Compat.overlay(p, Component.literal("Your next arrow is imbued: " + stackLabel(p)));
        dev.gatebound.skill.fx.RangerFx.primed(p);
        sound(p, p.level(), GateboundSounds.of(SoundEvents.ENCHANTMENT_TABLE_USE), 1.2f);
        return true;
    }

    /** Trail particle for the primed skills. */
    private static ParticleOptions trailOf(java.util.Set<Kind> kinds) {
        if (kinds.contains(Kind.POISON)) {
            return ParticleTypes.ITEM_SLIME;
        } else if (kinds.contains(Kind.SNARE)) {
            return ParticleTypes.SNOWFLAKE;
        } else if (kinds.contains(Kind.RAIN)) {
            return ParticleTypes.CLOUD;
        } else if (kinds.contains(Kind.MULTISHOT)) {
            return ParticleTypes.CRIT;
        }
        return ParticleTypes.END_ROD;
    }

    /** The Ranger loosed a shot while bow skills were primed: the arrow becomes them all, or one ray skill fires instead of the arrow. */
    private static EventResult fired(ServerPlayer p, ServerLevel level, AbstractArrow arrow, Primed primed) {
        PlayerData data = PlayerDataStore.of(p);
        java.util.Set<Kind> kinds = primed.kinds();
        if (kinds.contains(Kind.BARRAGE)) {
            fireBarrage(p, level, data);
        } else if (kinds.contains(Kind.RICOCHET)) {
            fireRicochet(p, level, data);
        } else if (kinds.contains(Kind.HEADSHOT)) {
            fireHeadshot(p, level, data);
        } else if (kinds.contains(Kind.PIERCING)) {
            firePiercing(p, level, data);
        } else {
            imbue(p, level, data, arrow, primed);
            return EventResult.pass();
        }
        return EventResult.interruptFalse(); // the shot became the skill; no arrow flies
    }

    /** Poison and snare ride on the arrow as potion effects (arrows divide effect time by 8). */
    private static void applyEffects(Arrow a, java.util.Set<Kind> kinds, PlayerData data) {
        if (kinds.contains(Kind.POISON)) {
            a.addEffect(new MobEffectInstance(MobEffects.POISON, (5 + data.level / 10) * 20 * 8, 1));
        }
        if (kinds.contains(Kind.SNARE)) {
            a.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 3 * 20 * 8, 10));
        }
    }

    /** Turns an arrow into the primed skills (damage, effects, trail, multishot, rain). */
    private static void imbue(ServerPlayer p, ServerLevel level, PlayerData data, AbstractArrow arrow, Primed primed) {
        java.util.Set<Kind> kinds = primed.kinds();
        Vec3 v = arrow.getDeltaMovement();
        double speed = Math.max(v.length(), 1.0);
        double power = Math.min(1.0, speed / 3.0);
        float base = 0;
        for (Kind kind : kinds) {
            base = Math.max(base, switch (kind) {
                case POISON, SNARE -> dmg(data, 5);
                case MULTISHOT -> dmg(data, 4);
                case RAIN -> dmg(data, 3);
                default -> dmg(data, 7);
            });
        }
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(base * (0.4 + 0.6 * power) / speed);
        arrow.addTag(ARROW_TAG);
        arrow.setGlowingTag(true);
        boolean homing = kinds.contains(Kind.HOMING);
        boolean seeks = homing || buffed(p, now(p));
        if (seeks) {
            arrow.setNoGravity(true);
        }
        Tracked t = new Tracked(p.getUUID(), level.dimension(), now(p), seeks);
        t.speed = speed;
        t.lockEntity = homing ? primed.target() : null;
        t.lockBlock = homing ? primed.block() : null;
        t.trail = trailOf(kinds);
        t.lastPos = arrow.position();
        t.glow = dev.gatebound.skill.fx.RangerFx.glow(level, arrow);
        t.kind = kinds.contains(Kind.POISON) ? "poison" : kinds.contains(Kind.SNARE) ? "snare"
                : kinds.iterator().next().name().toLowerCase();
        ARROWS.put(arrow.getUUID(), t);
        if (arrow instanceof Arrow a) {
            applyEffects(a, kinds, data);
        }
        if (kinds.contains(Kind.MULTISHOT)) {
            for (int i = -2; i <= 2; i++) {
                if (i == 0) {
                    continue;
                }
                double rad = Math.toRadians(i * 9.0);
                Vec3 dir = new Vec3(v.x * Math.cos(rad) - v.z * Math.sin(rad), v.y, v.x * Math.sin(rad) + v.z * Math.cos(rad));
                Arrow extra = shootFrom(p, level, arrow.position(), dir.normalize(), speed, base * (float) (0.4 + 0.6 * power),
                        seeks);
                Tracked et = ARROWS.get(extra.getUUID());
                et.trail = trailOf(kinds);
                et.kind = t.kind;
                applyEffects(extra, kinds, data);
                extra.setGlowingTag(true);
            }
        }
        if (kinds.contains(Kind.RAIN)) {
            t.onLand = spot -> {
                dev.gatebound.skill.fx.RangerFx.rain(level, spot, RangerConfig.rainRingRadius * 2.2, RangerConfig.rainWaves * 3 + 24);
                rainAt(p, spot, dmg(data, 3));
            };
        }
        sound(p, level, GateboundSounds.of(SoundEvents.ENCHANTMENT_TABLE_USE), 1.6f);
    }

    /** Arrows fall from low over a ring all around the spot, 360 degrees, and converge on it. */
    private static void rainAt(ServerPlayer p, Vec3 spot, float damage) {
        for (int wave = 0; wave < RangerConfig.rainWaves; wave++) {
            after(p, wave * 3, server -> {
                if (!p.isAlive()) {
                    return;
                }
                ServerLevel l = p.level();
                for (int k = 0; k < RangerConfig.rainPerWave; k++) {
                    double ang = l.getRandom().nextDouble() * Math.PI * 2;
                    double r = RangerConfig.rainRingRadius * (0.7 + 0.3 * l.getRandom().nextDouble());
                    Vec3 from = new Vec3(spot.x + Math.cos(ang) * r, spot.y + RangerConfig.rainHeight + l.getRandom().nextDouble() * 2, spot.z + Math.sin(ang) * r);
                    Vec3 aim = new Vec3(spot.x + (l.getRandom().nextDouble() - 0.5) * RangerConfig.rainSpread, spot.y + 0.4,
                            spot.z + (l.getRandom().nextDouble() - 0.5) * RangerConfig.rainSpread);
                    Arrow a = shootFrom(p, l, from, aim.subtract(from).normalize(), 1.9, damage, false);
                    ARROWS.get(a.getUUID()).trail = ParticleTypes.CRIT;
                }
                l.sendParticles(ParticleTypes.CRIT, spot.x, spot.y + 0.2, spot.z, 6, 2, 0.1, 2, 0.1);
            });
        }
        sound(p, p.level(), GateboundSounds.of(SoundEvents.ARROW_SHOOT), 0.6f);
    }

    // ---------------------------------------------------------------- aiming: faster draw, slowed time, no damage

    private static java.lang.reflect.Field useRemaining;
    private static boolean useRemainingFailed;

    /** Called every tick for a Ranger who is drawing a bow (server and client): shortens the draw by speeding up its clock. */
    public static void boostDraw(LivingEntity e) {
        if (useRemainingFailed || RangerConfig.drawExtraTicks <= 0 || !e.isUsingItem()
                || !(e.getUseItem().getItem() instanceof BowItem || e.getUseItem().getItem() instanceof CrossbowItem)) {
            return;
        }
        try {
            if (useRemaining == null) {
                useRemaining = LivingEntity.class.getDeclaredField("useItemRemaining");
                useRemaining.setAccessible(true);
            }
            if (e.getTicksUsingItem() < 40) {
                useRemaining.setInt(e, useRemaining.getInt(e) - RangerConfig.drawExtraTicks);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            useRemainingFailed = true;
        }
    }

    /** True if the player is drawing a bow. */
    private static boolean aiming(ServerPlayer p) {
        return p.isUsingItem() && (p.getUseItem().getItem() instanceof BowItem || p.getUseItem().getItem() instanceof CrossbowItem) && isRanger(p);
    }

    /** "Time slows" for the drawing Ranger: nearby enemies crawl and enemy missiles hang in the air. */
    private static void bulletTime(ServerPlayer p) {
        ServerLevel level = p.level();
        double r = RangerConfig.bulletRadius;
        for (LivingEntity e : foes(p, level, r)) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 3, RangerConfig.bulletSlowAmp, false, false, false));
        }
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(r), x -> x instanceof Projectile pr && !(pr.getOwner() instanceof Player))) {
            Vec3 v = e.getDeltaMovement();
            if (v.lengthSqr() > 0.01) {
                e.setDeltaMovement(v.scale(0.5));
                e.needsSync = true;
            }
        }
    }

    /** Starts tracking an arrow for homing and landing effects. */
    private static void track(ServerPlayer p, ServerLevel level, Arrow arrow, boolean seek) {
        arrow.addTag(ARROW_TAG);
        if (seek) {
            arrow.setNoGravity(true);
        }
        Tracked tr = new Tracked(p.getUUID(), level.dimension(), now(p), seek);
        tr.glow = dev.gatebound.skill.fx.RangerFx.glow(level, arrow);
        ARROWS.put(arrow.getUUID(), tr);
    }

    /** Look direction rotated by a yaw offset. */
    private static Vec3 rotated(ServerPlayer p, float yawOffset) {
        return Vec3.directionFromRotation(p.getXRot(), p.getYRot() + yawOffset);
    }

    // ---------------------------------------------------------------- events

    /** Hurt hook for Ranger damage bonuses. */
    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        // A Ranger who is drawing a bow cannot be hurt (only things that bypass invulnerability get through).
        if (victim instanceof ServerPlayer sp && amount > 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && aiming(sp)) {
            return EventResult.interruptFalse();
        }
        if (applying || amount <= 0 || victim instanceof Player || !(victim.level() instanceof ServerLevel level)
                || !(source.getDirectEntity() instanceof AbstractArrow arrow) || !(arrow.getOwner() instanceof ServerPlayer p)
                || (victim instanceof Mob mob && EchoManager.isEcho(mob))) {
            return EventResult.pass();
        }
        long now = level.getServer().getTickCount();
        Tracked hitBy = ARROWS.get(arrow.getUUID());
        if (hitBy != null && hitBy.kind != null) {
            dev.gatebound.skill.fx.RangerFx.impact(level, victim, hitBy.kind);
            hitBy.kind = null;
        }
        ding(p, victim);
        // Every player's arrows hit harder with Strength and with distance; quarry and Agility bonuses are the Ranger's.
        PlayerData pd = PlayerDataStore.of(p);
        double mult = multiplier(p, victim, now) * (1 + RangerConfig.strengthBowBonus * pd.get(Stat.STRENGTH));
        if (isRanger(p) && !arrow.entityTags().contains(ARROW_TAG)) {
            mult *= 1 + RangerConfig.agilityShotBonus * pd.get(Stat.AGILITY); // own bow shots
        }
        float extra = (float) (amount * (mult - 1));
        if (extra > 0.4f) {
            // A second hit one tick later: bonus damage inside the hurt event would be eaten by invulnerability frames.
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
        return EventResult.pass();
    }

    /** Per-tick driver for tasks, arrows, quarry and Apex. */
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
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isAlive() && p.isUsingItem() && isRanger(p)) {
                boostDraw(p);
                if (RangerConfig.bulletTime && aiming(p)) {
                    bulletTime(p);
                }
            }
        }
        if (!ARROWS.isEmpty()) {
            tickArrows(server, now);
        }
        if (now % 20 == 0) {
            keepQuarry(server);
            TRANCE.values().removeIf(until -> until <= now);
        }
        if (!APEX.isEmpty()) {
            tickApex(server, now);
        }
    }

    /** Looks a player up by UUID. */
    private static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && p.isAlive() ? p : null;
    }

    /** The player's current quarry entity, if loaded. */
    private static LivingEntity quarryOf(MinecraftServer server, ServerPlayer p) {
        UUID id = QUARRY.get(p.getUUID());
        if (id == null) {
            return null;
        }
        Entity e = p.level().getEntity(id);
        return e instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    /** Drops quarry marks whose target is gone. */
    private static void keepQuarry(MinecraftServer server) {
        for (UUID owner : new ArrayList<>(QUARRY.keySet())) {
            ServerPlayer p = playerOf(server, owner);
            if (p == null) {
                QUARRY.remove(owner);
                continue;
            }
            LivingEntity q = quarryOf(server, p);
            if (q == null) {
                QUARRY.remove(owner);
            } else {
                q.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
            }
        }
    }

    /** Per-tick upkeep of tracked arrows (homing, trails, landing). */
    private static void tickArrows(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(ARROWS.keySet())) {
            Tracked t = ARROWS.get(id);
            ServerLevel level = server.getLevel(t.dim);
            Entity e = level == null ? null : level.getEntity(id);
            if (!(e instanceof AbstractArrow arrow) || !arrow.isAlive()) {
                land(t);
                drop(id, t);
                continue;
            }
            long age = now - t.born;
            Vec3 v = arrow.getDeltaMovement();
            boolean flying = v.lengthSqr() > 0.25;
            boolean skill = arrow.entityTags().contains(ARROW_TAG);
            if (t.glow != null) {
                if (flying) {
                    if (age % 2 == 0) {
                        dev.gatebound.skill.fx.RangerFx.moveGlow(t.glow, arrow);
                    }
                } else {
                    dev.gatebound.skill.fx.RangerFx.endGlow(t.glow);
                    t.glow = null;
                }
            }
            if (flying) {
                t.lastPos = arrow.position();
                if (t.trail != null) {
                    level.sendParticles(t.trail, arrow.getX(), arrow.getY(), arrow.getZ(), 2, 0.05, 0.05, 0.05, 0.0);
                }
            } else if (t.onLand != null) {
                t.lastPos = arrow.position();
                land(t);
            }
            if (skill && (age > RangerConfig.arrowLifeTicks || (!flying && age > RangerConfig.arrowGroundTicks))) {
                arrow.discard();
                drop(id, t);
                continue;
            }
            if (!t.seek || !flying) {
                if (!flying && !skill && age > 1200) {
                    drop(id, t);
                }
                continue;
            }
            double flightSpeed = t.speed > 0 ? t.speed : Math.max(v.length(), 1.5);
            // A homing arrow locked on at cast time chases that entity (or button / target) at any distance.
            Vec3 aim = null;
            if (t.lockEntity != null) {
                if (level.getEntity(t.lockEntity) instanceof LivingEntity locked && locked.isAlive()) {
                    aim = center(locked);
                }
            } else if (t.lockBlock != null) {
                aim = Vec3.atCenterOf(t.lockBlock);
            }
            if (aim != null) {
                Vec3 want = aim.subtract(arrow.position()).normalize();
                Vec3 nv = v.normalize().scale(1 - RangerConfig.homingLock).add(want.scale(RangerConfig.homingLock)).normalize().scale(flightSpeed);
                arrow.setDeltaMovement(nv);
                arrow.needsSync = true;
                continue;
            }
            ServerPlayer owner = playerOf(server, t.owner);
            LivingEntity target = null;
            if (owner != null) {
                target = quarryOf(server, owner);
                if (target != null && target.distanceToSqr(arrow) > 48 * 48) {
                    target = null;
                }
            }
            if (target == null) {
                double best = RangerConfig.homingRange * RangerConfig.homingRange;
                for (Entity c : level.getEntities(arrow, arrow.getBoundingBox().inflate(RangerConfig.homingRange),
                        x -> x instanceof net.minecraft.world.entity.monster.Enemy && x instanceof LivingEntity le && valid(le))) {
                    double d = c.distanceToSqr(arrow);
                    if (d < best) {
                        best = d;
                        target = (LivingEntity) c;
                    }
                }
            }
            if (target == null) {
                if (age > 20) {
                    arrow.setNoGravity(false); // nothing to chase: fall like a normal arrow instead of hanging
                }
                continue;
            }
            Vec3 want = center(target).subtract(arrow.position()).normalize();
            Vec3 nv = v.normalize().scale(1 - RangerConfig.homing).add(want.scale(RangerConfig.homing)).normalize().scale(flightSpeed);
            arrow.setDeltaMovement(nv);
            arrow.needsSync = true;
        }
    }

    /** Stops tracking an arrow and ends its glow. */
    private static void drop(UUID id, Tracked t) {
        if (t.glow != null) {
            dev.gatebound.skill.fx.RangerFx.endGlow(t.glow);
            t.glow = null;
        }
        ARROWS.remove(id);
    }

    /** Runs an arrow's landing effect and stops tracking it. */
    private static void land(Tracked t) {
        if (t.onLand != null && t.lastPos != null) {
            Consumer<Vec3> run = t.onLand;
            t.onLand = null;
            run.accept(t.lastPos);
        }
    }

    /** Per-tick upkeep of Apex Predator (strikes, mana drain, end). */
    private static void tickApex(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(APEX.keySet())) {
            Apex a = APEX.get(id);
            ServerPlayer p = playerOf(server, id);
            if (p == null || a.until <= now) {
                APEX.remove(id);
                continue;
            }
            if (now < a.nextStrike) {
                continue;
            }
            a.nextStrike = now + 20;
            // Upkeep: the longer it runs the more mana it costs, and at zero it stops completely.
            double left = ManaState.get(p) - RangerConfig.apexManaPerSecond;
            ManaState.set(p, Math.max(0, left));
            SkillHandler.sendMana(p, true);
            if (left <= 0) {
                APEX.remove(id);
                p.removeEffect(MobEffects.SPEED);
                p.removeEffect(MobEffects.STRENGTH);
                dev.gatebound.skill.fx.FxState.clear(p, "form:ranger");
                dev.gatebound.skill.fx.FxState.clear(p, "circle:ranger:22");
                Compat.overlay(p, Component.literal("Out of mana: Apex Predator ends."));
                continue;
            }
            ServerLevel level = p.level();
            PlayerData data = PlayerDataStore.of(p);
            int strikes = 0;
            for (LivingEntity e : foes(p, level, RangerConfig.apexRadius)) {
                e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
                if (strikes++ < 6) {
                    dev.gatebound.skill.fx.RangerFx.strike(level, e);
                }
                rayHit(p, level, e, dmg(data, 3));
            }
            sound(p, level, GateboundSounds.of(SoundEvents.ARROW_HIT_PLAYER), 0.8f);
        }
    }

    // ---------------------------------------------------------------- skills

    /** Eagle Eye: nearby enemies glow. */
    public static boolean eagleEye(ServerPlayer p, ServerLevel level, PlayerData data) {
        begin(p, EAGLE_COST, false);
        List<LivingEntity> seen = foes(p, level, 24);
        for (LivingEntity e : seen) {
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 160, 0));
        }
        dev.gatebound.skill.fx.RangerFx.eagle(p, seen);
        sound(p, level, GateboundSounds.of(SoundEvents.ARROW_HIT_PLAYER), 0.7f);
        return true;
    }

    /** Blocks an arrow does something to when it hits them. */
    private static boolean shootableBlock(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.WOODEN_BUTTONS) || s.is(net.minecraft.world.level.block.Blocks.TARGET)
                || s.is(net.minecraft.world.level.block.Blocks.BELL);
    }

    /**
     * Homing Shot: the cast raycasts along your aim and locks onto the first thing an arrow can actually affect: a mob or
     * player, or a wooden button, target block or bell. Walls in the way block the lock. Nothing valid, nothing is primed.
     */
    public static boolean homingShot(ServerPlayer p, ServerLevel level, PlayerData data) {
        if (!begin(p, HOMING_COST, true)) {
            return false;
        }
        Vec3 from = p.getEyePosition();
        Vec3 end = from.add(p.getLookAngle().scale(RangerConfig.homingLockRange));
        BlockHitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double wallDist = wall.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : from.distanceTo(wall.getLocation());

        UUID entityId = null;
        net.minecraft.core.BlockPos blockPos = null;
        String name = "";
        double best = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, p.getBoundingBox().expandTowards(end.subtract(from)).inflate(1.0),
                x -> x instanceof LivingEntity le && le.isAlive() && !le.isSpectator() && le.isPickable()
                        && !(x instanceof Mob m && EchoManager.isEcho(m)))) {
            java.util.Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(from, end);
            if (hit.isPresent()) {
                double d = from.distanceTo(hit.get());
                if (d < best && d <= wallDist + 0.5) {
                    best = d;
                    entityId = e.getUUID();
                    name = e.getName().getString();
                }
            }
        }
        // Buttons and the like have no collision, so look for them with the outline ray, but never through a wall.
        BlockHitResult outline = level.clip(new ClipContext(from, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (outline.getType() != HitResult.Type.MISS && shootableBlock(level.getBlockState(outline.getBlockPos()))) {
            double d = from.distanceTo(outline.getLocation());
            if (d < best && d <= wallDist + 0.5) {
                best = d;
                entityId = null;
                blockPos = outline.getBlockPos();
                name = level.getBlockState(blockPos).getBlock().getName().getString();
            }
        }
        if (entityId == null && blockPos == null) {
            ManaState.add(p, HOMING_COST);
            Compat.overlay(p, Component.literal("Nothing to lock onto: aim at a mob, a player, a button or a target."));
            return false;
        }
        addPrime(p, Kind.HOMING, entityId, blockPos);
        Compat.overlay(p, Component.literal("Locked on: " + name + ". Loose an arrow."));
        dev.gatebound.skill.fx.RangerFx.lock(p, entityId == null ? null : level.getEntity(entityId), blockPos);
        sound(p, level, GateboundSounds.of(SoundEvents.ENCHANTMENT_TABLE_USE), 1.2f);
        return true;
    }

    /** Piercing Shot: next shot becomes a ray through up to three enemies. */
    public static boolean piercingShot(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, PIERCING_COST, Kind.PIERCING, "Piercing Shot");
    }

    /** Fires the piercing ray. */
    private static void firePiercing(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 40, 3, null);
        dev.gatebound.skill.fx.RangerFx.pierce(p, hits);
        sound(p, level, GateboundSounds.of(SoundEvents.ARROW_SHOOT), 0.8f);
        for (LivingEntity e : hits) {
            if (valid(e)) {
                rayHit(p, level, e, dmg(data, 6));
            }
        }
    }

    /** Hunter's Quarry: marks the target; arrows hit it harder. */
    public static boolean huntersQuarry(ServerPlayer p, ServerLevel level, PlayerData data) {
        begin(p, QUARRY_COST, false);
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 48, 1, null);
        if (hits.isEmpty() || !valid(hits.get(0))) {
            return miss(p, QUARRY_COST, "No target to mark.");
        }
        LivingEntity q = hits.get(0);
        QUARRY.put(p.getUUID(), q.getUUID());
        q.addEffect(new MobEffectInstance(MobEffects.GLOWING, RangerConfig.quarryGlowSeconds * 20, 0, false, false));
        dev.gatebound.skill.fx.RangerFx.quarry(p, q, RangerConfig.quarryGlowSeconds * 20);
        sound(p, level, GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), 1.4f);
        return true;
    }

    /** Evasive Roll: backward dash with brief invulnerability that looses a shot at the nearest enemy. */
    public static boolean evasiveRoll(ServerPlayer p, ServerLevel level, PlayerData data) {
        begin(p, ROLL_COST, false);
        Vec3 look = p.getLookAngle();
        Vec3 back = new Vec3(-look.x, 0, -look.z);
        back = back.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, -1) : back.normalize();
        p.setDeltaMovement(back.x * 1.5, 0.35, back.z * 1.5);
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        PlayerCombat.grantIFrames(p, 8);
        dev.gatebound.skill.fx.RangerFx.roll(p);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 1.4f);
        LivingEntity target = nearest(p, level, 24);
        if (target != null && hasBow(p)) {
            Vec3 dir = center(target).subtract(p.getEyePosition()).normalize();
            shoot(p, level, data, dir, RangerConfig.arrowSpeed, dmg(data, 4), false);
        }
        return true;
    }

    /** Poison Arrow: imbues the next arrow with poison. */
    public static boolean poisonArrow(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, POISON_COST, Kind.POISON, "Poison Arrow");
    }

    /** Snare Arrow: next arrow pins its target in place. */
    public static boolean snareArrow(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, SNARE_COST, Kind.SNARE, "Snare Arrow");
    }

    /** Multishot: next shot splits into a fan of arrows. */
    public static boolean multishot(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, MULTISHOT_COST, Kind.MULTISHOT, "Multishot");
    }

    /** Ricochet: next shot is a ray that bounces between enemies. */
    public static boolean ricochet(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, RICOCHET_COST, Kind.RICOCHET, "Ricochet");
    }

    /** Fires the ricochet ray. */
    private static void fireRicochet(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> first = SkillTargeting.ray(p, level, 40, 1, null);
        sound(p, level, GateboundSounds.of(SoundEvents.ARROW_SHOOT), 1.5f);
        if (first.isEmpty() || !valid(first.get(0))) {
            return;
        }
        Set<UUID> hit = new HashSet<>();
        LivingEntity cur = first.get(0);
        List<Vec3> bounces = new ArrayList<>();
        float damage = dmg(data, 7);
        for (int bounce = 0; bounce < 4 && cur != null; bounce++) {
            hit.add(cur.getUUID());
            bounces.add(center(cur));
            rayHit(p, level, cur, damage);
            damage *= 0.85f;
            LivingEntity next = null;
            for (Entity c : level.getEntities(cur, cur.getBoundingBox().inflate(10),
                    x -> x instanceof net.minecraft.world.entity.monster.Enemy && x instanceof LivingEntity le && valid(le))) {
                if (!hit.contains(c.getUUID()) && (next == null || c.distanceToSqr(cur) < next.distanceToSqr(cur))) {
                    next = (LivingEntity) c;
                }
            }
            cur = next;
        }
        dev.gatebound.skill.fx.RangerFx.ricochet(p, bounces);
    }

    /** Rain of Arrows: where the next arrow lands, arrows rain in. */
    public static boolean rainOfArrows(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, RAIN_COST, Kind.RAIN, "Rain of Arrows (rains down where it lands)");
    }

    /** Headshot: next shot is a devastating ray, finishing wounded non-bosses. */
    public static boolean headshot(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, HEADSHOT_COST, Kind.HEADSHOT, "Headshot");
    }

    /** Fires the headshot ray. */
    private static void fireHeadshot(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = SkillTargeting.ray(p, level, 48, 1, null);
        sound(p, level, GateboundSounds.of(SoundEvents.CROSSBOW_SHOOT), 0.8f);
        if (hits.isEmpty() || !valid(hits.get(0))) {
            return;
        }
        LivingEntity t = hits.get(0);
        float base = dmg(data, 10) * (float) (3 + data.level / 30.0);
        if (isQuarry(p, t, now(p))) {
            base *= 2;
        }
        if (t.getHealth() < t.getMaxHealth() * 0.2f && t.getMaxHealth() <= RangerConfig.headshotBossHealth) {
            base = Math.max(base, t.getHealth() + 1);
        }
        rayHit(p, level, t, base);
        dev.gatebound.skill.fx.RangerFx.headshot(p, t);
    }

    /** Hunter's Trance: skills cost no mana, faster, arrows seek. */
    public static boolean huntersTrance(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (RangerConfig.tranceBaseSeconds + data.level / 10) * 20;
        TRANCE.put(p.getUUID(), now(p) + ticks);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
        dev.gatebound.skill.fx.RangerFx.trance(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), 1.6f);
        return true;
    }

    /** Arrow Barrage: next shot unleashes seeking arrows over every enemy nearby. */
    public static boolean arrowBarrage(ServerPlayer p, ServerLevel level, PlayerData data) {
        return prime(p, BARRAGE_COST, Kind.BARRAGE, "Arrow Barrage");
    }

    /** Fires the barrage volley. */
    private static void fireBarrage(ServerPlayer p, ServerLevel level, PlayerData data) {
        float damage = dmg(data, 4);
        dev.gatebound.skill.fx.RangerFx.barrage(p, RangerConfig.barrageArrows);
        for (int i = 0; i < RangerConfig.barrageArrows; i++) {
            after(p, i, server -> {
                if (!p.isAlive()) {
                    return;
                }
                ServerLevel l = p.level();
                List<LivingEntity> list = foes(p, l, RangerConfig.barrageRadius);
                Vec3 dir;
                if (list.isEmpty()) {
                    dir = rotated(p, (l.getRandom().nextFloat() - 0.5f) * 30f);
                } else {
                    LivingEntity t = list.get(l.getRandom().nextInt(list.size()));
                    dir = center(t).subtract(p.getEyePosition()).normalize();
                }
                shootFrom(p, l, p.getEyePosition().add(0, 0.2, 0), dir, RangerConfig.arrowSpeed, damage, true);
                sound(p, l, GateboundSounds.of(SoundEvents.ARROW_SHOOT), 1.2f);
            });
        }
    }

    /** Apex Predator: ultimate; spectral arrows strike every enemy each second, drains mana until empty. */
    public static boolean apexPredator(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (RangerConfig.apexBaseSeconds + data.level / 10) * 20;
        long now = now(p);
        APEX.put(p.getUUID(), new Apex(now + ticks, now + 20));
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 2));
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 1));
        for (LivingEntity e : foes(p, level, RangerConfig.apexRadius)) {
            e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
        }
        dev.gatebound.skill.fx.RangerFx.apex(p, ticks);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDER_DRAGON_GROWL), 1.2f);
        return true;
    }
}
