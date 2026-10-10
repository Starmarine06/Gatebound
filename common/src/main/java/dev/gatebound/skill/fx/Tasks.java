package dev.gatebound.skill.fx;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Class-neutral helpers for visuals: a tick-driven task list, delayed actions, sounds and a few particle shortcuts.
 * Every class's effects use this; MageCore delegates to it. State is in memory only and cleared on server stop.
 */
public final class Tasks {
    /** Something that runs every server tick until it returns true. */
    @FunctionalInterface
    public interface Ticker {
        boolean tick(MinecraftServer server, long now);
    }

    private static final List<Ticker> TICKERS = new ArrayList<>();

    /** Static holder; not instantiable. */
    private Tasks() {
    }

    /** Hooks the tick driver; called once from MageCore.init. */
    public static void init() {
        TickEvent.SERVER_POST.register(Tasks::tick);
        LifecycleEvent.SERVER_STOPPED.register(server -> TICKERS.clear());
    }

    /** Runs every ticker once; drops finished or failing ones. */
    private static void tick(MinecraftServer server) {
        if (TICKERS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        for (Ticker t : new ArrayList<>(TICKERS)) {
            boolean done;
            try {
                done = t.tick(server, now);
            } catch (RuntimeException e) {
                done = true; // a broken effect must not take the tick loop down with it
            }
            if (done) {
                TICKERS.remove(t);
            }
        }
    }

    /** Starts a ticker that runs every tick until it returns true. */
    public static void run(Ticker ticker) {
        TICKERS.add(ticker);
    }

    /** Runs an action after some ticks. */
    public static void after(int ticks, Consumer<MinecraftServer> action) {
        long[] due = {-1};
        run((server, now) -> {
            if (due[0] < 0) {
                due[0] = now + ticks;
            }
            if (now < due[0]) {
                return false;
            }
            action.accept(server);
            return true;
        });
    }

    /** Looks a player up by UUID. */
    public static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && p.isAlive() ? p : null;
    }

    /** Plays a sound at the player's feet at full volume. */
    public static void sound(ServerPlayer player, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    /** Server tick count, as seen from the player's server. */
    public static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    /** Plays a sound at a position. */
    public static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** Spawns particles at a position. */
    public static void particles(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread, double speed) {
        level.sendParticles(type, at.x, at.y, at.z, count, spread, spread * 0.6, spread, speed);
    }

    /** Bright flash of light at a position. */
    public static void flash(ServerLevel level, double x, double y, double z) {
        //? if >=26.1 {
        level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, -1), x, y, z, 1, 0, 0, 0, 0);
        //?} else {
        /*level.sendParticles(ParticleTypes.FLASH, x, y, z, 1, 0, 0, 0, 0);
        *///?}
    }

    /** A line of particles between two points. */
    public static void line(ServerLevel level, ParticleOptions type, Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from);
        int points = (int) Math.max(2, step.length() * 2);
        for (int i = 0; i <= points; i++) {
            Vec3 q = from.add(step.scale(i / (double) points));
            level.sendParticles(type, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
        }
    }
}
