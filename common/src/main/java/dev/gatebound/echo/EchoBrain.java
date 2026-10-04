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
    private static final double SEEK_SIGHT = 40;

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
        int mode = EchoManager.modeOf(owner, info.slot);
        EchoKits.Kit kit = EchoKits.of(e.mobType());
        if (e.attackCooldown > 0) {
            e.attackCooldown--;
        }
        if (e.abilityCooldown > 0) {
            e.abilityCooldown--;
        }
        if (mode != EchoRoster.Entry.SEEK) {
            e.seekFound = null;
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
            case EchoRoster.Entry.GUARD -> follow(e, owner, 4.5, 2.5);
            case EchoRoster.Entry.SEEK -> seek(e, owner, level);
            default -> follow(e, owner, 7, 4);
        }
    }

    // ---------------------------------------------------------------- targeting

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
                && mode != EchoRoster.Entry.SEEK) {
            pick = hit;
        }
        LivingEntity hurtBy = owner.getLastHurtByMob();
        if (pick == null && hurtBy != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100
                && validTarget(e, hurtBy, owner) && mode != EchoRoster.Entry.SEEK) {
            pick = hurtBy;
        }
        if (pick == null) {
            // Guards watch the area around the owner; the others only what comes near themselves.
            boolean guard = mode == EchoRoster.Entry.GUARD;
            double radius = guard ? 16 : (mode == EchoRoster.Entry.FOLLOW ? 9 : 4);
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
            if (pick == null && mode != EchoRoster.Entry.SEEK) {
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

    /** Scout for a gate: wander around the owner, and once a gate is within sight walk to it and report it. */
    private static void seek(EchoEntity e, ServerPlayer owner, ServerLevel level) {
        if (e.seekFound != null) {
            if (e.distanceToSqr(e.seekFound.getX() + 0.5, e.seekFound.getY(), e.seekFound.getZ() + 0.5) > 9) {
                if (--e.repath <= 0) {
                    e.repath = 15;
                    e.getNavigation().moveTo(e.seekFound.getX() + 0.5, e.seekFound.getY(), e.seekFound.getZ() + 0.5, 1.1);
                }
            } else {
                e.getNavigation().stop();
            }
            if (e.tickCount % 400 == 0) {
                announce(e, owner, level, e.seekFound);
            }
            return;
        }
        if (e.tickCount % 20 == 0) {
            BlockPos found = findGate(e, level);
            if (found != null) {
                e.seekFound = found;
                announce(e, owner, level, found);
                return;
            }
        }
        double fromOwner = e.distanceToSqr(owner);
        if (--e.wanderCooldown <= 0 || fromOwner > 64 * 64 || !e.getNavigation().isInProgress()) {
            e.wanderCooldown = 120 + e.getRandom().nextInt(80);
            double angle = e.getRandom().nextDouble() * Math.PI * 2;
            double dist = 18 + e.getRandom().nextDouble() * 28;
            double cx = fromOwner > 64 * 64 ? owner.getX() : e.getX();
            double cz = fromOwner > 64 * 64 ? owner.getZ() : e.getZ();
            e.getNavigation().moveTo(cx + Math.cos(angle) * dist, e.getY(), cz + Math.sin(angle) * dist, 1.0);
        }
    }

    private static BlockPos findGate(EchoEntity e, ServerLevel level) {
        DungeonStore store = DungeonStore.get(level.getServer());
        BlockPos best = null;
        double bestD = SEEK_SIGHT * SEEK_SIGHT;
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

    private static void announce(EchoEntity e, ServerPlayer owner, ServerLevel level, BlockPos pos) {
        String what = "a gate";
        DungeonStore store = DungeonStore.get(level.getServer());
        int total = store.ensureTower(level.getServer());
        if (level.getBlockState(pos).getBlock() instanceof GateExitBlock) {
            int floor = Spire.floorAt(pos.getX() + 0.5, pos.getZ() + 0.5, total);
            what = "the way up to Floor " + (floor + 1);
        } else {
            for (DungeonStore.GateRef g : store.gates) {
                if (g.dim.equals(level.dimension()) && g.pos.equals(pos) && g.floor > 0) {
                    what = "a gate to Floor " + g.floor;
                }
            }
        }
        Component msg = Component.literal("[Echo] " + e.mobName() + " found " + what + " at "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.LIGHT_PURPLE);
        owner.sendSystemMessage(msg);
        owner.sendOverlayMessage(msg);
    }
}
