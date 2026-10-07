package dev.gatebound.echo;

import dev.gatebound.dungeon.DungeonRegistries;
import dev.gatebound.dungeon.DungeonStore;
import dev.gatebound.dungeon.FloorPlan;
import dev.gatebound.dungeon.GateExitBlock;
import dev.gatebound.dungeon.Spire;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The AI of an Echo, run once per tick from EchoEntity. Each echo has an order from its owner:
 * FOLLOW (trail behind, fight what attacks you), GUARD (stay close and attack any threat near you) or
 * SEEK (scout for a gate and report it). Mob-specific abilities come from EchoKits.
 */
final class EchoBrain {
    private EchoBrain() {
    }

    static void tick(EchoEntity e, ServerLevel level) {
        EchoManager.Info info = EchoManager.info(e);
        if (info == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(info.owner);
        if (owner == null || owner.level() != level || !owner.isAlive()) {
            e.setTarget(null);
            return;
        }
        if (e.isVehicle()) {
            e.setTarget(null);
            return;
        }
        int mode = EchoManager.effectiveMode(owner, e.getUUID(), info.slot);
        EchoKits.Kit kit = EchoKits.of(e.mobType());
        if (e.attackCooldown > 0) {
            e.attackCooldown--;
        }
        if (e.abilityCooldown > 0) {
            e.abilityCooldown--;
        }
        if (mode != EchoRoster.SEEK && mode != EchoRoster.GOTO) {
            e.seekFound = null;
        }
        if (mode != EchoRoster.GUIDE) {
            e.guideWarned = false;
            e.guideGo = false;
            e.guideReady = false;
        }

        if (kit.special() == EchoKits.Special.HEALER) {
            support(e, owner, level);
        }
        if ((e.tickCount + info.slot) % 5 == 0) {
            acquire(e, owner, level, mode);
        }
        LivingEntity target = e.getTarget();
        boolean onLeash = mode == EchoRoster.GUARD || mode == EchoRoster.GUIDE;
        if (target != null && onLeash && (e.distanceToSqr(owner) > GUARD_LEASH * GUARD_LEASH
                || target.distanceToSqr(owner) > GUARD_LEASH * GUARD_LEASH)) {
            e.setTarget(null);
            target = null;
        }
        if (target != null && !validTarget(e, target, owner)) {
            e.setTarget(null);
            target = null;
        }
        if (target != null) {
            fight(e, target, kit, level);
            return;
        }
        e.setAggressive(false);
        switch (mode) {
            case EchoRoster.ATTACK -> follow(e, owner, 9, 4);
            case EchoRoster.SEEK -> seek(e, owner, level);
            case EchoRoster.GUIDE -> guide(e, owner, level);
            case EchoRoster.GOTO -> goTo(e, owner, level);
            default -> follow(e, owner, 5, 2.5); // Guard: stays close
        }
    }

    // ---------------------------------------------------------------- targeting

    /** Scouts and gate-runners avoid fights unless something is right on top of them. */
    private static boolean passive(int mode) {
        return mode == EchoRoster.SEEK || mode == EchoRoster.GOTO;
    }

    static boolean validTarget(EchoEntity e, LivingEntity t, ServerPlayer owner) {
        return t.isAlive() && t.level() == e.level() && !(t instanceof Player) && !(t instanceof EchoEntity)
                && !(t instanceof dev.gatebound.trade.GateboundNpc) && !EchoManager.isEcho(t)
                && t.distanceToSqr(owner) < 40 * 40;
    }

    private static boolean threat(LivingEntity m, EchoEntity e, ServerPlayer owner) {
        if (m instanceof Enemy) {
            return true;
        }
        return m instanceof Mob mob && (mob.getTarget() == owner || mob.getTarget() == e);
    }

    private static void acquire(EchoEntity e, ServerPlayer owner, ServerLevel level, int mode) {
        LivingEntity current = e.getTarget();
        if (current != null && validTarget(e, current, owner)) {
            return;
        }
        LivingEntity pick = null;
        if (mode == EchoRoster.ATTACK) {
            // Attack: go for the nearest enemy around the echo, and for whatever the owner is fighting.
            LivingEntity hit = owner.getLastHurtMob();
            if (hit != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && validTarget(e, hit, owner)) {
                pick = hit;
            }
            if (pick == null) {
                double best = Double.MAX_VALUE;
                for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(20, 8, 20),
                        m -> validTarget(e, m, owner) && threat(m, e, owner))) {
                    double d = e.distanceToSqr(m);
                    if (d < best) {
                        best = d;
                        pick = m;
                    }
                }
            }
        } else {
            // Guard (and Guide, and scouts when something is on top of them): only what has just done harm, close to the owner.
            double reach = passive(mode) ? 4 : GUARD_LEASH;
            LivingEntity hurtBy = owner.getLastHurtByMob();
            if (hurtBy != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100 && !passive(mode)
                    && validTarget(e, hurtBy, owner) && hurtBy.distanceToSqr(owner) < reach * reach) {
                pick = hurtBy;
            }
            LivingEntity hurtEcho = e.getLastHurtByMob();
            if (pick == null && hurtEcho != null && e.tickCount - e.getLastHurtByMobTimestamp() < 100
                    && validTarget(e, hurtEcho, owner) && hurtEcho.distanceToSqr(owner) < reach * reach) {
                pick = hurtEcho;
            }
            if (pick == null) {
                double best = Double.MAX_VALUE;
                for (Mob m : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(reach, 6, reach),
                        m -> (m.getTarget() == owner || m.getTarget() == e) && validTarget(e, m, owner))) {
                    double d = e.distanceToSqr(m);
                    if (d < best) {
                        best = d;
                        pick = m;
                    }
                }
            }
        }
        if (pick != null) {
            e.setTarget(pick);
        }
    }

    /** Guards never chase or fight farther than this from their owner. */
    private static final double GUARD_LEASH = 12;

    // ---------------------------------------------------------------- fighting

    private static void fight(EchoEntity e, LivingEntity target, EchoKits.Kit kit, ServerLevel level) {
        e.setAggressive(true);
        e.getLookControl().setLookAt(target, 30, 30);
        double distSq = e.distanceToSqr(target.getX(), target.getY(), target.getZ());

        if (kit.special() == EchoKits.Special.TELEPORTER && distSq > 36 && e.abilityCooldown <= 0) {
            Vec3 behind = target.position().subtract(target.getLookAngle().multiply(1.5, 0, 1.5));
            level.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.2);
            e.teleportTo(behind.x, target.getY(), behind.z);
            level.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.2);
            e.abilityCooldown = 80;
            return;
        }

        if (kit.ranged()) {
            boolean sight = e.hasLineOfSight(target);
            if (distSq > kit.range() * kit.range() || !sight) {
                approach(e, target);
            } else {
                if (distSq < kit.range() * kit.range() * 0.12) {
                    Vec3 away = e.position().subtract(target.position()).normalize().scale(6);
                    e.getNavigation().moveTo(e.getX() + away.x, e.getY(), e.getZ() + away.z, 1.1);
                } else {
                    e.getNavigation().stop();
                }
                if (e.attackCooldown <= 0) {
                    e.attackCooldown = kit.cooldown();
                    shoot(e, target, kit, level);
                }
            }
            return;
        }

        double reach = e.getBbWidth() * 1.2 + target.getBbWidth() + 0.6;
        if (distSq > reach * reach) {
            approach(e, target);
            return;
        }
        e.getNavigation().stop();
        if (e.attackCooldown > 0) {
            return;
        }
        e.attackCooldown = kit.cooldown();
        e.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        if (kit.special() == EchoKits.Special.EXPLODER) {
            if (e.abilityCooldown <= 0) {
                e.abilityCooldown = 120;
                blast(e, target, level);
            }
            return;
        }
        strike(e, target, kit, level);
    }

    private static void approach(EchoEntity e, LivingEntity target) {
        if (--e.repath <= 0) {
            e.repath = 8;
            e.getNavigation().moveTo(target, 1.25);
        }
    }

    private static void strike(EchoEntity e, LivingEntity target, EchoKits.Kit kit, ServerLevel level) {
        float dealt = (float) (e.attackDamage() * kit.damageMult() * e.damageMultiplier(target));
        target.hurtServer(level, level.damageSources().mobAttack(e), dealt);
        soulLink(e, level, dealt);
        if (kit.effect() != null) {
            target.addEffect(new MobEffectInstance(kit.effect(), kit.effectTicks(), kit.amplifier()));
        }
        if (kit.fireSeconds() > 0) {
            target.setRemainingFireTicks(kit.fireSeconds() * 20);
        }
        if (kit.knockback()) {
            Vec3 dir = target.position().subtract(e.position()).normalize();
            target.push(dir.x * 0.9, 0.4, dir.z * 0.9);
            target.needsSync = true;
        }
    }

    private static void shoot(EchoEntity e, LivingEntity target, EchoKits.Kit kit, ServerLevel level) {
        e.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        trail(level, e.getEyePosition(), target.getEyePosition(), kit.trail());
        level.playSound(null, e.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 0.8f, 1.0f);
        strike(e, target, kit, level);
    }

    static void trail(ServerLevel level, Vec3 from, Vec3 to, net.minecraft.core.particles.ParticleOptions particle) {
        if (particle == null) {
            return;
        }
        Vec3 d = to.subtract(from);
        int steps = Math.max(1, (int) (d.length() / 0.6));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(d.scale(i / (double) steps));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    /** Creeper echo: a blast that hurts only enemies and breaks no blocks, and the echo survives it. */
    private static void blast(EchoEntity e, LivingEntity center, ServerLevel level) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, e.getX(), e.getY() + 0.5, e.getZ(), 1, 0, 0, 0, 0);
        level.playSound(null, e.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 1.0f, 1.0f);
        EchoManager.Info info = EchoManager.info(e);
        ServerPlayer owner = info == null ? null : level.getServer().getPlayerList().getPlayer(info.owner);
        for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(4))) {
            if (owner != null && validTarget(e, m, owner) && threat(m, e, owner)) {
                float dealt = (float) (e.attackDamage() * 2.5 * e.damageMultiplier(m));
                m.hurtServer(level, level.damageSources().mobAttack(e), dealt);
                soulLink(e, level, dealt);
                Vec3 dir = m.position().subtract(e.position()).normalize();
                m.push(dir.x * 1.1, 0.5, dir.z * 1.1);
                m.needsSync = true;
            }
        }
    }

    /** Soul Link: while it lasts, an echo heals itself (and, a little, its owner) for part of the damage it deals. */
    static void soulLink(EchoEntity e, ServerLevel level, float dealt) {
        if (level.getGameTime() >= e.linkUntil || dealt <= 0) {
            return;
        }
        e.heal((float) (dealt * e.linkFraction));
        EchoManager.Info info = EchoManager.info(e);
        ServerPlayer owner = info == null ? null : level.getServer().getPlayerList().getPlayer(info.owner);
        if (owner != null && owner.isAlive()) {
            owner.heal((float) (dealt * e.linkFraction * 0.4));
        }
        level.sendParticles(ParticleTypes.SOUL, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(), 1, 0.2, 0.2, 0.2, 0.02);
    }

    // ---------------------------------------------------------------- healing (witch, allay)

    private static void support(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        if (e.abilityCooldown > 0) {
            return;
        }
        if (owner.getHealth() < owner.getMaxHealth() * 0.6f && e.distanceToSqr(owner) < 20 * 20) {
            e.abilityCooldown = 90;
            e.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            trail(level, e.getEyePosition(), owner.getEyePosition(), ParticleTypes.WITCH);
            level.playSound(null, e.blockPosition(), SoundEvents.WITCH_THROW, SoundSource.NEUTRAL, 1.0f, 1.0f);
            level.playSound(null, owner.blockPosition(), SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.HEART, owner.getX(), owner.getY() + 1.2, owner.getZ(), 10, 0.5, 0.6, 0.5, 0.05);
            owner.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 1));
            owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            return;
        }
        List<EchoEntity> hurt = level.getEntitiesOfClass(EchoEntity.class, e.getBoundingBox().inflate(16),
                a -> a != e && a.getHealth() < a.getMaxHealth() * 0.5f && EchoManager.isEcho(a));
        if (!hurt.isEmpty()) {
            EchoEntity ally = hurt.get(0);
            e.abilityCooldown = 90;
            trail(level, e.getEyePosition(), ally.getEyePosition(), ParticleTypes.WITCH);
            level.playSound(null, e.blockPosition(), SoundEvents.WITCH_THROW, SoundSource.NEUTRAL, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.HEART, ally.getX(), ally.getY() + 1.0, ally.getZ(), 8, 0.4, 0.5, 0.4, 0.05);
            ally.heal(8f);
        }
    }

    // ---------------------------------------------------------------- movement orders

    private static void follow(EchoEntity e, ServerPlayer owner, double start, double stop) {
        double d2 = e.distanceToSqr(owner);
        if (d2 > start * start) {
            if (--e.repath <= 0) {
                e.repath = 10;
                e.getNavigation().moveTo(owner, 1.2);
            }
        } else if (d2 < stop * stop) {
            e.getNavigation().stop();
        }
    }

    /** Gate search: sweep the area around the owner, away from ground already covered, and report any gate in sight. */
    private static void seek(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        // As soon as any seeker has found a gate the whole group stops searching and gathers there.
        BlockPos rally = EchoManager.rallyPoint(owner, e);
        EchoManager.Info myInfo = EchoManager.info(e);
        if (e.seekFound == null && rally != null) {
            e.seekFound = rally;
            e.seekFinder = false;
            e.exploreTarget = null;
        } else if (e.seekFound != null && !e.seekFinder && rally == null && myInfo != null
                && EchoManager.effectiveMode(owner, e.getUUID(), myInfo.slot) == EchoRoster.SEEK) {
            e.seekFound = null;
        }
        if (e.seekFound != null) {
            if (e.distanceToSqr(e.seekFound.getX() + 0.5, e.seekFound.getY(), e.seekFound.getZ() + 0.5) > 9) {
                if (--e.repath <= 0) {
                    e.repath = 15;
                    if (!routeInDungeon(e, level, e.seekFound, 1.3)) {
                        e.getNavigation().moveTo(e.seekFound.getX() + 0.5, e.seekFound.getY() + (e.isFlier() ? 2 : 0),
                                e.seekFound.getZ() + 0.5, 1.3);
                    }
                }
            } else {
                e.getNavigation().stop();
            }
            if (e.seekFinder && e.tickCount % 400 == 0) {
                announce(e, owner, level, e.seekFound);
            }
            return;
        }
        markExplored(e, owner, level);
        if (e.tickCount % 10 == 0) {
            BlockPos found = findGate(e, level, e.isFlier() ? 80 : 56);
            if (found != null) {
                e.seekFound = found;
                e.seekFinder = true;
                e.exploreTarget = null;
                announce(e, owner, level, found);
                return;
            }
        }
        // Detect getting stuck: not having moved for a while means the target is unreachable from here.
        stuckTick(e);
        boolean arrived = e.exploreTarget != null && e.distanceToSqr(e.exploreTarget.x, e.getY(), e.exploreTarget.z) < 25;
        if (e.exploreTarget == null || arrived || e.stuckChecks >= 3 || (!e.getNavigation().isInProgress() && e.tickCount % 20 == 0)) {
            if (arrived) {
                e.exploreRound++;
                if (e.exploreRound == 14) {
                    Component msg = Component.literal("[Echo] " + e.mobName() + " has searched the area around you and found no gate. "
                            + "It keeps looking; walk somewhere new to widen the search.").withStyle(ChatFormatting.GRAY);
                    owner.sendSystemMessage(msg);
                    e.exploreRound = 6;
                }
            }
            if (e.stuckChecks >= 3) {
                // Stuck even after choosing new targets: slip out toward where it was going (or toward a random bearing).
                e.stuckStrikes++;
                if (e.stuckStrikes >= 2) {
                    double bearing = e.getRandom().nextDouble() * Math.PI * 2;
                    double tx = e.exploreTarget != null ? e.exploreTarget.x : e.getX() + Math.cos(bearing) * 20;
                    double tz = e.exploreTarget != null ? e.exploreTarget.z : e.getZ() + Math.sin(bearing) * 20;
                    blink(e, level, tx, tz);
                }
            }
            e.stuckChecks = 0;
            pickExploreTarget(e, owner, level);
        }
    }

    private static final java.util.Map<String, java.util.Set<Long>> EXPLORED = new java.util.HashMap<>();

    private static String exploredKey(ServerPlayer owner, ServerLevel level) {
        return owner.getUUID() + "@" + level.dimension().toString();
    }

    private static long cell(double x, double z) {
        return (((long) Math.floor(x / 16.0)) << 32) ^ (((long) Math.floor(z / 16.0)) & 0xFFFFFFFFL);
    }

    private static void markExplored(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        if (e.tickCount % 10 != 0) {
            return;
        }
        markWaypoints(e, owner, level);
        java.util.Set<Long> set = EXPLORED.computeIfAbsent(exploredKey(owner, level), k -> new java.util.HashSet<>());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set.add(cell(e.getX() + dx * 16, e.getZ() + dz * 16));
            }
        }
        if (set.size() > 20000) {
            set.clear();
        }
    }

    private static int visitedAround(java.util.Set<Long> set, double x, double z) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (set.contains(cell(x + dx * 16, z + dz * 16))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Picks the reachable point around the owner whose surroundings have been explored the least. */
    private static void pickExploreTarget(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        if (level.dimension().equals(DungeonRegistries.DUNGEON) && pickWaypoint(e, owner, level)) {
            return;
        }
        java.util.Set<Long> seen = EXPLORED.computeIfAbsent(exploredKey(owner, level), k -> new java.util.HashSet<>());
        boolean flier = e.isFlier();
        double maxRadius = flier ? 110 : 90;
        double radius = Math.min(maxRadius, 24 + e.exploreRound * 9);
        // Each echo starts on its own bearing so a group fans out instead of searching the same spot.
        EchoManager.Info info = EchoManager.info(e);
        double baseAngle = (info == null ? 0 : info.slot) * 2.399963;
        net.minecraft.world.phys.Vec3 best = null;
        net.minecraft.world.level.pathfinder.Path bestPath = null;
        int bestScore = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = baseAngle + e.exploreRound * 0.9 + e.getRandom().nextDouble() * Math.PI * 2 * (attempt / 10.0 + 0.1);
            double dist = radius * (0.6 + e.getRandom().nextDouble() * 0.5);
            double x = owner.getX() + Math.cos(angle) * dist;
            double z = owner.getZ() + Math.sin(angle) * dist;
            if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(x, owner.getY(), z))) {
                continue;
            }
            double y;
            if (flier) {
                y = owner.getY() + 10 + e.getRandom().nextInt(10);
            } else if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int) Math.floor(x), (int) Math.floor(z));
            } else {
                y = e.getY();
            }
            net.minecraft.world.level.pathfinder.Path path = e.getNavigation().createPath(x, y, z, 2);
            if (path == null && !flier) {
                continue;
            }
            int score = visitedAround(seen, x, z) * 4 + e.getRandom().nextInt(3);
            if (score < bestScore) {
                bestScore = score;
                best = new net.minecraft.world.phys.Vec3(x, y, z);
                bestPath = path;
            }
        }
        if (best == null) {
            // Nothing reachable in that ring: try close to the owner so the echo never just stands still.
            e.exploreRound = Math.max(0, e.exploreRound - 3);
            e.exploreTarget = null;
            if (e.distanceToSqr(owner) > 12 * 12) {
                e.getNavigation().moveTo(owner, 1.2);
            }
            return;
        }
        e.exploreTarget = best;
        double speed = flier ? 1.5 : 1.3;
        if (bestPath != null) {
            e.getNavigation().moveTo(bestPath, speed);
        } else {
            e.getNavigation().moveTo(best.x, best.y, best.z, speed);
        }
    }

    // Dungeon floors are walls with rooms and corridors, so random points around the owner are almost all solid rock and
    // the echo just circles its own room. Instead it walks room to room along the floor's known waypoints (the plan's
    // spawn spots, spread through every room), nearest unvisited first, and remembers which it has seen.

    private static long memorySeed = Long.MIN_VALUE;

    /** Forgets every floor's waypoints, explored marks and sector claims (a floor was rebuilt, or the world changed). */
    static void resetExploration() {
        WAYPOINTS.clear();
        PLANS.clear();
        FIELDS.clear();
        SEEN_WAYPOINTS.clear();
        CLAIMS.clear();
        ATTEMPTS.clear();
        FLOOR_DONE.clear();
    }

    /** The memory is static, so a different world in the same game session must not inherit it. */
    private static void checkWorld(ServerLevel level) {
        if (level.getSeed() != memorySeed) {
            memorySeed = level.getSeed();
            resetExploration();
        }
    }

    private static final java.util.Map<Integer, List<BlockPos>> WAYPOINTS = new java.util.HashMap<>();
    private static final java.util.Map<Integer, FloorPlan> PLANS = new java.util.HashMap<>();
    private static final java.util.Map<String, int[]> FIELDS = new java.util.HashMap<>();

    /**
     * Walks toward a spot on a dungeon floor along the real walkable route (the plan's open cells), about 14 blocks at a
     * time. The pathfinder alone cannot do this: its search is limited in range and gets stuck behind walls in a straight
     * line to the target. Returns false when this is not a dungeon floor or the spot cannot be reached by any route.
     */
    private static boolean routeInDungeon(EchoEntity e, ServerLevel level, BlockPos target, double speed) {
        if (!level.dimension().equals(DungeonRegistries.DUNGEON)) {
            return false;
        }
        int total = DungeonStore.get(level.getServer()).ensureTower(level.getServer());
        int floor = Spire.floorAt(e.getX(), e.getZ(), total);
        if (floor <= 0 || floor != Spire.floorAt(target.getX() + 0.5, target.getZ() + 0.5, total)) {
            return false;
        }
        waypoints(level, floor, total, true); // makes sure the plan is cached
        FloorPlan plan = PLANS.get(floor);
        if (plan == null) {
            return false;
        }
        String key = floor + "#" + target.asLong();
        if (FIELDS.size() > 12) {
            FIELDS.clear();
        }
        int[] field = FIELDS.computeIfAbsent(key, k -> plan.distancesTo(target));
        if (field == null) {
            return false;
        }
        BlockPos hop = plan.hop(field, e.getX(), e.getZ(), 14);
        if (hop == null) {
            return false;
        }
        e.getNavigation().moveTo(hop.getX() + 0.5, hop.getY(), hop.getZ() + 0.5, speed);
        return true;
    }
    private static final java.util.Map<String, java.util.Set<Long>> SEEN_WAYPOINTS = new java.util.HashMap<>();

    private static final int SECTOR = 48;

    private static final class Claim {
        final java.util.UUID who;
        long tick;

        Claim(java.util.UUID who, long tick) {
            this.who = who;
            this.tick = tick;
        }
    }

    private static final java.util.Map<String, java.util.Map<Long, Claim>> CLAIMS = new java.util.HashMap<>();

    private static long sector(BlockPos p) {
        return ((long) Math.floorDiv(p.getX(), SECTOR) << 32) ^ (Math.floorDiv(p.getZ(), SECTOR) & 0xFFFFFFFFL);
    }

    private static List<BlockPos> waypoints(ServerLevel level, int floor, int total, boolean generate) {
        List<BlockPos> list = WAYPOINTS.get(floor);
        if (list == null && generate) {
            FloorPlan plan = FloorPlan.generate(level.getSeed(), floor, total);
            PLANS.put(floor, plan);
            list = new java.util.ArrayList<>(plan.mobSpots);
            for (BlockPos extra : new BlockPos[]{plan.center, plan.upArrive, plan.downArrive, plan.boss}) {
                if (extra != null) {
                    list.add(extra);
                }
            }
            if (WAYPOINTS.size() > 40) {
                WAYPOINTS.clear();
                PLANS.clear();
                FIELDS.clear();
            }
            WAYPOINTS.put(floor, list);
        }
        return list;
    }

    private static java.util.Set<Long> seenWaypoints(ServerPlayer owner, int floor) {
        return SEEN_WAYPOINTS.computeIfAbsent(owner.getUUID() + "#" + floor, k -> new java.util.HashSet<>());
    }

    /** A waypoint counts as explored once the echo has stood near it or has seen it across open space. */
    private static void markWaypoints(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        if (!level.dimension().equals(DungeonRegistries.DUNGEON)) {
            return;
        }
        checkWorld(level);
        int floor = Spire.floorAt(e.getX(), e.getZ(), DungeonStore.get(level.getServer()).ensureTower(level.getServer()));
        List<BlockPos> list = floor > 0 ? WAYPOINTS.get(floor) : null;
        if (list == null) {
            return;
        }
        java.util.Set<Long> seen = seenWaypoints(owner, floor);
        Vec3 eye = e.getEyePosition();
        for (BlockPos w : list) {
            if (seen.contains(w.asLong()) || Math.abs(w.getY() - e.getY()) >= 4) {
                continue;
            }
            double dx = w.getX() + 0.5 - e.getX();
            double dz = w.getZ() + 0.5 - e.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < 49) {
                seen.add(w.asLong());
            } else if (d2 < 15 * 15) {
                Vec3 to = new Vec3(w.getX() + 0.5, w.getY() + 1.2, w.getZ() + 0.5);
                if (level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, e)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                    seen.add(w.asLong());
                }
            }
        }
    }

    private static final java.util.Map<String, Integer> ATTEMPTS = new java.util.HashMap<>();
    private static final java.util.Set<String> FLOOR_DONE = new java.util.HashSet<>();

    /**
     * Frontier exploration over the floor's waypoints. Explored waypoints stay explored (there is no second sweep), so an
     * echo never goes back over ground that has been searched. The next target is the unexplored waypoint in the echo's own
     * sector that costs the least walking; when the sector is finished the cheapest unexplored waypoint anywhere else is
     * taken at once, which is the nearest branch not yet searched. Waypoints too far for one path search are approached
     * along a partial path rather than written off.
     */
    private static boolean pickWaypoint(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        checkWorld(level);
        if (e.exploreTarget == null && e.repath-- > 0) {
            return true; // nothing to walk to right now: look again in a moment instead of every tick
        }
        int total = DungeonStore.get(level.getServer()).ensureTower(level.getServer());
        int floor = Spire.floorAt(e.getX(), e.getZ(), total);
        if (floor <= 0) {
            return false;
        }
        List<BlockPos> list = waypoints(level, floor, total, true);
        String ownerKey = owner.getUUID() + "#" + floor;
        java.util.Set<Long> seen = seenWaypoints(owner, floor);
        List<BlockPos> open = new java.util.ArrayList<>();
        for (BlockPos w : list) {
            if (!seen.contains(w.asLong())) {
                open.add(w);
            }
        }
        if (open.isEmpty()) {
            // Everything on this floor has been searched: report once and wait with the owner instead of wandering.
            e.exploreTarget = null;
            e.repath = 40;
            if (FLOOR_DONE.add(ownerKey)) {
                dev.gatebound.Gatebound.LOGGER.info("[echo] seek: floor {} reported fully searched ({} waypoints, {} marked seen)",
                        floor, list.size(), seen.size());
                owner.sendSystemMessage(Component.literal("[Echo] This floor has been searched completely. No unexplored paths are left.")
                        .withStyle(ChatFormatting.GRAY));
            }
            if (e.distanceToSqr(owner) > 6 * 6) {
                e.getNavigation().moveTo(owner, 1.2);
            }
            return true;
        }
        FLOOR_DONE.remove(ownerKey);

        java.util.Map<Long, Claim> claims = CLAIMS.computeIfAbsent(ownerKey, k -> new java.util.HashMap<>());
        long now = level.getGameTime();
        claims.values().removeIf(c -> now - c.tick > 600);
        java.util.Map<Long, List<BlockPos>> bySector = new java.util.HashMap<>();
        for (BlockPos w : open) {
            bySector.computeIfAbsent(sector(w), k -> new java.util.ArrayList<>()).add(w);
        }
        long mine = Long.MIN_VALUE;
        for (java.util.Map.Entry<Long, Claim> c : claims.entrySet()) {
            if (c.getValue().who.equals(e.getUUID())) {
                mine = c.getKey();
            }
        }
        if (mine != Long.MIN_VALUE && !bySector.containsKey(mine)) {
            claims.remove(mine); // sector finished: move on straight away
            mine = Long.MIN_VALUE;
        }
        List<BlockPos> pool = mine != Long.MIN_VALUE ? bySector.get(mine) : null;
        if (pool == null) {
            // Candidates: every unclaimed sector's waypoints (or, if all are claimed, all open ones).
            pool = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Long, List<BlockPos>> s : bySector.entrySet()) {
                if (!claims.containsKey(s.getKey())) {
                    pool.addAll(s.getValue());
                }
            }
            if (pool.isEmpty()) {
                pool = open;
            }
        }
        // Rank by straight-line distance, then judge the nearest few by real walking cost.
        pool.sort(java.util.Comparator.comparingDouble(w -> e.distanceToSqr(w.getX() + 0.5, w.getY(), w.getZ() + 0.5)));
        BlockPos pick = null;
        net.minecraft.world.level.pathfinder.Path pickPath = null;
        int pickCost = Integer.MAX_VALUE;
        BlockPos partial = null;
        net.minecraft.world.level.pathfinder.Path partialPath = null;
        for (int i = 0; i < Math.min(8, pool.size()); i++) {
            BlockPos w = pool.get(i);
            net.minecraft.world.level.pathfinder.Path path = e.getNavigation().createPath(w.getX() + 0.5, w.getY(), w.getZ() + 0.5, 1);
            if (path == null) {
                // No path right now (the echo may be mid-air or boxed in): only give up on it after many failures.
                if (ATTEMPTS.merge(ownerKey + "!" + w.asLong(), 1, Integer::sum) > 12) {
                    seen.add(w.asLong());
                }
                continue;
            }
            if (path.canReach()) {
                if (path.getNodeCount() < pickCost) {
                    pickCost = path.getNodeCount();
                    pick = w;
                    pickPath = path;
                }
            } else if (partial == null) {
                partial = w;
                partialPath = path;
            }
        }
        if (pick == null && partial != null && routeInDungeon(e, level, partial, 1.3)) {
            // Too far or round a corner for one path search: walk the plan's route toward it instead.
            e.exploreTarget = new Vec3(partial.getX() + 0.5, partial.getY(), partial.getZ() + 0.5);
            long sec0 = sector(partial);
            if (mine == Long.MIN_VALUE && !claims.containsKey(sec0)) {
                claims.put(sec0, new Claim(e.getUUID(), now));
                mine = sec0;
            }
            Claim h0 = claims.get(mine);
            if (h0 != null && h0.who.equals(e.getUUID())) {
                h0.tick = now;
            }
            return true;
        }
        if (pick == null && partial != null) {
            pick = partial;
            pickPath = partialPath;
            // A waypoint that keeps ending in partial paths without being reached is a dead end: stop trying it.
            String ak = ownerKey + "@" + pick.asLong();
            int tries = ATTEMPTS.merge(ak, 1, Integer::sum);
            if (tries > 8) {
                seen.add(pick.asLong());
                ATTEMPTS.remove(ak);
            }
        }
        if (pick == null) {
            e.exploreTarget = null;
            e.repath = 20;
            if (e.distanceToSqr(owner) > 8 * 8) {
                e.getNavigation().moveTo(owner, 1.2);
            }
            return true;
        }
        long sec = sector(pick);
        if (mine == Long.MIN_VALUE && !claims.containsKey(sec)) {
            claims.put(sec, new Claim(e.getUUID(), now));
            mine = sec;
        }
        Claim held = claims.get(mine);
        if (held != null && held.who.equals(e.getUUID())) {
            held.tick = now;
        }
        e.exploreTarget = new Vec3(pick.getX() + 0.5, pick.getY(), pick.getZ() + 0.5);
        e.getNavigation().moveTo(pickPath, e.isFlier() ? 1.5 : 1.3);
        return true;
    }

    private static BlockPos findGate(EchoEntity e, ServerLevel level, double sight) {
        DungeonStore store = DungeonStore.get(level.getServer());
        BlockPos best = null;
        double bestD = sight * sight;
        for (DungeonStore.GateRef g : store.gates) {
            if (!g.dim.equals(level.dimension())) {
                continue;
            }
            double d = e.distanceToSqr(g.pos.getX() + 0.5, g.pos.getY(), g.pos.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = g.pos;
            }
        }
        if (level.dimension().equals(DungeonRegistries.DUNGEON)) {
            int floor = Spire.floorAt(e.getX(), e.getZ(), store.ensureTower(level.getServer()));
            if (floor > 0) {
                DungeonStore.FloorState fs = store.floor(floor);
                BlockPos up = fs.upPortal.equals(BlockPos.ZERO) ? BlockPos.ZERO
                        : dev.gatebound.dungeon.PortalPlacer.where(level, fs.upPortal, net.minecraft.core.Direction.EAST);
                if (fs.built && up != null && !up.equals(BlockPos.ZERO)
                        && level.getBlockState(up).getBlock() instanceof GateExitBlock
                        && e.distanceToSqr(up.getX() + 0.5, up.getY(), up.getZ() + 0.5) < bestD) {
                    best = up;
                }
            }
        }
        return best;
    }

    private static String describe(ServerLevel level, BlockPos pos) {
        DungeonStore store = DungeonStore.get(level.getServer());
        int total = store.ensureTower(level.getServer());
        if (level.getBlockState(pos).getBlock() instanceof GateExitBlock) {
            int floor = Spire.floorAt(pos.getX() + 0.5, pos.getZ() + 0.5, total);
            return "Way up to Floor " + (floor + 1);
        }
        for (DungeonStore.GateRef g : store.gates) {
            if (g.dim.equals(level.dimension()) && g.pos.equals(pos) && dev.gatebound.dungeon.Spire.isGate(g.floor)) {
                return dev.gatebound.dungeon.Spire.rank(g.floor, total).label() + " Gate";
            }
        }
        return "Gate";
    }

    private static void announce(EchoEntity e, ServerPlayer owner, ServerLevel level, BlockPos pos) {
        String what = describe(level, pos);
        EchoManager.Found gate = new EchoManager.Found(level.dimension(), pos, what, e.mobName());
        EchoManager.addFound(owner, gate);
        EchoManager.setRally(owner, gate);
        Component msg = Component.literal("[Echo] " + e.mobName() + " found: " + what + " at "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (open the Echoes screen with H to send guides)")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        owner.sendSystemMessage(msg);
        owner.sendOverlayMessage(msg);
        EchoManager.sendRoster(owner, false);
    }

    /** Eye order: fly or run straight to the selected gate and hold there, without waiting for the owner. */
    private static void goTo(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        EchoManager.Found gate = EchoManager.selectedGate(owner);
        if (gate == null || !gate.dim().equals(level.dimension())) {
            if (!e.guideWarned) {
                e.guideWarned = true;
                owner.sendOverlayMessage(Component.literal(gate == null
                        ? "No gate selected. Find one first, then pick it on the H screen."
                        : "The selected gate is in another dimension.").withStyle(ChatFormatting.GRAY));
            }
            seek(e, owner, level);
            return;
        }
        e.guideWarned = false;
        double gx = gate.pos().getX() + 0.5;
        double gz = gate.pos().getZ() + 0.5;
        double toGate = Math.sqrt(e.distanceToSqr(gx, e.getY(), gz));
        if (toGate < 3) {
            e.getNavigation().stop();
            if (e.seekFound == null) {
                e.seekFound = gate.pos();
                owner.sendOverlayMessage(Component.literal(e.mobName() + " reached " + gate.label() + ".")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        } else if (--e.repath <= 0) {
            e.repath = 15;
            double step = Math.min(24, toGate);
            double dx = (gx - e.getX()) / toGate * step;
            double dz = (gz - e.getZ()) / toGate * step;
            e.getNavigation().moveTo(e.getX() + dx, gate.pos().getY() + 1, e.getZ() + dz, 1.2);
        }
    }


    // ---------------------------------------------------------------- getting unstuck

    /** Once a second: counts how long the echo has barely moved. Two or three seconds of that means it is stuck. */
    private static void stuckTick(EchoEntity e) {
        if (e.tickCount % 20 != 0) {
            return;
        }
        double moved = (e.getX() - e.lastX) * (e.getX() - e.lastX) + (e.getZ() - e.lastZ) * (e.getZ() - e.lastZ);
        e.stuckChecks = moved < 2.0 ? e.stuckChecks + 1 : 0;
        if (moved >= 9) {
            e.stuckStrikes = 0;
        }
        e.lastX = e.getX();
        e.lastZ = e.getZ();
    }

    /** A spot the echo could stand on near the given column: free space for its body, ground under it, no water or lava. */
    private static Vec3 standSpot(EchoEntity e, ServerLevel level, double x, double z) {
        if (!level.hasChunkAt(BlockPos.containing(x, e.getY(), z))) {
            return null;
        }
        int[] offsets = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -6, -8};
        for (int dy : offsets) {
            double y = Math.floor(e.getY()) + dy;
            net.minecraft.world.phys.AABB box = e.getBoundingBox().move(x - e.getX(), y - e.getY(), z - e.getZ());
            if (!level.noCollision(e, box) || level.noCollision(e, box.move(0, -0.6, 0))) {
                continue;
            }
            BlockPos feet = BlockPos.containing(x, y, z);
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) {
                continue;
            }
            return new Vec3(x, y, z);
        }
        return null;
    }

    /**
     * Last resort for an echo that cannot path out of where it is (a closed room, odd slabs and stairs, a pit): it slips
     * through the spirit world to the nearest standable spot toward where it was heading. Returns whether it moved.
     */
    private static boolean blink(EchoEntity e, ServerLevel level, double towardX, double towardZ) {
        if (e.isFlier()) {
            return false;
        }
        double dx = towardX - e.getX();
        double dz = towardZ - e.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        dx /= len;
        dz /= len;
        for (double dist : new double[]{12, 8, 5, 16}) {
            for (double side : new double[]{0, 4, -4, 8, -8}) {
                Vec3 spot = standSpot(e, level, e.getX() + dx * dist - dz * side, e.getZ() + dz * dist + dx * side);
                if (spot == null) {
                    continue;
                }
                level.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 0.8, e.getZ(), 15, 0.3, 0.5, 0.3, 0.2);
                e.getNavigation().stop();
                e.teleportTo(spot.x, spot.y, spot.z);
                level.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 0.8, e.getZ(), 15, 0.3, 0.5, 0.3, 0.2);
                e.stuckChecks = 0;
                e.stuckStrikes = 0;
                e.repath = 0;
                e.lastX = e.getX();
                e.lastZ = e.getZ();
                return true;
            }
        }
        return false;
    }

    /** Right-click on a Guide echo: lead the owner to the selected gate, or (clicked again) stop and wait at the owner's side. */
    static void guideSignal(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        EchoManager.Found gate = EchoManager.selectedGate(owner);
        if (gate == null || !gate.dim().equals(level.dimension())) {
            owner.sendOverlayMessage(Component.literal(e.mobName() + ": no gate has been found yet. I will wait with you.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        e.guideGo = !e.guideGo;
        e.guideTarget = gate.pos();
        e.guideArrived = false;
        e.repath = 0;
        owner.sendOverlayMessage(Component.literal(e.guideGo
                ? e.mobName() + ": follow me to the " + gate.label() + "."
                : e.mobName() + ": I will wait here with you.").withStyle(ChatFormatting.AQUA));
    }

    /**
     * Guide: wait beside the owner until a gate is known, announce it, and keep waiting until the owner right-clicks the
     * echo. Then take the best route to the gate, waiting for the owner when they fall behind, and hold at the gate.
     */
    private static void guide(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        EchoManager.Found gate = EchoManager.selectedGate(owner);
        if (gate == null || !gate.dim().equals(level.dimension())) {
            e.guideGo = false;
            e.guideReady = false;
            if (!e.guideWarned) {
                e.guideWarned = true;
                owner.sendSystemMessage(Component.literal(gate == null
                        ? "[Echo] " + e.mobName() + ": waiting with you until a gate is found. Send a group to Seek first."
                        : "[Echo] " + e.mobName() + ": the selected gate is in another dimension.").withStyle(ChatFormatting.GRAY));
            }
            follow(e, owner, 5, 2.5);
            return;
        }
        e.guideWarned = false;
        if (e.guideGo && !gate.pos().equals(e.guideTarget)) {
            e.guideGo = false; // a different gate was selected: wait for a new signal
        }
        if (!e.guideGo) {
            if (!e.guideReady) {
                e.guideReady = true;
                owner.sendSystemMessage(Component.literal("[Echo] " + e.mobName() + ": " + gate.label()
                        + " found. Right-click me and I will lead the way.").withStyle(ChatFormatting.AQUA));
            }
            follow(e, owner, 5, 2.5);
            return;
        }
        double gx = gate.pos().getX() + 0.5;
        double gz = gate.pos().getZ() + 0.5;
        double toGate = Math.sqrt(e.distanceToSqr(gx, e.getY(), gz));
        if (toGate < 3) {
            e.getNavigation().stop();
            if (!e.guideArrived) {
                e.guideArrived = true;
                owner.sendOverlayMessage(Component.literal(e.mobName() + " reached the " + gate.label() + ".")
                        .withStyle(ChatFormatting.AQUA));
            }
        } else if (e.distanceToSqr(owner) > 20 * 20) {
            e.getNavigation().stop(); // the owner is lagging: wait for them
            e.getLookControl().setLookAt(owner);
        } else {
            stuckTick(e);
            if (e.stuckChecks >= 4 && blink(e, level, gx, gz)) {
                e.stuckChecks = 0;
            }
            if (--e.repath <= 0) {
                e.repath = 20;
                if (!routeInDungeon(e, level, gate.pos(), 1.25)) {
                    routeTo(e, level, gate.pos());
                }
            }
        }
        if (e.tickCount % 10 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
        }
        if (e.tickCount % 100 == 0 && toGate >= 3) {
            int meters = (int) Math.sqrt(owner.distanceToSqr(gx, gate.pos().getY(), gz));
            owner.sendOverlayMessage(Component.literal("Following " + e.mobName() + ": " + gate.label() + ", " + meters + " blocks")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * Best route to a gate: the full path when the pathfinder can reach it, otherwise the reachable waypoint (nearest
     * step first, then sidesteps around obstacles) that leaves the least distance to the gate. Fliers go straight.
     */
    private static void routeTo(EchoEntity e, ServerLevel level, BlockPos gate) {
        double gx = gate.getX() + 0.5;
        double gy = gate.getY();
        double gz = gate.getZ() + 0.5;
        if (e.isFlier()) {
            e.getNavigation().moveTo(gx, gy + 2, gz, 1.3);
            return;
        }
        net.minecraft.world.level.pathfinder.Path full = e.getNavigation().createPath(gx, gy, gz, 2);
        if (full != null && full.canReach()) {
            e.getNavigation().moveTo(full, 1.25);
            return;
        }
        double dist = Math.max(1, Math.sqrt(e.distanceToSqr(gx, e.getY(), gz)));
        double ux = (gx - e.getX()) / dist;
        double uz = (gz - e.getZ()) / dist;
        boolean overworld = level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
        for (double step : new double[]{44, 30, 18, 10}) {
            if (step > dist) {
                continue;
            }
            net.minecraft.world.level.pathfinder.Path best = null;
            double bestRemain = Double.MAX_VALUE;
            for (double lateral : new double[]{0, 10, -10, 20, -20}) {
                double wx = e.getX() + ux * step - uz * lateral;
                double wz = e.getZ() + uz * step + ux * lateral;
                if (!level.hasChunkAt(BlockPos.containing(wx, e.getY(), wz))) {
                    continue;
                }
                double wy = overworld ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int) Math.floor(wx), (int) Math.floor(wz)) : gy;
                net.minecraft.world.level.pathfinder.Path p = e.getNavigation().createPath(wx, wy, wz, 2);
                if (p == null) {
                    continue;
                }
                double remain = (wx - gx) * (wx - gx) + (wz - gz) * (wz - gz);
                if (remain < bestRemain) {
                    bestRemain = remain;
                    best = p;
                }
            }
            if (best != null) {
                e.getNavigation().moveTo(best, 1.25);
                return;
            }
        }
        e.repath = 50; // nothing reachable right now: look again shortly
        e.getNavigation().moveTo(gx, gy, gz, 1.1);
    }
}
