package dev.gatebound.dungeon;

import dev.gatebound.Gatebound;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sequential queue of long-running world edits (floor builds, tower builds). One job runs at a time, each tick it
 * gets a block budget and a wall-clock deadline, so nothing ever freezes the server thread. Players who are waiting
 * for a job are held in place (immune and unable to walk) and get a progress message, then are teleported on completion.
 */
public final class WorkQueue {
    /** Where a travelling player should appear on a floor. */
    public enum Dest {
        BOTTOM, TOP, RIFT
    }

    /** Per-tick allowance handed to the running job. */
    public static final class Budget {
        private int blocks;
        private final long deadline;

        Budget(int blocks, long deadline) {
            this.blocks = blocks;
            this.deadline = deadline;
        }

        public boolean ok() {
            return blocks > 0 && System.nanoTime() < deadline;
        }

        public void use(int n) {
            blocks -= n;
        }
    }

    public abstract static class Job {
        public final String key;
        public final String label;
        public final Map<UUID, Dest> waiting = new LinkedHashMap<>();
        public final List<Runnable> onDone = new ArrayList<>();
        public int ageTicks;
        public boolean failed;

        protected Job(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** Advances the job; returns true when finished. */
        public abstract boolean step(MinecraftServer server, Budget budget);

        public abstract int percent();

        /** Short description of the current phase, for diagnostics. */
        public String status() {
            return "";
        }

        /** Called once when the job is finished or abandoned; release any chunk tickets here. */
        public void cleanup(MinecraftServer server) {
        }
    }

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final Set<UUID> HOLDING = new HashSet<>();

    private WorkQueue() {
    }

    public static boolean isHolding(UUID id) {
        return HOLDING.contains(id);
    }

    public static Job find(String key) {
        for (Job j : QUEUE) {
            if (j.key.equals(key)) {
                return j;
            }
        }
        return null;
    }

    public static boolean isIdle() {
        return QUEUE.isEmpty();
    }

    public static int size() {
        return QUEUE.size();
    }

    public static void enqueue(Job job) {
        QUEUE.add(job);
    }

    public static void reset(MinecraftServer server) {
        for (Job j : QUEUE) {
            try {
                j.cleanup(server);
            } catch (RuntimeException ignored) {
                // server is stopping
            }
        }
        QUEUE.clear();
        HOLDING.clear();
    }

    public static void tick(MinecraftServer server) {
        Job head = QUEUE.peek();
        if (head == null) {
            return;
        }
        head.ageTicks++;
        boolean done;
        Budget budget = new Budget(Math.max(100, DungeonConfig.buildBlocksPerTick),
                System.nanoTime() + Math.max(2, DungeonConfig.buildMillisPerTick) * 1_000_000L);
        try {
            long t0 = System.nanoTime();
            String phaseBefore = head.status();
            done = head.step(server, budget);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > 40) {
                Gatebound.LOGGER.warn("Job {} spent {} ms in one tick (phase {} -> {})", head.key, ms, phaseBefore, head.status());
            }
            if (!done && head.ageTicks > 20000) {
                Gatebound.LOGGER.error("Job {} timed out", head.key);
                head.failed = true;
                done = true;
            }
        } catch (RuntimeException | StackOverflowError e) {
            Gatebound.LOGGER.error("Job {} failed", head.key, e);
            head.failed = true;
            done = true;
        }
        if (server.getTickCount() % 8 == 0) {
            hold(server);
        }
        if (done) {
            QUEUE.poll();
            try {
                head.cleanup(server);
            } catch (RuntimeException e) {
                Gatebound.LOGGER.warn("Job cleanup failed", e);
            }
            finish(server, head);
        }
    }

    /** Applies (or refreshes) the hold on every waiting player and shows progress. */
    public static void hold(MinecraftServer server) {
        int index = 0;
        for (Job j : QUEUE) {
            Component msg = index == 0
                    ? Component.translatableWithFallback("gatebound.tower.building", "%s takes shape... %s%%", j.label, j.percent())
                    : Component.translatableWithFallback("gatebound.tower.queued", "Waiting for the Spire...");
            for (UUID id : j.waiting.keySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null) {
                    continue;
                }
                HOLDING.add(id);
                p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 24, 7, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 24, 4, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 24, 0, false, false));
                p.sendOverlayMessage(msg.copy().withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            index++;
        }
    }

    private static void finish(MinecraftServer server, Job job) {
        for (Map.Entry<UUID, Dest> e : job.waiting.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            HOLDING.remove(e.getKey());
            if (p == null) {
                continue;
            }
            p.removeEffect(MobEffects.SLOWNESS);
            p.removeEffect(MobEffects.RESISTANCE);
            p.removeEffect(MobEffects.SLOW_FALLING);
            if (job.failed) {
                p.sendSystemMessage(Component.literal("[Gatebound] The floor could not be formed. Try again.").withStyle(ChatFormatting.RED));
            } else if (job instanceof FloorJob fj) {
                DungeonManager.completeTravel(server, p, fj.floor, e.getValue());
            }
        }
        for (Runnable r : job.onDone) {
            try {
                r.run();
            } catch (RuntimeException ex) {
                Gatebound.LOGGER.warn("Job completion callback failed", ex);
            }
        }
    }
}
