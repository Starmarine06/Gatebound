package dev.gatebound.data;

import dev.gatebound.network.ManaSyncPayload;
import dev.gatebound.network.SkillCooldownPayload;

import java.util.HashMap;
import java.util.Map;

/** Client-side mana and skill cooldowns for the HUD. */
public final class ClientSkillData {
    public static int mana;
    public static int maxMana = 40;
    private static final Map<String, long[]> COOLDOWNS = new HashMap<>();

    private ClientSkillData() {
    }

    public static void update(ManaSyncPayload p) {
        mana = p.mana();
        maxMana = Math.max(1, p.max());
    }

    public static void update(SkillCooldownPayload p) {
        long now = System.currentTimeMillis();
        COOLDOWNS.put(p.skillId(), new long[]{now, p.ticks() * 50L});
    }

    /** Fraction of the cooldown still remaining, 0 when ready, 1 right after casting. */
    public static float cooldownRemaining(String skillId) {
        long[] c = COOLDOWNS.get(skillId);
        if (c == null) {
            return 0f;
        }
        long elapsed = System.currentTimeMillis() - c[0];
        return elapsed >= c[1] ? 0f : 1f - (float) elapsed / c[1];
    }
}
