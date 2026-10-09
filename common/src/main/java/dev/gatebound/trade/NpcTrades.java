package dev.gatebound.trade;

import dev.gatebound.compat.Compat;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server side of the custom NPC shops: offer definitions (priced by player level), stock (restocks once per in-game day,
 * kept in memory) and the purchase handler. The client only ever sends "buy offer N of NPC X"; everything is validated here.
 */
public final class NpcTrades {
    /** Max distance (blocks) between buyer and NPC. */
    private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

    /** One offer: pay {@code price} of {@code pay}, receive {@code count} of {@code result}, up to {@code max} per day. */
    private record Def(Supplier<Item> result, int count, Supplier<Item> pay, int price, int max) {
    }

    private static final class Stock {
        long day = Long.MIN_VALUE;
        int[] uses = new int[0];
    }

    /** NPC uuid -> uses today per offer index. */
    private static final Map<UUID, Stock> STOCK = new HashMap<>();

    private NpcTrades() {
    }

    public static void init() {
        Compat.registerS2C(NpcOffersPayload.TYPE, NpcOffersPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> dev.gatebound.client.NpcClient.handle(payload))));

        Compat.registerC2S(NpcBuyPayload.TYPE, NpcBuyPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        buy(player, payload.entityId(), payload.offerIndex());
                    }
                }));
    }

    // ---------------------------------------------------------------- offers

    private static List<Def> defs(boolean broker, int level) {
        List<Def> o = new ArrayList<>();
        if (broker) {
            o.add(new Def(TradeItems.RESPEC_TOKEN::get, 1, TradeItems.GATE_SHARD::get, 10 + level / 3, 2));
            for (Stat s : new Stat[]{Stat.STRENGTH, Stat.AGILITY, Stat.VITALITY}) {
                o.add(new Def(TradeItems.TOMES.get(s)::get, 1, TradeItems.GATE_SHARD::get, tomePrice(level), TradeConfig.offerStock));
            }
            o.add(new Def(TradeItems.ECHO_SHARD::get, 1, TradeItems.GATE_SHARD::get, 30 + level / 2, 2));
            o.add(new Def(TradeItems.MANA_POTION::get, 1, TradeItems.GATE_SHARD::get, 25 + level / 5, 8));
            o.add(new Def(TradeItems.GATE_SHARD::get, 5, () -> Items.DIAMOND, 1, 8));
        } else {
            o.add(new Def(TradeItems.INFO_SCROLL::get, 1, TradeItems.GATE_SHARD::get, 2 + level / 20, 10));
            o.add(new Def(TradeItems.MANA_POTION::get, 1, TradeItems.GATE_SHARD::get, 25 + level / 5, 8));
            for (Stat s : new Stat[]{Stat.INTELLECT, Stat.LUCK}) {
                o.add(new Def(TradeItems.TOMES.get(s)::get, 1, TradeItems.GATE_SHARD::get, tomePrice(level), TradeConfig.offerStock));
            }
            o.add(new Def(TradeItems.GATE_SHARD::get, 2, () -> Items.EMERALD, 1, 16));
        }
        return o;
    }

    private static int tomePrice(int level) {
        return 6 + level / 4;
    }

    private static int[] usesToday(GateboundNpc npc, int size) {
        long day = npc.level().getGameTime() / 24000L;
        Stock s = STOCK.computeIfAbsent(npc.getUUID(), id -> new Stock());
        if (s.day != day || s.uses.length != size) {
            s.day = day;
            s.uses = new int[size];
        }
        return s.uses;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static NpcOffersPayload view(ServerPlayer player, GateboundNpc npc) {
        boolean broker = npc.isBroker();
        List<Def> defs = defs(broker, PlayerDataStore.of(player).level);
        int[] uses = usesToday(npc, defs.size());
        List<NpcOffersPayload.OfferView> views = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            Def d = defs.get(i);
            views.add(new NpcOffersPayload.OfferView(id(d.result().get()), d.count(), id(d.pay().get()), d.price(),
                    Math.max(0, d.max() - uses[i]), d.max()));
        }
        return new NpcOffersPayload(npc.getId(), broker, views);
    }

    // ---------------------------------------------------------------- open / buy

    public static void open(ServerPlayer player, GateboundNpc npc) {
        Gatebound.LOGGER.info("[npc] {} opened the {} shop", player.getName().getString(), npc.isBroker() ? "broker" : "scholar");
        NetworkManager.sendToPlayer(player, view(player, npc));
    }

    private static void buy(ServerPlayer player, int entityId, int index) {
        Entity e = player.level().getEntity(entityId);
        if (!(e instanceof GateboundNpc npc) || !npc.isAlive()) {
            return;
        }
        if (player.distanceToSqr(npc) > MAX_DISTANCE_SQR) {
            Compat.overlay(player, Component.translatableWithFallback("gatebound.npc.too_far", "You are too far away.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        List<Def> defs = defs(npc.isBroker(), PlayerDataStore.of(player).level);
        if (index < 0 || index >= defs.size()) {
            return;
        }
        Def d = defs.get(index);
        int[] uses = usesToday(npc, defs.size());
        Item pay = d.pay().get();
        if (uses[index] >= d.max()) {
            Compat.overlay(player, Component.translatableWithFallback("gatebound.npc.sold_out", "Sold out for today.")
                    .withStyle(ChatFormatting.RED));
        } else if (countItems(player, pay) < d.price()) {
            Compat.overlay(player, Component.translatableWithFallback("gatebound.npc.cannot_afford", "You cannot afford that.")
                    .withStyle(ChatFormatting.RED));
        } else {
            removeItems(player, pay, d.price());
            ItemStack stack = new ItemStack(d.result().get(), d.count());
            Component name = stack.getHoverName();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.spawnAtLocation(player.level(), stack);
            }
            uses[index]++;
            Compat.overlay(player, Component.translatableWithFallback("gatebound.npc.bought", "Bought %s", name)
                    .withStyle(ChatFormatting.GREEN));
            Gatebound.LOGGER.info("[npc] {} bought {}", player.getName().getString(), id(d.result().get()));
        }
        // Always refresh so the screen shows the true stock and prices.
        NetworkManager.sendToPlayer(player, view(player, npc));
    }

    private static int countItems(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void removeItems(ServerPlayer player, Item item, int amount) {
        Inventory inv = player.getInventory();
        int left = amount;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()) && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        player.containerMenu.broadcastChanges();
    }
}
