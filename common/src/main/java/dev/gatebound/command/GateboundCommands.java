package dev.gatebound.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.combat.CombatConfig;
import dev.gatebound.combat.CombatState;
import dev.gatebound.data.Element;
import dev.gatebound.skill.MagicConfig;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillHandler;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Progression;
import dev.gatebound.data.Stat;
import dev.gatebound.network.PingPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class GateboundCommands {
    /** Static holder; not instantiable. */
    private GateboundCommands() {
    }

    /** Registers the /gatebound commands. */
    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gatebound");

            root.then(Commands.literal("ping").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                NetworkManager.sendToPlayer(player, new PingPayload("pong from the server"));
                ctx.getSource().sendSuccess(() -> Component.literal("Ping sent"), false);
                return 1;
            }));

            root.then(LocateCommand.build());
            LocateCommand.attachToVanilla(dispatcher);

            root.then(Commands.literal("status").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                PlayerData d = PlayerDataStore.of(player);
                StringBuilder sb = new StringBuilder("Level " + d.level + " (" + d.xp + "/" + d.xpToNext() + " XP), "
                        + d.statPoints + " stat points\n");
                for (Stat s : Stat.values()) {
                    sb.append(s.id()).append(": ").append(d.get(s)).append("  ");
                }
                ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                return 1;
            }));

            // Spend stat points: /gatebound stat <stat> <points>
            LiteralArgumentBuilder<CommandSourceStack> stat = Commands.literal("stat").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
            for (Stat s : Stat.values()) {
                stat.then(Commands.literal(s.id()).then(Commands.argument("points", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            PlayerData d = PlayerDataStore.of(player);
                            int points = IntegerArgumentType.getInteger(ctx, "points");
                            if (!d.spend(s, points)) {
                                ctx.getSource().sendFailure(Component.literal("Not enough stat points (" + d.statPoints + " left)"));
                                return 0;
                            }
                            Progression.save(player, d);
                            ctx.getSource().sendSuccess(() -> Component.literal(s.id() + " is now " + d.get(s)), false);
                            return 1;
                        })));
            }
            root.then(stat);

            // Admin/testing: /gatebound level set <n>, /gatebound xp add <n>
            root.then(Commands.literal("level").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("set").then(Commands.argument("level", IntegerArgumentType.integer(1, PlayerData.MAX_LEVEL))
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                PlayerData d = PlayerDataStore.of(player);
                                d.setLevel(IntegerArgumentType.getInteger(ctx, "level"));
                                Progression.save(player, d);
                                Progression.openSpellPickIfNeeded(player);
                                ctx.getSource().sendSuccess(() -> Component.literal("Level set to " + d.level), false);
                                return 1;
                            }))));
            root.then(Commands.literal("xp").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("add").then(Commands.argument("amount", IntegerArgumentType.integer(1))
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                Progression.giveXp(player, IntegerArgumentType.getInteger(ctx, "amount"));
                                return 1;
                            }))));

            // /gatebound awaken reopens the class screen if no class is chosen; /gatebound class reset is admin-only.
            root.then(Commands.literal("awaken").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                Progression.openAwakeningIfNeeded(ctx.getSource().getPlayerOrException());
                return 1;
            }));
            // /gatebound spells reopens the spell choice screen when a multi-element Mage has a pick waiting.
            root.then(Commands.literal("spells").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                Progression.openSpellPickIfNeeded(ctx.getSource().getPlayerOrException());
                return 1;
            }));
            root.then(Commands.literal("class").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("reset").executes(ctx -> {
                        Progression.resetClass(ctx.getSource().getPlayerOrException());
                        ctx.getSource().sendSuccess(() -> Component.literal("Class reset"), false);
                        return 1;
                    })));

            // Combat testing: /gatebound combat debug, /gatebound combat window <ticks>, /gatebound combat posture <value>
            root.then(Commands.literal("combat").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("debug").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        boolean on = CombatState.toggleDebug(player.getUUID());
                        ctx.getSource().sendSuccess(() -> Component.literal("Combat debug " + (on ? "on" : "off")), false);
                        return 1;
                    }))
                    .then(Commands.literal("window").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 40)).executes(ctx -> {
                                CombatConfig.parryWindowTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                                ctx.getSource().sendSuccess(() -> Component.literal("Parry window is now "
                                        + CombatConfig.parryWindowTicks + " ticks"), true);
                                return 1;
                            })))
                    .then(Commands.literal("posture").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.argument("max", IntegerArgumentType.integer(10, 1000)).executes(ctx -> {
                                CombatConfig.postureMax = IntegerArgumentType.getInteger(ctx, "max");
                                ctx.getSource().sendSuccess(() -> Component.literal("Max posture is now "
                                        + (int) CombatConfig.postureMax), true);
                                return 1;
                            }))));

            root.then(Combat4bCommands.node()); // merges into the combat node above

            // Magic testing (op only): /gatebound element grant <element>, /gatebound mana set <n>, /gatebound magic odds <second> <third>
            LiteralArgumentBuilder<CommandSourceStack> grant = Commands.literal("grant");
            for (Element element : Element.values()) {
                grant.then(Commands.literal(element.id()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    PlayerData d = PlayerDataStore.of(player);
                    if (!d.hasElement(element)) {
                        d.elements.add(element.id());
                    }
                    Progression.save(player, d);
                    Progression.openSpellPickIfNeeded(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Elements: " + String.join(", ", d.elements)), false);
                    return 1;
                }));
            }
            root.then(Commands.literal("element").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(grant));
            root.then(Commands.literal("mana").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("set").then(Commands.argument("amount", IntegerArgumentType.integer(0, 10000))
                            .executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                ManaState.set(player, IntegerArgumentType.getInteger(ctx, "amount"));
                                SkillHandler.sendMana(player, true);
                                ctx.getSource().sendSuccess(() -> Component.literal("Mana set to " + (int) ManaState.get(player)), false);
                                return 1;
                            }))));
            root.then(Commands.literal("magic").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("odds")
                            .then(Commands.argument("second", IntegerArgumentType.integer(0, 100))
                                    .then(Commands.argument("third", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                                        MagicConfig.secondElementChance = IntegerArgumentType.getInteger(ctx, "second") / 100.0;
                                        MagicConfig.thirdElementChance = IntegerArgumentType.getInteger(ctx, "third") / 100.0;
                                        ctx.getSource().sendSuccess(() -> Component.literal("Extra element odds: second "
                                                + (int) (MagicConfig.secondElementChance * 100) + "%, third "
                                                + (int) (MagicConfig.thirdElementChance * 100) + "%"), true);
                                        return 1;
                                    })))));

            root.then(dev.gatebound.dungeon.DungeonCommands.build());

            dispatcher.register(root);
        });
    }
}
