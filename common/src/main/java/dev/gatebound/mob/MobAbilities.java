package dev.gatebound.mob;

import dev.gatebound.compat.Compat;
import dev.gatebound.skill.fx.Fx;
import dev.gatebound.skill.fx.Tasks;
import dev.gatebound.sound.GateboundSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Special attacks of the Spire's own monsters. Every custom mob runs an {@link AbilityGoal}: now and then it picks one of
 * the attacks in its kit, stops and telegraphs it (a marked area, a line, a gathering of particles and a sound), and only
 * then lets it land, so each one can be dodged by moving or jumping. Guardians get faster and add attacks as they are
 * hurt. Ability damage is magic damage dealt by the mob (it ignores armour and cannot be parried, only avoided); its size
 * follows the monster's rank, floor and the gate level-gap scaling stored in the {@code gb_pow} tag.
 */
public final class MobAbilities {
    public static final String MINION = "gatebound_minion";
    private static final String POW = "gb_pow:";
    private static final int[] COLORS = {0x9FD08A, 0x6BC84A, 0x4AB0F0, 0xFF7A1F, 0xFF3A3A, 0xB05CFF};

    /** Static holder; not instantiable. */
    private MobAbilities() {
    }

    // ------------------------------------------------------------------ model

    /** A running cast: who casts, where it was aimed when it began and what it hits for. */
    private static final class Cast {
        final ServerLevel level;
        final Mob mob;
        final int band;
        final boolean boss;
        final double power;
        final int rgb;
        final Holder<MobEffect> effect;
        ServerPlayer target;
        Vec3 aim = Vec3.ZERO;
        Vec3 dir = new Vec3(0, 0, 1);
        Vec3 dest;
        List<Vec3> spots = new ArrayList<>();
        Mob ally;
        int phase;

        Cast(ServerLevel level, Mob mob, int band, boolean boss, double power, Holder<MobEffect> effect) {
            this.level = level;
            this.mob = mob;
            this.band = band;
            this.boss = boss;
            this.power = power;
            this.rgb = COLORS[Math.max(0, Math.min(COLORS.length - 1, band))];
            this.effect = effect;
        }
    }

    private interface Tele {
        void run(Cast c, int t, int windup);
    }

    private interface Exec {
        void run(Cast c);
    }

    private record Ab(String name, int cooldown, double min, double max, int windup, int weight, int minPhase,
                      Predicate<Cast> usable, SoundEvent cue, Tele tele, Exec exec) {
        Ab phase(int p) {
            return new Ab(name, cooldown, min, max, windup, weight, p, usable, cue, tele, exec);
        }

        Ab when(Predicate<Cast> u) {
            return new Ab(name, cooldown, min, max, windup, weight, minPhase, u, cue, tele, exec);
        }

        Ab weight(int w) {
            return new Ab(name, cooldown, min, max, windup, w, minPhase, usable, cue, tele, exec);
        }
    }

    // ------------------------------------------------------------------ install

    /** Gives a freshly added custom Spire mob its attack goal. */
    public static void install(Entity entity) {
        if (!(entity instanceof Mob mob) || entity.level().isClientSide() || !SpireMobs.isCustom(mob.getType())) {
            return;
        }
        boolean has = Compat.goals(mob).getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof AbilityGoal);
        if (!has) {
            List<Ab> kit = kit(SpireMobs.idOf(mob.getType()));
            if (!kit.isEmpty()) {
                Compat.goals(mob).addGoal(0, new AbilityGoal(mob, kit));
            }
        }
    }

    // ------------------------------------------------------------------ goal

    private static final class AbilityGoal extends Goal {
        private final Mob mob;
        private final List<Ab> kit;
        private final long[] ready;
        private final boolean boss;
        private final int band;
        private long nextAt;
        private Ab current;
        private Cast cast;
        private int timer;
        private int lastPhase;

        AbilityGoal(Mob mob, List<Ab> kit) {
            this.mob = mob;
            this.kit = kit;
            this.ready = new long[kit.size()];
            this.boss = SpireMobs.isBoss(mob.getType());
            this.band = SpireMobs.bandOf(mob.getType());
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private long now() {
            return mob.level().getServer().getTickCount();
        }

        private ServerPlayer target() {
            return mob.getTarget() instanceof ServerPlayer p && p.isAlive() && !p.isSpectator() ? p : null;
        }

        private int phase() {
            if (!boss) {
                return 0;
            }
            float f = mob.getHealth() / mob.getMaxHealth();
            return f < 0.33f ? 2 : f < 0.66f ? 1 : 0;
        }

        private Cast newCast(ServerLevel level, ServerPlayer t) {
            double pow = 3.0 + 1.6 * band;
            for (String tag : mob.entityTags()) {
                if (tag.startsWith(POW)) {
                    try {
                        pow *= Integer.parseInt(tag.substring(POW.length())) / 100.0;
                    } catch (NumberFormatException ignored) {
                        // a malformed tag just leaves the base power
                    }
                }
            }
            Cast c = new Cast(level, mob, band, boss, pow * (boss ? 1.35 : 1.0), SpireMobs.effectOf(mob.getType()));
            c.target = t;
            c.phase = phase();
            c.aim = t.position();
            Vec3 d = t.position().subtract(mob.position()).multiply(1, 0, 1);
            c.dir = d.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : d.normalize();
            return c;
        }

        private Ab choose(ServerLevel level, ServerPlayer t) {
            double dist = mob.distanceTo(t);
            int ph = phase();
            Cast probe = newCast(level, t);
            int total = 0;
            int[] w = new int[kit.size()];
            for (int i = 0; i < kit.size(); i++) {
                Ab a = kit.get(i);
                if (ready[i] > now() || ph < a.minPhase() || dist < a.min() || dist > a.max()
                        || (a.max() > 6 && !mob.getSensing().hasLineOfSight(t))
                        || (a.usable() != null && !a.usable().test(probe))) {
                    continue;
                }
                w[i] = a.weight();
                total += w[i];
            }
            if (total <= 0) {
                return null;
            }
            int roll = mob.getRandom().nextInt(total);
            for (int i = 0; i < kit.size(); i++) {
                roll -= w[i];
                if (w[i] > 0 && roll < 0) {
                    return kit.get(i);
                }
            }
            return null;
        }

        @Override
        public boolean canUse() {
            ServerPlayer t = target();
            if (t == null || !(mob.level() instanceof ServerLevel level) || now() < nextAt) {
                return false;
            }
            Ab a = choose(level, t);
            if (a == null) {
                nextAt = now() + 20;
                return false;
            }
            current = a;
            cast = newCast(level, t);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return current != null && cast != null && mob.isAlive();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            timer = 0;
            mob.getNavigation().stop();
            int ph = phase();
            if (ph > lastPhase) {
                lastPhase = ph;
                enrage(ph);
            }
            cast.level.playSound(null, mob.blockPosition(), current.cue(), SoundSource.HOSTILE, 1.1f, 0.8f + mob.getRandom().nextFloat() * 0.3f);
            // Say what is coming, so a player learns the moves.
            for (ServerPlayer p : cast.level.getEntitiesOfClass(ServerPlayer.class, mob.getBoundingBox().inflate(24), x -> x.isAlive() && !x.isSpectator())) {
                Compat.overlay(p, Component.literal(current.name()).withStyle(net.minecraft.ChatFormatting.RED));
            }
            if (current.tele() != null) {
                // Allies and spots are chosen when the cast begins, so the telegraph shows the final target.
                current.tele().run(cast, 0, current.windup());
            }
        }

        @Override
        public void tick() {
            ServerPlayer t = target();
            if (t != null) {
                mob.getLookControl().setLookAt(t, 60f, 60f);
                cast.target = t;
            }
            mob.getNavigation().stop();
            timer++;
            if (timer < current.windup()) {
                if (current.tele() != null) {
                    current.tele().run(cast, timer, current.windup());
                }
                return;
            }
            try {
                current.exec().run(cast);
            } finally {
                int idx = kit.indexOf(current);
                ready[idx] = now() + current.cooldown();
                int gap = boss ? 40 + mob.getRandom().nextInt(40) : 70 + mob.getRandom().nextInt(70);
                nextAt = now() + (long) (gap * (1.0 - 0.3 * phase()));
                current = null;
                cast = null;
            }
        }

        @Override
        public void stop() {
            current = null;
            cast = null;
        }

        private void enrage(int ph) {
            mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 40, ph - 1, false, false));
            if (ph >= 2) {
                mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 20 * 40, 0, false, false));
            }
            cast.level.playSound(null, mob.blockPosition(), GateboundSounds.of(SoundEvents.RAVAGER_ROAR), SoundSource.HOSTILE, 1.6f, 0.6f);
            Fx.ringOut(cast.level, Fx.dust(cast.rgb, 1.6f), mob.position().add(0, 0.3, 0), 1.0, 40, 0.5);
            cast.level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight() + 0.4, mob.getZ(), 8, 0.5, 0.3, 0.5, 0);
            for (ServerPlayer p : cast.level.getEntitiesOfClass(ServerPlayer.class, mob.getBoundingBox().inflate(30), x -> x.isAlive() && !x.isSpectator())) {
                Compat.overlay(p, mob.getDisplayName().copy().append(Component.literal(ph >= 2 ? " is furious!" : " is enraged!"))
                        .withStyle(net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.BOLD));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Unwraps a sound holder. */
    private static SoundEvent snd(Holder<SoundEvent> e) {
        return GateboundSounds.of(e);
    }

    /** Returns a sound event as is. */
    private static SoundEvent snd(SoundEvent e) {
        return GateboundSounds.of(e);
    }

    /** Plays a sound at a position for a cast. */
    private static void sound(Cast c, Vec3 at, SoundEvent e, float vol, float pitch) {
        c.level.playSound(null, at.x, at.y, at.z, e, SoundSource.HOSTILE, vol, pitch);
    }

    /** Players near a point within a radius and height. */
    private static List<ServerPlayer> playersNear(Cast c, Vec3 centre, double radius, double dy) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : c.level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(centre, centre).inflate(radius, dy, radius), x -> x.isAlive() && !x.isSpectator())) {
            double dx = p.getX() - centre.x;
            double dz = p.getZ() - centre.z;
            if (dx * dx + dz * dz <= radius * radius) {
                out.add(p);
            }
        }
        return out;
    }

    /** Damage from the mob that ignores armour and cannot be parried; the hit carries the mob's on-hit effect. */
    private static void hit(Cast c, ServerPlayer p, double mult) {
        p.hurtServer(c.level, c.level.damageSources().indirectMagic(null, c.mob), (float) (c.power * mult));
        if (c.effect != null) {
            p.addEffect(new MobEffectInstance(c.effect, 60 + 20 * c.band, c.boss ? 1 : 0));
        }
    }

    /** Knocks a player in a direction. */
    private static void knock(ServerPlayer p, Vec3 dir, double h, double v) {
        Vec3 d = new Vec3(dir.x, 0, dir.z);
        d = d.lengthSqr() < 1.0E-4 ? Vec3.ZERO : d.normalize();
        p.setDeltaMovement(p.getDeltaMovement().add(d.x * h, v, d.z * h));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
    }

    /** A danger marker on the ground: the outline plus a disc that fills as the strike nears. */
    private static void mark(Cast c, Vec3 centre, double radius, int t, int windup) {
        double fill = Math.min(1.0, (t + 1) / (double) windup);
        ParticleOptions edge = Fx.dust(0xFF2A2A, 1.2f);
        ParticleOptions core = Fx.dust(c.rgb, 1.0f);
        Vec3 g = centre.add(0, 0.12, 0);
        if (t % 2 == 0) {
            Fx.ring(c.level, edge, g, radius, (int) (8 + radius * 7));
            Fx.ring(c.level, core, g, radius * fill, (int) (5 + radius * fill * 6));
        }
    }

    /** An expanding ring left by an impact. */
    private static void wave(Cast c, Vec3 centre, double maxRadius, int ticks) {
        int[] t = {0};
        ParticleOptions p = Fx.fade(c.rgb, 0x303030, 1.5f);
        Tasks.run((server, now) -> {
            t[0]++;
            double r = maxRadius * t[0] / ticks;
            Fx.ring(c.level, p, centre.add(0, 0.2, 0), r, (int) (10 + r * 8));
            Fx.ringOut(c.level, ParticleTypes.POOF, centre.add(0, 0.2, 0), r, 6, 0.04);
            return t[0] >= ticks;
        });
    }

    /** Snaps a position to the ground. */
    private static Vec3 groundAt(ServerLevel level, Vec3 v) {
        BlockPos p = BlockPos.containing(v.x, v.y + 1, v.z);
        for (int i = 0; i < 6; i++) {
            if (!level.getBlockState(p.below()).isAir()) {
                return new Vec3(v.x, p.getY(), v.z);
            }
            p = p.below();
        }
        return v;
    }

    /** Eye position of a mob. */
    private static Vec3 eye(Mob m) {
        return new Vec3(m.getX(), m.getEyeY(), m.getZ());
    }

    /** Distance from a point to a line segment. */
    private static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double u = len2 < 1.0E-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(u)));
    }

    // ------------------------------------------------------------------ attack builders

    /** A stamp around the caster: marked ring, then damage, knock-up and a wave. */
    private static Ab slam(String name, double radius, int cd, int windup, double dmg, SoundEvent cue) {
        return new Ab(name, cd, 0, radius + 3.5, windup, 3, 0, null, cue,
                (c, t, w) -> mark(c, c.mob.position(), radius, t, w),
                c -> {
                    Compat.swing(c.mob, InteractionHand.MAIN_HAND, true);
                    for (ServerPlayer p : playersNear(c, c.mob.position(), radius, 2.5)) {
                        hit(c, p, dmg);
                        knock(p, p.position().subtract(c.mob.position()), 0.5, 0.7);
                    }
                    c.level.sendParticles(ParticleTypes.EXPLOSION, c.mob.getX(), c.mob.getY() + 0.3, c.mob.getZ(), 3, radius * 0.4, 0.1, radius * 0.4, 0);
                    sound(c, c.mob.position(), snd(SoundEvents.GENERIC_EXPLODE), 1.1f, 0.7f);
                    wave(c, c.mob.position(), radius, 8);
                });
    }

    /** A jump onto where the target stood, shown by a marker; lands with a burst. */
    private static Ab leap(String name, int cd, double min, double max, double dmg, SoundEvent cue) {
        return new Ab(name, cd, min, max, 16, 3, 0, null, cue,
                (c, t, w) -> {
                    if (t % 2 == 0) {
                        Fx.ring(c.level, Fx.dust(c.rgb, 1.0f), c.mob.position().add(0, 0.1, 0), 1.0 + 0.4 * Math.sin(t), 8);
                    }
                    mark(c, c.aim, 2.8, t, w);
                },
                c -> {
                    Vec3 to = c.aim.subtract(c.mob.position()).multiply(1, 0, 1);
                    double dist = to.length();
                    Vec3 d = dist < 1.0E-3 ? c.dir : to.normalize();
                    double speed = Math.min(1.4, 0.35 + dist / 12.0);
                    c.mob.setDeltaMovement(d.x * speed, 0.62, d.z * speed);
                    c.mob.needsSync = true;
                    int[] ticks = {0};
                    Tasks.run((server, now) -> {
                        ticks[0]++;
                        if (ticks[0] < 6 || (!c.mob.onGround() && ticks[0] < 50 && c.mob.isAlive())) {
                            return false;
                        }
                        if (c.mob.isAlive()) {
                            for (ServerPlayer p : playersNear(c, c.mob.position(), 3.0, 2.5)) {
                                hit(c, p, dmg);
                                knock(p, p.position().subtract(c.mob.position()), 0.7, 0.45);
                            }
                            c.level.sendParticles(ParticleTypes.EXPLOSION, c.mob.getX(), c.mob.getY() + 0.2, c.mob.getZ(), 2, 1.0, 0.1, 1.0, 0);
                            sound(c, c.mob.position(), snd(SoundEvents.GENERIC_EXPLODE), 0.9f, 1.0f);
                            wave(c, c.mob.position(), 3.0, 6);
                        }
                        return true;
                    });
                });
    }

    /** A wide frontal sweep, previewed as an arc that draws itself. */
    private static Ab cleave(String name, double range, double halfAngleDeg, int cd, int windup, double dmg, SoundEvent cue) {
        double cos = Math.cos(Math.toRadians(halfAngleDeg));
        return new Ab(name, cd, 0, range + 0.5, windup, 4, 0, null, cue,
                (c, t, w) -> {
                    double frac = (t + 1) / (double) w;
                    Vec3 o = c.mob.position().add(0, 0.12, 0);
                    double base = Math.atan2(c.dir.z, c.dir.x);
                    double half = Math.toRadians(halfAngleDeg);
                    int pts = 14;
                    for (int i = 0; i <= pts * frac; i++) {
                        double a = base - half + (2 * half) * i / pts;
                        Fx.put(c.level, Fx.dust(0xFF2A2A, 1.1f), o.add(Math.cos(a) * range, 0, Math.sin(a) * range));
                    }
                    Fx.put(c.level, Fx.dust(c.rgb, 1.0f), o.add(c.dir.scale(range * frac)));
                },
                c -> {
                    Compat.swing(c.mob, InteractionHand.MAIN_HAND, true);
                    for (ServerPlayer p : playersNear(c, c.mob.position(), range, 2.5)) {
                        Vec3 to = p.position().subtract(c.mob.position()).multiply(1, 0, 1);
                        if (to.lengthSqr() < 0.25 || to.normalize().dot(c.dir) >= cos) {
                            hit(c, p, dmg);
                            knock(p, c.dir, 0.8, 0.3);
                        }
                    }
                    Vec3 o = c.mob.position().add(0, 1.0, 0);
                    double base = Math.atan2(c.dir.z, c.dir.x);
                    for (int i = -3; i <= 3; i++) {
                        double a = base + Math.toRadians(halfAngleDeg) * i / 3.0;
                        Fx.put(c.level, ParticleTypes.SWEEP_ATTACK, o.add(Math.cos(a) * range * 0.7, 0, Math.sin(a) * range * 0.7));
                    }
                    sound(c, c.mob.position(), snd(SoundEvents.PLAYER_ATTACK_SWEEP), 1.2f, 0.7f);
                });
    }

    /** Markers on the ground that burst a moment later: the first under the target, the rest scattered around it. */
    private static Ab eruption(String name, int spots, double radius, double spread, int cd, double dmg, SoundEvent cue) {
        return new Ab(name, cd, 0, 22, 30, 3, 0, null, cue,
                (c, t, w) -> {
                    if (t == 0) {
                        c.spots.clear();
                        c.spots.add(groundAt(c.level, c.target != null ? c.target.position() : c.aim));
                        for (int i = 1; i < spots; i++) {
                            double a = c.mob.getRandom().nextDouble() * Math.PI * 2;
                            double r = 1.5 + c.mob.getRandom().nextDouble() * spread;
                            c.spots.add(groundAt(c.level, c.aim.add(Math.cos(a) * r, 0, Math.sin(a) * r)));
                        }
                    }
                    for (Vec3 s : c.spots) {
                        mark(c, s, radius, t, w);
                    }
                },
                c -> {
                    for (Vec3 s : c.spots) {
                        for (ServerPlayer p : playersNear(c, s, radius, 3.0)) {
                            hit(c, p, dmg);
                            knock(p, p.position().subtract(s), 0.3, 0.9);
                        }
                        Fx.ringOut(c.level, Fx.dust(c.rgb, 1.5f), s.add(0, 0.2, 0), radius * 0.6, 16, 0.25);
                        for (int y = 0; y < 8; y++) {
                            c.level.sendParticles(Fx.dust(c.rgb, 1.8f), s.x, s.y + y * 0.5, s.z, 3, radius * 0.3, 0.1, radius * 0.3, 0);
                        }
                        c.level.sendParticles(ParticleTypes.EXPLOSION, s.x, s.y + 0.4, s.z, 1, 0.2, 0.1, 0.2, 0);
                        sound(c, s, snd(SoundEvents.GENERIC_EXPLODE), 0.7f, 1.2f);
                    }
                });
    }

    /** Aimed bolts: each shows a thin line for a moment, then fires along it. */
    private static Ab volley(String name, int shots, double dmg, int cd, SoundEvent cue) {
        return new Ab(name, cd, 4, 26, 18, 4, 0, null, cue,
                (c, t, w) -> {
                    Vec3 e = eye(c.mob);
                    c.level.sendParticles(Fx.dust(c.rgb, 1.3f), e.x + c.dir.x * 0.6, e.y, e.z + c.dir.z * 0.6, 3, 0.15, 0.15, 0.15, 0);
                    if (t % 3 == 0 && c.target != null) {
                        Fx.beam(c.level, Fx.dust(0xFF5050, 0.6f), e, c.target.position().add(0, 1.0, 0), 1.2);
                    }
                },
                c -> {
                    for (int i = 0; i < shots; i++) {
                        Tasks.after(i * 8, server -> {
                            if (!c.mob.isAlive() || c.target == null || !c.target.isAlive()) {
                                return;
                            }
                            Vec3 from = eye(c.mob);
                            Vec3 aimAt = c.target.position().add(0, 1.0, 0);
                            Vec3 d = aimAt.subtract(from).normalize();
                            Vec3 to = from.add(d.scale(26));
                            Fx.beam(c.level, Fx.dust(0xFF5050, 0.7f), from, to, 1.0);
                            Compat.swing(c.mob, InteractionHand.MAIN_HAND, true);
                            Tasks.after(8, s2 -> {
                                if (!c.mob.isAlive()) {
                                    return;
                                }
                                Fx.beam(c.level, Fx.dust(c.rgb, 1.6f), from, to, 0.5);
                                sound(c, from, snd(SoundEvents.BLAZE_SHOOT), 1.0f, 1.3f);
                                for (ServerPlayer p : playersNear(c, from.add(to).scale(0.5), 16, 16)) {
                                    if (distToSegment(p.position().add(0, 1.0, 0), from, to) < 1.1) {
                                        hit(c, p, dmg);
                                    }
                                }
                            });
                        });
                    }
                });
    }

    /** Calls helpers of its own rank; only while few of them are alive. */
    private static Ab summon(String name, int count, int cap, int cd, SoundEvent cue) {
        return new Ab(name, cd, 0, 30, 34, 2, 0,
                c -> c.level.getEntitiesOfClass(Mob.class, c.mob.getBoundingBox().inflate(30), m -> m.isAlive() && m.entityTags().contains(MINION)).size() < cap,
                cue,
                (c, t, w) -> {
                    c.level.sendParticles(Fx.dust(c.rgb, 1.4f), c.mob.getX(), c.mob.getY() + 0.5 + t * 0.05, c.mob.getZ(), 4, 0.9, 0.3, 0.9, 0);
                    if (t % 6 == 0) {
                        Fx.ring(c.level, ParticleTypes.SOUL_FIRE_FLAME, c.mob.position().add(0, 0.1, 0), 2.5, 14);
                    }
                },
                c -> {
                    List<EntityType<? extends Mob>> pool = SpireMobs.regulars(c.band);
                    if (pool.isEmpty()) {
                        return;
                    }
                    for (int i = 0; i < count; i++) {
                        double a = c.mob.getRandom().nextDouble() * Math.PI * 2;
                        double r = 2.5 + c.mob.getRandom().nextDouble() * 2.5;
                        BlockPos pos = BlockPos.containing(c.mob.getX() + Math.cos(a) * r, c.mob.getY(), c.mob.getZ() + Math.sin(a) * r);
                        if (!c.level.getBlockState(pos).isAir() || !c.level.getBlockState(pos.below()).isSolid()) {
                            pos = c.mob.blockPosition();
                        }
                        Mob m = pool.get(c.mob.getRandom().nextInt(pool.size())).spawn(c.level, pos, EntitySpawnReason.EVENT);
                        if (m == null) {
                            continue;
                        }
                        AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
                        if (hp != null) {
                            hp.setBaseValue(hp.getBaseValue() * 0.8);
                            m.setHealth(m.getMaxHealth());
                        }
                        m.addTag(MINION);
                        m.setPersistenceRequired();
                        if (c.target != null) {
                            m.setTarget(c.target);
                        }
                        c.level.sendParticles(ParticleTypes.REVERSE_PORTAL, m.getX(), m.getY() + 1, m.getZ(), 16, 0.3, 0.6, 0.3, 0.15);
                    }
                    sound(c, c.mob.position(), snd(SoundEvents.EVOKER_CAST_SPELL), 1.2f, 0.8f);
                });
    }

    /** Vanishes and reappears behind the target for a quick stab. */
    private static Ab blink(String name, int cd, double dmg, SoundEvent cue) {
        return new Ab(name, cd, 4, 20, 14, 3, 0, null, cue,
                (c, t, w) -> {
                    if (t == 0 && c.target != null) {
                        Vec3 look = Vec3.directionFromRotation(0, c.target.getYRot());
                        Vec3 d = c.target.position().subtract(look.scale(2.2));
                        c.dest = groundAt(c.level, d);
                    }
                    c.level.sendParticles(ParticleTypes.PORTAL, c.mob.getX(), c.mob.getY() + 1, c.mob.getZ(), 6, 0.3, 0.8, 0.3, 0.4);
                    if (c.dest != null && t % 2 == 0) {
                        Fx.ring(c.level, Fx.dust(0xFF2A2A, 1.0f), c.dest.add(0, 0.1, 0), 1.0, 8);
                    }
                },
                c -> {
                    if (c.dest == null) {
                        return;
                    }
                    c.level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.mob.getX(), c.mob.getY() + 1, c.mob.getZ(), 20, 0.3, 0.8, 0.3, 0.2);
                    sound(c, c.mob.position(), snd(SoundEvents.ENDERMAN_TELEPORT), 1.0f, 1.0f);
                    c.mob.teleportTo(c.dest.x, c.dest.y, c.dest.z);
                    c.level.sendParticles(ParticleTypes.PORTAL, c.dest.x, c.dest.y + 1, c.dest.z, 20, 0.3, 0.8, 0.3, 0.4);
                    Tasks.after(6, server -> {
                        if (!c.mob.isAlive()) {
                            return;
                        }
                        Compat.swing(c.mob, InteractionHand.MAIN_HAND, true);
                        for (ServerPlayer p : playersNear(c, c.mob.position(), 3.2, 2.5)) {
                            hit(c, p, dmg);
                            knock(p, p.position().subtract(c.mob.position()), 0.6, 0.3);
                        }
                        sound(c, c.mob.position(), snd(SoundEvents.PLAYER_ATTACK_CRIT), 1.0f, 0.8f);
                    });
                });
    }

    /** A chain of energy that drags the target in. */
    private static Ab pull(String name, int cd, double dmg, SoundEvent cue) {
        return new Ab(name, cd, 5, 16, 16, 2, 0, null, cue,
                (c, t, w) -> {
                    if (c.target != null) {
                        Fx.beam(c.level, Fx.dust(c.rgb, 0.8f), eye(c.mob), c.target.position().add(0, 1.0, 0), 0.7 - 0.4 * t / w);
                    }
                },
                c -> {
                    if (c.target == null || !c.target.isAlive() || c.mob.distanceTo(c.target) > 18) {
                        return;
                    }
                    Vec3 to = c.mob.position().subtract(c.target.position());
                    double len = Math.max(1.0, to.length());
                    Vec3 d = to.scale(1.0 / len);
                    c.target.setDeltaMovement(d.x * Math.min(1.8, 0.4 + len / 7.0), 0.35, d.z * Math.min(1.8, 0.4 + len / 7.0));
                    c.target.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(c.target));
                    hit(c, c.target, dmg);
                    Fx.beam(c.level, Fx.dust(c.rgb, 1.5f), eye(c.mob), c.target.position().add(0, 1.0, 0), 0.4);
                    sound(c, c.target.position(), snd(SoundEvents.CHAIN_BREAK), 1.0f, 0.6f);
                });
    }

    /** A lingering zone on the ground that hurts anyone standing in it. */
    private static Ab cloud(String name, double radius, int duration, int cd, double dmgPerSecond, SoundEvent cue) {
        return new Ab(name, cd, 0, 22, 22, 3, 0, null, cue,
                (c, t, w) -> {
                    if (t == 0) {
                        c.dest = groundAt(c.level, c.target != null ? c.target.position() : c.aim);
                    }
                    mark(c, c.dest, radius, t, w);
                },
                c -> {
                    Vec3 centre = c.dest == null ? c.aim : c.dest;
                    int[] t = {0};
                    sound(c, centre, snd(SoundEvents.BREWING_STAND_BREW), 1.0f, 0.6f);
                    Tasks.run((server, now) -> {
                        t[0]++;
                        c.level.sendParticles(Fx.dust(c.rgb, 1.6f), centre.x, centre.y + 0.4, centre.z, 6, radius * 0.45, 0.35, radius * 0.45, 0);
                        c.level.sendParticles(ParticleTypes.SMOKE, centre.x, centre.y + 0.2, centre.z, 2, radius * 0.4, 0.1, radius * 0.4, 0.01);
                        if (t[0] % 10 == 0) {
                            Fx.ring(c.level, Fx.dust(0xFF2A2A, 1.0f), centre.add(0, 0.1, 0), radius, (int) (8 + radius * 6));
                            for (ServerPlayer p : playersNear(c, centre, radius, 2.5)) {
                                hit(c, p, dmgPerSecond / 2.0);
                            }
                        }
                        return t[0] >= duration || !c.mob.level().equals(c.level);
                    });
                });
    }

    /** A ring that rolls out from the caster; jump over it or be out of reach. */
    private static Ab nova(String name, double maxRadius, int cd, double dmg, SoundEvent cue) {
        return new Ab(name, cd, 0, maxRadius, 24, 2, 0, null, cue,
                (c, t, w) -> {
                    double r = 1.5 + 2.0 * Math.sin(t * 0.5);
                    Fx.ring(c.level, Fx.dust(c.rgb, 1.2f), c.mob.position().add(0, 0.12, 0), r, 14);
                    if (t == w - 1) {
                        Fx.ringOut(c.level, Fx.dust(0xFF2A2A, 1.4f), c.mob.position().add(0, 0.3, 0), 1.0, 24, 0.3);
                    }
                },
                c -> {
                    Vec3 o = c.mob.position();
                    Set<java.util.UUID> struck = new HashSet<>();
                    int ticks = 22;
                    int[] t = {0};
                    sound(c, o, snd(SoundEvents.WARDEN_SONIC_BOOM), 1.0f, 1.2f);
                    Tasks.run((server, now) -> {
                        t[0]++;
                        double r = maxRadius * t[0] / ticks;
                        Fx.ring(c.level, Fx.dust(c.rgb, 1.8f), o.add(0, 0.3, 0), r, (int) (14 + r * 7));
                        Fx.ringOut(c.level, ParticleTypes.CLOUD, o.add(0, 0.2, 0), r, 8, 0.03);
                        for (ServerPlayer p : playersNear(c, o, r + 1.2, 2.5)) {
                            double dx = p.getX() - o.x;
                            double dz = p.getZ() - o.z;
                            double d = Math.sqrt(dx * dx + dz * dz);
                            if (d >= r - 1.2 && p.getY() - o.y < 1.1 && struck.add(p.getUUID())) {
                                hit(c, p, dmg);
                                knock(p, p.position().subtract(o), 0.9, 0.35);
                            }
                        }
                        return t[0] >= ticks;
                    });
                });
    }

    /** Heals the most hurt ally nearby. */
    private static Ab mend(String name, int cd, SoundEvent cue) {
        Predicate<Cast> hurtAlly = c -> findAlly(c) != null;
        return new Ab(name, cd, 0, 40, 20, 2, 0, hurtAlly, cue,
                (c, t, w) -> {
                    if (t == 0) {
                        c.ally = findAlly(c);
                    }
                    if (c.ally != null && c.ally.isAlive()) {
                        Fx.beam(c.level, Fx.dust(0x70FF90, 0.8f), eye(c.mob), c.ally.position().add(0, c.ally.getBbHeight() * 0.5, 0), 0.8);
                    }
                },
                c -> {
                    if (c.ally != null && c.ally.isAlive()) {
                        c.ally.heal(c.ally.getMaxHealth() * 0.3f);
                        c.level.sendParticles(ParticleTypes.HEART, c.ally.getX(), c.ally.getY() + c.ally.getBbHeight(), c.ally.getZ(), 6, 0.4, 0.3, 0.4, 0);
                        sound(c, c.ally.position(), snd(SoundEvents.EVOKER_CAST_SPELL), 0.8f, 1.6f);
                    }
                });
    }

    /** Most wounded (under 70% health) allied Spire mob within 14 blocks. */
    private static Mob findAlly(Cast c) {
        Mob best = null;
        float worst = 0.7f;
        for (Mob m : c.level.getEntitiesOfClass(Mob.class, c.mob.getBoundingBox().inflate(14), x -> x != c.mob && x.isAlive()
                && (x.entityTags().contains("gatebound_mob") || x.entityTags().contains(MINION)))) {
            float f = m.getHealth() / m.getMaxHealth();
            if (f < worst) {
                worst = f;
                best = m;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ kits

    /** The ability list for a mob type id. */
    private static List<Ab> kit(String id) {
        SoundEvent growl = snd(SoundEvents.RAVAGER_STEP);
        SoundEvent roar = snd(SoundEvents.RAVAGER_ROAR);
        SoundEvent cast = snd(SoundEvents.EVOKER_PREPARE_ATTACK);
        SoundEvent hiss = snd(SoundEvents.SPIDER_AMBIENT);
        SoundEvent bone = snd(SoundEvents.SKELETON_AMBIENT);
        SoundEvent fire = snd(SoundEvents.BLAZE_AMBIENT);
        SoundEvent whisper = snd(SoundEvents.ENDERMAN_STARE);
        return switch (id) {
            // E, the crypts
            case "crypt_shambler" -> List.of(leap("Grave Lunge", 200, 4, 12, 1.0, growl), cleave("Rotting Claw", 3.2, 55, 100, 12, 1.0, growl));
            case "bone_warden" -> List.of(volley("Bone Shot", 1, 1.0, 160, bone), slam("Rattle", 3.0, 200, 18, 0.9, bone));
            case "crypt_lord" -> List.of(slam("Crypt Quake", 5.5, 180, 26, 1.1, roar), summon("Raise the Fallen", 3, 6, 400, cast),
                    eruption("Bone Spikes", 4, 2.0, 5, 260, 1.0, cast), cleave("Grave Cleave", 4.2, 70, 120, 16, 1.2, roar),
                    leap("Tomb Crash", 260, 5, 16, 1.2, roar).phase(1));
            // D, the mossy caverns
            case "cavern_lurker" -> List.of(leap("Pounce", 160, 4, 14, 1.0, hiss), cloud("Venom Spit", 2.6, 80, 260, 2.0, hiss));
            case "mossback" -> List.of(slam("Moss Slam", 3.5, 200, 22, 1.0, growl), cloud("Spore Burst", 3.0, 100, 280, 2.2, growl));
            case "hollow_knight" -> List.of(cleave("Hollow Cut", 4.5, 80, 100, 14, 1.2, whisper), blink("Hollow Step", 220, 1.4, whisper),
                    nova("Hollow Shriek", 10, 300, 1.0, whisper).phase(1), eruption("Soul Thorns", 5, 1.9, 6, 280, 1.0, whisper),
                    summon("Hollow Echoes", 2, 4, 450, whisper).phase(1));
            // C, the flooded halls
            case "drowned_husk" -> List.of(pull("Drowning Chain", 240, 0.8, growl), leap("Tide Lunge", 180, 4, 12, 1.0, growl));
            case "tide_stalker" -> List.of(volley("Water Bolt", 2, 0.9, 200, bone), cloud("Brine Pool", 2.8, 100, 300, 2.2, bone));
            case "deep_warden" -> List.of(nova("Tidal Wave", 12, 260, 1.2, roar), pull("Abyssal Hook", 260, 1.0, roar),
                    eruption("Geysers", 5, 2.0, 6, 260, 1.1, roar), slam("Deep Slam", 5.0, 200, 24, 1.2, roar),
                    summon("Call of the Deep", 3, 6, 450, cast).phase(1));
            // B, the lava halls
            case "magma_brute" -> List.of(slam("Magma Stomp", 4.2, 190, 22, 1.2, growl), leap("Molten Charge", 220, 5, 14, 1.2, growl),
                    cleave("Searing Swing", 3.8, 65, 110, 14, 1.2, growl));
            case "ember_witch" -> List.of(volley("Firebolt", 2, 1.0, 180, fire), cloud("Burning Brew", 3.0, 100, 280, 2.4, fire), mend("Ember Mending", 340, cast));
            case "cinder_magus" -> List.of(eruption("Pillars of Cinder", 6, 2.0, 7, 240, 1.1, cast), nova("Cinder Nova", 11, 300, 1.2, cast),
                    volley("Magus Bolts", 3, 1.0, 240, fire), summon("Summon Embers", 3, 6, 450, cast).phase(1), blink("Flare Step", 260, 1.4, cast).phase(1));
            // A, the ashen reaches
            case "ash_revenant" -> List.of(blink("Ashen Step", 200, 1.4, whisper), cleave("Wither Cut", 4.0, 75, 100, 14, 1.3, bone),
                    cloud("Ash Cloud", 3.0, 100, 280, 2.6, bone));
            case "cinder_archer" -> List.of(volley("Cinder Volley", 3, 1.1, 200, fire), eruption("Arrow Rain", 5, 1.8, 6, 260, 1.0, fire), leap("Backflip", 180, 0, 6, 0.6, fire));
            case "ashen_colossus" -> List.of(slam("Colossus Slam", 6.5, 190, 26, 1.4, roar), leap("Titan Leap", 240, 6, 18, 1.4, roar),
                    eruption("Ashfall", 7, 2.2, 8, 240, 1.2, roar), nova("Ash Shockwave", 13, 300, 1.3, roar),
                    summon("Ashen Brood", 3, 6, 450, roar).phase(1));
            // S, the void sanctum
            case "void_walker" -> List.of(blink("Void Step", 190, 1.5, whisper), pull("Void Chain", 240, 1.0, whisper), cleave("Void Rend", 4.2, 75, 100, 13, 1.4, whisper));
            case "rift_caster" -> List.of(volley("Rift Bolts", 3, 1.1, 200, cast), eruption("Rift Tears", 6, 2.0, 7, 240, 1.2, cast), mend("Void Mending", 320, cast));
            case "void_sovereign" -> List.of(blink("Sovereign Step", 180, 1.6, whisper), nova("Void Ring", 13, 260, 1.4, whisper),
                    eruption("Collapsing Stars", 7, 2.1, 8, 220, 1.3, whisper), pull("Singularity", 240, 1.2, whisper),
                    volley("Void Lances", 4, 1.2, 240, whisper), summon("Void Spawn", 3, 6, 420, whisper).phase(1));
            case "spire_sovereign" -> List.of(slam("Sovereign's Wrath", 7.0, 170, 24, 1.5, roar), blink("Final Step", 170, 1.7, whisper),
                    nova("Spire Nova", 14, 240, 1.5, whisper), eruption("Judgement", 8, 2.2, 9, 200, 1.4, cast),
                    volley("Sovereign Lances", 5, 1.3, 220, cast), pull("Dragging Chains", 220, 1.3, whisper),
                    summon("Legion of the Spire", 4, 8, 400, cast).phase(1), leap("Falling Spire", 240, 6, 20, 1.6, roar).phase(1));
            default -> List.of();
        };
    }
}
