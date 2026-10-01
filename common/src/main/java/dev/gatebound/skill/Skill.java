package dev.gatebound.skill;

import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** An active skill. Class-locked and level-gated; costs mana and has a cooldown. */
public record Skill(String id, GateboundClass owner, int minLevel, int manaCost, int cooldownTicks, Effect effect) {

    /** Runs the skill. Return false if it had no valid target, so no mana or cooldown is spent. */
    @FunctionalInterface
    public interface Effect {
        boolean cast(ServerPlayer player, ServerLevel level, PlayerData data);
    }

    public String nameKey() {
        return "skill.gatebound." + id;
    }

    public String descriptionKey() {
        return "skill.gatebound." + id + ".desc";
    }
}
