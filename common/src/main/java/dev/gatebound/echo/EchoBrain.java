package dev.gatebound.echo;

import dev.gatebound.dungeon.DungeonRegistries;
import dev.gatebound.dungeon.DungeonStore;
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
        }

        if (kit.special() == EchoKits.Special.HEALER) {
            support(e, owner, level);
        }
        if ((e.tickCount + info.slot) % 5 == 0) {
            acquire(e, owner, level, mode);
        }
        LivingEntity target = e.getTarget();
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
            case EchoRoster.GUARD -> follow(e, owner, 4.5, 2.5);
            case EchoRoster.SEEK -> seek(e, owner, level);
            case EchoRoster.GUIDE -> guide(e, owner, level);
            case EchoRoster.GOTO -> goTo(e, owner, level);
            default -> follow(e, owner, 7, 4);
        }
    }

    // ---------------------------------------------------------------- targeting

    /** Scouts and gate-runners avoid fights unless something is right on top of them. */
    private static boolean passive(int mode) {
        return mode == EchoRoster.SEEK || mode == EchoRoster.GOTO;
    }

    private static boolean validTarget(EchoEntity e, LivingEntity t, ServerPlayer owner) {
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
        LivingEntity hit = owner.getLastHurtMob();
        if (hit != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && validTarget(e, hit, owner)
                && !passive(mode)) {
            pick = hit;
        }
        LivingEntity hurtBy = owner.getLastHurtByMob();
        if (pick == null && hurtBy != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100
                && validTarget(e, hurtBy, owner) && !passive(mode)) {
            pick = hurtBy;
        }
        if (pick == null) {
            // Guards watch the area around the owner; the others only what comes near themselves.
            boolean guard = mode == EchoRoster.GUARD;
            double radius = guard ? 16 : (mode == EchoRoster.FOLLOW ? 9 : 4);
            AABB box = (guard ? owner.getBoundingBox() : e.getBoundingBox()).inflate(radius, 6, radius);
            double best = Double.MAX_VALUE;
            for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, box,
                    m -> validTarget(e, m, owner) && threat(m, e, owner))) {
                double d = e.distanceToSqr(m);
                if (d < best) {
                    best = d;
                    pick = m;
                }
            }
            // Anything hunting the owner, even from outside the box.
            if (pick == null && !passive(mode)) {
                for (Mob m : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(22, 8, 22),
                        m -> m.getTarget() == owner && validTarget(e, m, owner))) {
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
        target.hurtServer(level, level.damageSources().mobAttack(e), (float) (e.attackDamage() * kit.damageMult()));
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

    private static void trail(ServerLevel level, Vec3 from, Vec3 to, net.minecraft.core.particles.ParticleOptions particle) {
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
                m.hurtServer(level, level.damageSources().mobAttack(e), (float) (e.attackDamage() * 2.5));
                Vec3 dir = m.position().subtract(e.position()).normalize();
                m.push(dir.x * 1.1, 0.5, dir.z * 1.1);
                m.needsSync = true;
            }
        }
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
        if (e.seekFound != null) {
            if (e.distanceToSqr(e.seekFound.getX() + 0.5, e.seekFound.getY(), e.seekFound.getZ() + 0.5) > 9) {
                if (--e.repath <= 0) {
                    e.repath = 15;
                    e.getNavigation().moveTo(e.seekFound.getX() + 0.5, e.seekFound.getY() + (e.isFlier() ? 2 : 0),
                            e.seekFound.getZ() + 0.5, 1.2);
                }
            } else {
                e.getNavigation().stop();
            }
            if (e.tickCount % 400 == 0) {
                announce(e, owner, level, e.seekFound);
            }
            return;
        }
        markExplored(e, owner, level);
        if (e.tickCount % 10 == 0) {
            BlockPos found = findGate(e, level, e.isFlier() ? 80 : 56);
            if (found != null) {
                e.seekFound = found;
                e.exploreTarget = null;
                announce(e, owner, level, found);
                return;
            }
        }
        // Detect getting stuck: not having moved for a while means the target is unreachable.
        if (e.tickCount % 20 == 0) {
            double moved = (e.getX() - e.lastX) * (e.getX() - e.lastX) + (e.getZ() - e.lastZ) * (e.getZ() - e.lastZ);
            e.stuckChecks = moved < 2.0 ? e.stuckChecks + 1 : 0;
            e.lastX = e.getX();
            e.lastZ = e.getZ();
        }
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
                BlockPos up = fs.upPortal;
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
        EchoManager.addFound(owner, new EchoManager.Found(level.dimension(), pos, what, e.mobName()));
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

    /** Guide: lead the owner to the selected gate, waiting whenever the owner falls behind. */
    private static void guide(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        EchoManager.Found gate = EchoManager.selectedGate(owner);
        if (gate == null || !gate.dim().equals(level.dimension())) {
            if (!e.guideWarned) {
                e.guideWarned = true;
                Component msg = Component.literal(gate == null
                        ? "[Echo] " + e.mobName() + ": no gate has been found yet. Send a group to Seek Gate first."
                        : "[Echo] " + e.mobName() + ": the selected gate is in another dimension.")
                        .withStyle(ChatFormatting.GRAY);
                owner.sendSystemMessage(msg);
            }
            follow(e, owner, 7, 4);
            return;
        }
        e.guideWarned = false;
        double gx = gate.pos().getX() + 0.5;
        double gz = gate.pos().getZ() + 0.5;
        double toGate = Math.sqrt(e.distanceToSqr(gx, e.getY(), gz));
        double ownerBehind = e.distanceToSqr(owner);
        if (toGate < 3) {
            e.getNavigation().stop();
        } else if (ownerBehind > 14 * 14) {
            // The owner is lagging: wait for them.
            e.getNavigation().stop();
            e.getLookControl().setLookAt(owner);
        } else if (--e.repath <= 0) {
            e.repath = 15;
            double step = Math.min(24, toGate);
            double dx = (gx - e.getX()) / toGate * step;
            double dz = (gz - e.getZ()) / toGate * step;
            e.getNavigation().moveTo(e.getX() + dx, gate.pos().getY(), e.getZ() + dz, 1.0);
        }
        if (e.tickCount % 10 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
        }
        if (e.tickCount % 100 == 0) {
            int meters = (int) Math.sqrt(owner.distanceToSqr(gx, gate.pos().getY(), gz));
            owner.sendOverlayMessage(Component.literal("Following " + e.mobName() + ": " + gate.label() + ", " + meters + " blocks")
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
