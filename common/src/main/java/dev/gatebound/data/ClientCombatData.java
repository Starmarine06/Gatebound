package dev.gatebound.data;

import dev.gatebound.network.ParryStatePayload;
import dev.gatebound.network.PostureSyncPayload;

/** Client-side combat info for the HUD. Times use System.currentTimeMillis so the HUD animates smoothly. */
public final class ClientCombatData {
    public static int targetId = -1;
    public static int posture;
    public static int postureMax = 100;
    public static boolean staggered;
    public static long targetUpdatedMs;

    public static long parryStartMs;
    public static long parryWindowMs;
    public static long parryCooldownMs;

    /** Static holder; not instantiable. */
    private ClientCombatData() {
    }

    /** Stores the latest posture sync. */
    public static void update(PostureSyncPayload p) {
        targetId = p.entityId();
        posture = p.value();
        postureMax = Math.max(1, p.max());
        staggered = p.staggered();
        targetUpdatedMs = System.currentTimeMillis();
    }

    /** Stores the latest parry state. */
    public static void update(ParryStatePayload p) {
        parryStartMs = System.currentTimeMillis();
        parryWindowMs = p.windowTicks() * 50L;
        parryCooldownMs = p.cooldownTicks() * 50L;
    }
}
