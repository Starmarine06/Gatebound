package dev.gatebound.skill;

import dev.gatebound.data.PlayerData;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Skill proficiency: every successful cast of a skill counts as a use, and uses raise that skill's rank. */
public final class Proficiency {
    public static final int MAX_RANK = 10;
    /** Mana cost removed per rank, so a master pays 70% of the base cost. */
    public static final double DISCOUNT_PER_RANK = 0.03;

    /** Damage and duration gained per rank above 1, so a master deals and lasts 45% more. */
    public static final double POWER_PER_RANK = 0.05;
    /** Cast delay removed per rank above 1, so a master casts in 64% of the base time. */
    public static final double SPEED_PER_RANK = 0.04;
    /** Ticks after a cast during which its delayed hits still count as that skill's. */
    private static final int ACTIVE_TICKS = 600;

    /** The skill a player cast most recently: its rank and the tick it was cast. */
    private record Active(int rank, int tick) {
    }

    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    /** Static holder; not instantiable. */
    private Proficiency() {
    }

    /** Total uses needed to reach a rank (rank 1 is free; each rank costs more than the last). */
    public static int usesFor(int rank) {
        return rank <= 1 ? 0 : 5 * (rank - 1) * rank;
    }

    /** Rank (1 to MAX_RANK) for a use count. */
    public static int rank(int uses) {
        int rank = 1;
        while (rank < MAX_RANK && uses >= usesFor(rank + 1)) {
            rank++;
        }
        return rank;
    }

    /** Mana cost of a skill after the proficiency discount; never below 1. */
    public static int cost(int baseCost, int uses) {
        double factor = 1.0 - DISCOUNT_PER_RANK * (rank(uses) - 1);
        return Math.max(1, (int) Math.round(baseCost * factor));
    }

    /** Records one use. Returns the new rank if it just went up, otherwise 0. */
    public static int addUse(PlayerData data, String skillId) {
        int before = data.uses.getOrDefault(skillId, 0);
        data.uses.put(skillId, before + 1);
        int after = rank(before + 1);
        return after > rank(before) ? after : 0;
    }

    /** Marks the skill being cast, so damage and durations it produces are scaled by its rank. */
    public static void begin(ServerPlayer p, String skillId, PlayerData data) {
        ACTIVE.put(p.getUUID(), new Active(rank(data.uses.getOrDefault(skillId, 0)), p.level().getServer().getTickCount()));
    }

    /** Rank of the skill the player cast most recently, or 1 if none is still active. */
    private static int activeRank(ServerPlayer p) {
        Active a = ACTIVE.get(p.getUUID());
        return a != null && p.level().getServer().getTickCount() - a.tick() <= ACTIVE_TICKS ? a.rank() : 1;
    }

    /** Damage multiplier from the active skill's rank. */
    public static double damageFactor(ServerPlayer p) {
        return 1.0 + POWER_PER_RANK * (activeRank(p) - 1);
    }

    /** Scales skill damage by the active skill's rank. */
    public static float damage(ServerPlayer p, float amount) {
        return (float) (amount * damageFactor(p));
    }

    /** Scales a skill duration in ticks by the active skill's rank. */
    public static int duration(ServerPlayer p, int ticks) {
        return (int) Math.round(ticks * (1.0 + POWER_PER_RANK * (activeRank(p) - 1)));
    }

    /** Scales a cast delay in ticks by the rank of the skill about to be cast. */
    public static int castTime(int ticks, int uses) {
        return Math.max(1, (int) Math.round(ticks * (1.0 - SPEED_PER_RANK * (rank(uses) - 1))));
    }

    /** Forgets a player who left. */
    public static void forget(UUID id) {
        ACTIVE.remove(id);
    }
}
