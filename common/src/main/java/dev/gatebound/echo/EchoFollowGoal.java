package dev.gatebound.echo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Walks an Echo toward its owner (follow mode) or back to its guard post (guard mode) when idle. */
public final class EchoFollowGoal extends Goal {
    private final Mob mob;
    private boolean moving;
    private int repath;

    public EchoFollowGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Returns {x, y, z, startDistSq, stopDistSq} for where the mob should be, or null if it needs nothing. */
    private double[] goal() {
        EchoManager.Info info = EchoManager.info(mob);
        if (info == null || !(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(info.owner);
        if (owner == null || owner.level() != level) {
            return null;
        }
        if (EchoManager.isGuard(owner)) {
            return new double[]{info.gx, info.gy, info.gz, 36, 9};
        }
        return new double[]{owner.getX(), owner.getY(), owner.getZ(), 49, 16};
    }

    @Override
    public boolean canUse() {
        double[] g = goal();
        return g != null && mob.distanceToSqr(g[0], g[1], g[2]) > g[3];
    }

    @Override
    public boolean canContinueToUse() {
        double[] g = goal();
        return g != null && mob.distanceToSqr(g[0], g[1], g[2]) > g[4];
    }

    @Override
    public void start() {
        moving = true;
        repath = 0;
    }

    @Override
    public void stop() {
        moving = false;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--repath > 0) {
            return;
        }
        repath = 10;
        double[] g = goal();
        if (g != null) {
            mob.getNavigation().moveTo(g[0], g[1], g[2], 1.15);
        }
    }
}
