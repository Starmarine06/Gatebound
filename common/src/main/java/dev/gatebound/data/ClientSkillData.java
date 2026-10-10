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

    /** Static holder; not instantiable. */
    private ClientSkillData() {
    }

    /** Stores the latest mana sync. */
    public static void update(ManaSyncPayload p) {
        mana = p.mana();
        maxMana = Math.max(1, p.max());
    }

    /** Stores the latest skill cooldowns. */
    public static void update(SkillCooldownPayload p) {
        long now = System.currentTimeMillis();
        COOLDOWNS.put(p.skillId(), new long[]{now, p.ticks() * 50L});
    }

}
