package dev.gatebound.combat;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Wind-up before a hostile melee mob swings. While this goal runs it owns the mob's movement and looking, and it is
 * the only thing allowed to land hits: PlayerCombat cancels any other melee hit from a mob that has this goal.
 */
public final class TelegraphGoal extends Goal {
    private static final net.minecraft.core.particles.ParticleOptions RED = Compat.dust(0xFF2020, 1.4f);

    /** The mob currently inside its strike call, and whether that strike is heavy. */
    private static Mob striking;
    private static boolean strikingHeavy;

    private final PathfinderMob mob;
    private boolean recovering;
    private boolean heavy;
    private int timer;
    private int recoverFor;

    private TelegraphGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static void install(Entity entity, Level level) {
        if (level.isClientSide() || !(entity instanceof Monster monster) || hasGoal(monster)) {
            return;
        }
        boolean melee = Compat.goals(monster).getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof MeleeAttackGoal);
        if (melee) {
            Compat.goals(monster).addGoal(1, new TelegraphGoal(monster));
        }
    }

    public static boolean hasGoal(Mob mob) {
        return Compat.goals(mob).getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof TelegraphGoal);
    }

    public static boolean isStriking(Mob mob) {
        return striking == mob;
    }

    /** True when this mob's hit is the heavy, unparryable kind. */
    public static boolean isHeavyStrike(Entity attacker) {
        return striking != null && striking == attacker && strikingHeavy;
    }

    private ServerPlayer target() {
        return mob.getTarget() instanceof ServerPlayer p && p.isAlive() && !p.isSpectator() ? p : null;
    }

    private boolean staggered() {
        return CombatState.isStaggered(mob.getUUID(), mob.level().getServer().getTickCount());
    }

    @Override
    public boolean canUse() {
        ServerPlayer t = target();
        return CombatConfig.telegraphEnabled && t != null && !staggered() && mob.isWithinMeleeAttackRange(t)
                && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        ServerPlayer t = target();
        return CombatConfig.telegraphEnabled && t != null && !staggered() && mob.distanceTo(t) < 3.5f;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        beginWindup();
    }

    @Override
    public void stop() {
        timer = 0;
        recovering = false;
    }

    private void beginWindup() {
        recovering = false;
        timer = 0;
        heavy = mob.getRandom().nextDouble() < CombatConfig.heavyChance;
        mob.getNavigation().stop();
        if (mob.level() instanceof ServerLevel level) {
            // Cue: a distinct sound at the start of the wind-up.
            if (heavy) {
                level.playSound(null, mob.blockPosition(), GateboundSounds.of(SoundEvents.RAVAGER_ROAR), SoundSource.HOSTILE, 0.9f, 1.4f);
            } else {
                level.playSound(null, mob.blockPosition(), GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), SoundSource.HOSTILE, 0.8f, 0.7f);
            }
        }
    }

    @Override
    public void tick() {
        ServerPlayer t = target();
        if (t == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(t, 60f, 60f);
        timer++;

        if (recovering) {
            if (timer >= recoverFor) {
                if (mob.isWithinMeleeAttackRange(t)) {
                    beginWindup();
                }
            }
            return;
        }

        int total = heavy ? CombatConfig.heavyWindupTicks : CombatConfig.windupTicks;
        Vec3 head = new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() + 0.3, mob.getZ());
        if (heavy) {
            level.sendParticles(RED, head.x, head.y, head.z, 3, 0.25, 0.15, 0.25, 0);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, head.x, head.y, head.z, 1, 0.2, 0.1, 0.2, 0);
        } else if (timer % 2 == 0) {
            level.sendParticles(ParticleTypes.CRIT, head.x, head.y, head.z, 2, 0.2, 0.1, 0.2, 0.02);
        }
        if (timer >= total) {
            strike(t, level);
        }
    }

    private void strike(ServerPlayer t, ServerLevel level) {
        Compat.swing(mob, InteractionHand.MAIN_HAND, true);
        if (mob.isWithinMeleeAttackRange(t)) {
            striking = mob;
            strikingHeavy = heavy;
            try {
                mob.doHurtTarget(level, t);
                if (heavy) {
                    Vec3 away = t.position().subtract(mob.position());
                    Vec3 dir = new Vec3(away.x, 0, away.z).normalize();
                    t.push(dir.x * 0.7, 0.25, dir.z * 0.7);
                    t.needsSync = true;
                }
            } finally {
                striking = null;
                strikingHeavy = false;
            }
        }
        recovering = true;
        timer = 0;
        recoverFor = CombatConfig.attackRecoverTicks + mob.getRandom().nextInt(10);
    }
}
