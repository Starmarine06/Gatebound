package dev.gatebound.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side, in-memory combat state. Nothing here is saved; it resets on restart. */
public final class CombatState {
    private static final Map<UUID, Integer> PARRY_START = new HashMap<>();
    private static final Map<UUID, Integer> PARRY_READY_AT = new HashMap<>();
    private static final Map<UUID, Posture> POSTURE = new HashMap<>();
    private static final Map<UUID, Integer> STAGGERED_UNTIL = new HashMap<>();
    private static final Set<UUID> DEBUG = new HashSet<>();

    private static final class Posture {
        double value;
        int lastHitTick;
    }

    private CombatState() {
    }

    /** Starts a parry attempt if the player is not still recovering from the last one. Returns true if started. */
    public static boolean startParry(UUID player, int now) {
        if (now < PARRY_READY_AT.getOrDefault(player, 0)) {
            return false;
        }
        PARRY_START.put(player, now);
        PARRY_READY_AT.put(player, now + CombatConfig.parryCooldownTicks);
        return true;
    }

    /** Ticks since the last parry press, or -1 if there has been none. */
    public static int ticksSinceParry(UUID player, int now) {
        Integer start = PARRY_START.get(player);
        return start == null ? -1 : now - start;
    }

    public static boolean isParrying(UUID player, int now) {
        int since = ticksSinceParry(player, now);
        return since >= 0 && since <= CombatConfig.parryWindowTicks;
    }

    /** Adds posture to a mob (recovering any idle posture first) and returns the new value. */
    public static double addPosture(UUID mob, double amount, int now) {
        Posture p = POSTURE.computeIfAbsent(mob, id -> new Posture());
        recover(p, now);
        p.value = Math.min(CombatConfig.postureMax, p.value + amount);
        p.lastHitTick = now;
        return p.value;
    }

    public static double posture(UUID mob, int now) {
        Posture p = POSTURE.get(mob);
        if (p == null) {
            return 0;
        }
        recover(p, now);
        return p.value;
    }

    public static void resetPosture(UUID mob) {
        POSTURE.remove(mob);
    }

    private static void recover(Posture p, int now) {
        int idle = now - p.lastHitTick - CombatConfig.postureRecoveryDelay;
        if (idle > 0) {
            p.value = Math.max(0, p.value - idle * CombatConfig.postureRecoveryPerTick);
            p.lastHitTick = now - CombatConfig.postureRecoveryDelay;
        }
    }

    public static void stagger(UUID mob, int now) {
        STAGGERED_UNTIL.put(mob, now + CombatConfig.staggerTicks);
    }

    public static boolean isStaggered(UUID mob, int now) {
        return STAGGERED_UNTIL.getOrDefault(mob, 0) > now;
    }

    public static void clearStagger(UUID mob) {
        STAGGERED_UNTIL.remove(mob);
    }

    /** Forget everything about an entity that died or left. */
    public static void forget(UUID id) {
        PARRY_START.remove(id);
        PARRY_READY_AT.remove(id);
        POSTURE.remove(id);
        STAGGERED_UNTIL.remove(id);
        DEBUG.remove(id);
    }

    public static boolean toggleDebug(UUID player) {
        if (!DEBUG.add(player)) {
            DEBUG.remove(player);
            return false;
        }
        return true;
    }

    public static boolean debug(UUID player) {
        return DEBUG.contains(player);
    }
}
