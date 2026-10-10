package dev.gatebound.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.gatebound.combat.CombatConfig;
import dev.gatebound.combat.PlayerCombat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleConsumer;

/** Op-only /gatebound combat tuning for dodge, stamina, telegraphs and player posture. Merged into the existing combat node. */
public final class Combat4bCommands {
    /** Static holder; not instantiable. */
    private Combat4bCommands() {
    }

    /** Builds the combat tuning command (changes CombatConfig values live). */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        Map<String, DoubleConsumer> tunables = new LinkedHashMap<>();
        tunables.put("dodgecost", v -> CombatConfig.dodgeCost = v);
        tunables.put("dodgecooldown", v -> CombatConfig.dodgeCooldownTicks = (int) v);
        tunables.put("iframes", v -> CombatConfig.iframeTicks = (int) v);
        tunables.put("dodgespeed", v -> CombatConfig.dodgeSpeed = v);
        tunables.put("perfectrefund", v -> CombatConfig.perfectDodgeRefund = v);
        tunables.put("perfectcooldowncut", v -> CombatConfig.perfectDodgeCooldownCut = (int) v);
        tunables.put("staminaregen", v -> CombatConfig.staminaRegenPerTick = v);
        tunables.put("windup", v -> CombatConfig.windupTicks = (int) v);
        tunables.put("heavywindup", v -> CombatConfig.heavyWindupTicks = (int) v);
        tunables.put("heavychance", v -> CombatConfig.heavyChance = v);
        tunables.put("recover", v -> CombatConfig.attackRecoverTicks = (int) v);
        tunables.put("playerposture", v -> CombatConfig.playerPostureMax = v);
        tunables.put("lateparryposture", v -> CombatConfig.lateParryPosture = v);
        tunables.put("heavyparryposture", v -> CombatConfig.heavyParryPosture = v);
        tunables.put("staggerticks", v -> CombatConfig.playerStaggerTicks = (int) v);

        LiteralArgumentBuilder<CommandSourceStack> tune = Commands.literal("tune").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (Map.Entry<String, DoubleConsumer> e : tunables.entrySet()) {
            tune.then(Commands.literal(e.getKey()).then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 10000)).executes(ctx -> {
                double v = DoubleArgumentType.getDouble(ctx, "value");
                e.getValue().accept(v);
                ctx.getSource().sendSuccess(() -> Component.literal(e.getKey() + " = " + v), true);
                return 1;
            })));
        }

        return Commands.literal("combat")
                .then(tune)
                .then(Commands.literal("telegraph").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                    CombatConfig.telegraphEnabled = BoolArgumentType.getBool(ctx, "on");
                    ctx.getSource().sendSuccess(() -> Component.literal("Mob wind-ups " + (CombatConfig.telegraphEnabled ? "on" : "off")), true);
                    return 1;
                })))
                .then(Commands.literal("stamina").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 1000)).executes(ctx -> {
                    PlayerCombat.setStamina(ctx.getSource().getPlayerOrException(), DoubleArgumentType.getDouble(ctx, "value"));
                    return 1;
                })));
    }
}
