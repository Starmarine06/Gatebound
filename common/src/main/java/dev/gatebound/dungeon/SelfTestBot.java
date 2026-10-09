package dev.gatebound.dungeon;

import com.mojang.authlib.GameProfile;
import dev.gatebound.Gatebound;
import dev.gatebound.trade.TradeItems;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.UUID;

/**
 * Self-test only (DungeonConfig.selfTest): a fake player on an embedded netty channel that walks through the
 * real player-facing code paths (gate pull and entry, stairs, hold while a floor builds, boss reward, death and
 * respawn, tower exit) and logs what happened. Never loaded in normal play.
 */
final class SelfTestBot {
    private static int step;
    private static int wait;
    private static int timeout;
    private static ServerPlayer bot;
    private static BlockPos gatePos;
    private static boolean finished;

    private SelfTestBot() {
    }

    static boolean finished() {
        return finished;
    }

    private static void log(String msg, Object... args) {
        Gatebound.LOGGER.info("SELFTEST BOT " + msg, args);
    }

    private static String where(ServerPlayer p) {
        int total = DungeonStore.get(p.level().getServer()).totalFloors;
        return p.level().dimension().identifier() + " floor " + Spire.floorAt(p.getX(), p.getZ(), total) + " at "
                + (int) p.getX() + "," + (int) p.getY() + "," + (int) p.getZ();
    }

    static void tick(MinecraftServer server, DungeonStore store) {
        if (finished) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        ServerLevel ow = server.overworld();
        try {
            run(server, store, ow);
        } catch (RuntimeException e) {
            Gatebound.LOGGER.error("SELFTEST BOT crashed in step {}", step, e);
            finished = true;
        }
    }

    private static void run(MinecraftServer server, DungeonStore store, ServerLevel ow) {
        int total = store.totalFloors;
        switch (step) {
            case 0 -> {
                GameProfile gp = new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000b07b"), "SpireBot");
                bot = new ServerPlayer(server, ow, gp, ClientInformation.createDefault());
                Connection conn = new Connection(PacketFlow.SERVERBOUND);
                new EmbeddedChannel(conn);
                server.getPlayerList().placeNewPlayer(conn, bot, CommonListenerCookie.createInitial(gp, false));
                log("joined as {} ({})", bot.getName().getString(), where(bot));
                step = 1;
                wait = 20;
            }
            case 1 -> {
                BlockPos c = store.towerExit;
                bot.teleportTo(ow, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, Set.of(), 0f, 0f, true);
                gatePos = c.offset(10, 0, 0);
                ow.setBlock(gatePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                ow.setBlock(gatePos.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                ow.setBlock(gatePos.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                DungeonManager.placeGate(ow, gatePos, GateRank.E, bot.getX(), bot.getZ());
                store.gates.add(new DungeonStore.GateRef(ow.dimension(), gatePos, 2, false, ow.getGameTime(), 0));
                DungeonManager.testClearCooldowns();
                log("teleported to the tower exit ({}), gate placed at {} leading to floor 2", where(bot), gatePos.toShortString());
                step = 2;
                wait = 3;
            }
            case 2 -> {
                var v = bot.getDeltaMovement();
                log("pull active: bot deltaMovement = {}, {}, {} (gate lies to +x)", round(v.x), round(v.y), round(v.z));
                // Sneaking halves the pull.
                bot.setShiftKeyDown(true);
                step = 3;
                wait = 3;
            }
            case 3 -> {
                var v = bot.getDeltaMovement();
                log("sneaking pull: deltaMovement = {}, {}, {}", round(v.x), round(v.y), round(v.z));
                bot.setShiftKeyDown(false);
                // Creative flight is not pulled.
                bot.getAbilities().flying = true;
                bot.setDeltaMovement(0, 0, 0);
                step = 4;
                wait = 3;
            }
            case 4 -> {
                var v = bot.getDeltaMovement();
                log("flying player pulled? deltaMovement x = {} (expected 0)", round(v.x));
                bot.getAbilities().flying = false;
                // Put the bot right on the gate: pulling it fully in must trigger the teleport.
                bot.teleportTo(ow, gatePos.getX() + 0.5, gatePos.getY() + 0.1, gatePos.getZ() + 0.5, Set.of(), 0f, 0f, true);
                step = 5;
                wait = 10;
            }
            case 5 -> {
                log("after being pulled into the gate: {} (returns stored: {})", where(bot), store.returns.containsKey(bot.getUUID()));
                DungeonStore.GateRef g = store.gateAt(ow.dimension(), gatePos);
                log("gate uses = {}", g == null ? -1 : g.uses);
                step = 6;
            }
            case 6 -> {
                // Climb: floor 3 is usually not built yet, which exercises the hold and the build queue.
                DungeonManager.testClearCooldowns();
                int f = Spire.floorAt(bot.getX(), bot.getZ(), total);
                DungeonStore.FloorState fs = store.floor(f);
                log("using the UP portal on floor {}", f);
                DungeonManager.usePortal(bot, fs.upArrive.east(2), GateExitBlock.Kind.UP);
                step = 7;
                timeout = 0;
                wait = 2;
            }
            case 7 -> {
                timeout++;
                int f = Spire.floorAt(bot.getX(), bot.getZ(), total);
                if (f == 3 || timeout > 600) {
                    log("arrived: {} (hold cleared: {}, waited {} ticks)", where(bot), !WorkQueue.isHolding(bot.getUUID()), timeout);
                    step = 8;
                } else if (timeout == 5) {
                    log("holding while the floor builds: holding={}, queue size={}", WorkQueue.isHolding(bot.getUUID()), WorkQueue.size());
                }
            }
            case 8 -> {
                DungeonManager.testClearCooldowns();
                DungeonStore.FloorState fs = store.floor(Spire.floorAt(bot.getX(), bot.getZ(), total));
                DungeonManager.usePortal(bot, fs.downArrive.west(2), GateExitBlock.Kind.DOWN);
                step = 9;
                wait = 5;
            }
            case 9 -> {
                log("after DOWN portal: {}", where(bot));
                DungeonManager.testClearCooldowns();
                DungeonStore.FloorState fs = store.floor(Spire.floorAt(bot.getX(), bot.getZ(), total));
                DungeonManager.usePortal(bot, fs.downArrive.west(2), GateExitBlock.Kind.HOME);
                step = 10;
                wait = 5;
            }
            case 10 -> {
                log("after the escape rift: {} (expected the overworld in front of the gate)", where(bot));
                // Jump straight to floor 10 (a boss floor) and fake the guardian's death.
                DungeonManager.testClearCooldowns();
                int boss = Math.min(10, total - 1);
                boss = boss - boss % 10;
                if (boss < 10) {
                    boss = total;
                }
                DungeonManager.travelFloor(bot, boss, WorkQueue.Dest.BOTTOM);
                step = 11;
                timeout = 0;
                wait = 5;
            }
            case 11 -> {
                timeout++;
                int f = Spire.floorAt(bot.getX(), bot.getZ(), total);
                if (f > 0 && Spire.kind(f, total) != Spire.Kind.NORMAL || timeout > 600) {
                    log("on boss floor: {}", where(bot));
                    ServerLevel dun = server.getLevel(DungeonRegistries.DUNGEON);
                    int shardsBefore = countShards();
                    DungeonManager.testBossDeath(dun, store, f);
                    int shardsAfter = countShards();
                    DungeonStore.FloorState fs = store.floor(f);
                    boolean portal = PortalPlacer.exists(dun, fs.upPortal, net.minecraft.core.Direction.EAST);
                    log("boss reward: shards {} -> {}, exit portal present={}, bossDead={}, claimed={}", shardsBefore, shardsAfter,
                            portal, fs.bossDead, store.progressOf(bot.getUUID()).bosses);
                    DungeonManager.testBossDeath(dun, store, f);
                    log("second kill gives shards {} -> {} (no repeat reward expected)", shardsAfter, countShards());
                    step = 12;
                    wait = 5;
                }
            }
            case 12 -> {
                // Death on a floor: respawn at the last rest floor (floor 5 if the tower has one, else the entrance).
                DungeonStore.Progress prog = store.progressOf(bot.getUUID());
                prog.lastRest = total >= 5 && store.floor(5).built ? 5 : 0;
                log("killing the bot on {} with lastRest={}", where(bot), prog.lastRest);
                ServerLevel dl = server.getLevel(DungeonRegistries.DUNGEON);
                // Players are invulnerable until their client reports the level as loaded; the bot has no client.
                //? if >=26.1 {
                bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                //?}
                boolean hurt = bot.hurtServer(dl, bot.damageSources().genericKill(), 1000f);
                if (!hurt) {
                    bot.setHealth(0f);
                    bot.die(bot.damageSources().genericKill());
                }
                log("hurtServer returned {}, health {}, dead {}, invulnerable {}", hurt, bot.getHealth(), bot.isDeadOrDying(),
                        bot.isInvulnerable());
                step = 13;
                wait = 5;
            }
            case 13 -> {
                // Same path a real client takes when it presses Respawn (the listener swaps in the new player object).
                bot.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                        net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                bot = server.getPlayerList().getPlayer(bot.getUUID());
                log("respawned: {}", where(bot));
                step = 14;
                timeout = 0;
                wait = 5;
            }
            case 14 -> {
                timeout++;
                if (timeout < 40 && Spire.floorAt(bot.getX(), bot.getZ(), total) == 0
                        && bot.level().dimension().equals(DungeonRegistries.DUNGEON)) {
                    return;
                }
                log("after the respawn handling: {}", where(bot));
                // Leaving the tower from floor 1.
                DungeonManager.testClearCooldowns();
                DungeonManager.travelFloor(bot, 1, WorkQueue.Dest.BOTTOM);
                step = 15;
                wait = 10;
            }
            case 15 -> {
                DungeonManager.testClearCooldowns();
                DungeonStore.FloorState fs = store.floor(1);
                log("on floor 1: {} (removed={}, alive={}, inLevelPlayers={}, hasConnection={}, holding={}, ready={})", where(bot),
                        bot.isRemoved(), bot.isAlive(), bot.level().players().contains(bot), bot.connection != null,
                        WorkQueue.isHolding(bot.getUUID()), DungeonManager.portalReady(bot));
                DungeonManager.usePortal(bot, fs.downArrive.west(2), GateExitBlock.Kind.DOWN);
                step = 16;
                wait = 5;
            }
            case 16 -> {
                log("after DOWN on floor 1: {} (expected the overworld at the tower entrance {})", where(bot), store.towerExit.toShortString());
                server.getPlayerList().remove(bot);
                log("DONE");
                finished = true;
            }
            default -> finished = true;
        }
    }

    private static int countShards() {
        int n = 0;
        for (ItemStack s : bot.getInventory().getNonEquipmentItems()) {
            if (s.is(TradeItems.GATE_SHARD.get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }
}
