package dev.gatebound.data;

import dev.gatebound.network.PlayerStatusPayload;

/** Client-side stamina and player posture for the HUD. */
public final class ClientStaminaData {
    public static int stamina = 50;
    public static int maxStamina = 50;
    public static int posture;
    public static int maxPosture = 100;
    public static boolean staggered;
    public static long dodgeStartMs;
    public static long dodgeCooldownMs;
    public static long lastPostureMs;

    /** Static holder; not instantiable. */
    private ClientStaminaData() {
    }

    /** Stores the latest stamina/status sync. */
    public static void update(PlayerStatusPayload p) {
        stamina = p.stamina();
        maxStamina = Math.max(1, p.maxStamina());
        if (p.posture() != posture) {
            lastPostureMs = System.currentTimeMillis();
        }
        posture = p.posture();
        maxPosture = Math.max(1, p.maxPosture());
        staggered = p.staggered();
        if (p.dodgeCooldownTicks() > 0) {
            dodgeStartMs = System.currentTimeMillis();
            dodgeCooldownMs = p.dodgeCooldownTicks() * 50L;
        }
    }
}
