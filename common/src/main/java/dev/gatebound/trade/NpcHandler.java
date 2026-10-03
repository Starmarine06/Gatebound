package dev.gatebound.trade;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.gatebound.Gatebound;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skill Broker and Scholar: vanilla Villagers (frozen in place, invulnerable) marked with an entity tag.
 * Right-clicking one opens the vanilla merchant screen with a fixed offer list, priced by player level.
 */
public final class NpcHandler {
    public static final String BROKER_TAG = "gatebound_broker";
    public static final String SCHOLAR_TAG = "gatebound_scholar";

    /** Game day of the last restock per NPC; offers restock once per in-game day (resets on restart). */
    private static final Map<UUID, Long> LAST_RESTOCK_DAY = new HashMap<>();

    private NpcHandler() {
    }

    public static void init() {
        TradeItems.init();

        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer
                    && entity.level() instanceof ServerLevel level
                    && entity.getRandom().nextDouble() < TradeConfig.shardDropChance) {
                int count = 1 + (entity.getMaxHealth() >= 50 ? TradeConfig.bossShardBonus : 0);
                entity.spawnAtLocation(level, new ItemStack(TradeItems.GATE_SHARD.get(), count));
            }
            return EventResult.pass();
        });

        EntityEvent.LIVING_HURT.register((entity, source, amount) ->
                isNpc(entity) ? EventResult.interruptFalse() : EventResult.pass());

        InteractionEvent.INTERACT_ENTITY.register((player, entity, hand) -> {
            if (hand != InteractionHand.MAIN_HAND || !(entity instanceof Villager villager) || !isNpc(entity)
                    || !(player instanceof ServerPlayer sp) || !(entity.level() instanceof ServerLevel level)) {
                return EventResult.pass();
            }
            if (villager.isTrading()) {
                sp.sendOverlayMessage(Component.literal("Someone else is trading.").withStyle(ChatFormatting.RED));
                return EventResult.interruptTrue();
            }
            boolean broker = entity.entityTags().contains(BROKER_TAG);
            openTrade(sp, villager, level, broker);
            return EventResult.interruptTrue();
        });

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gatebound");
            root.then(Commands.literal("npc").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("spawn")
                            .then(Commands.literal("broker").executes(ctx -> spawn(ctx.getSource(), true)))
                            .then(Commands.literal("scholar").executes(ctx -> spawn(ctx.getSource(), false)))));
            dispatcher.register(root);
        });
    }

    public static boolean isNpc(Entity entity) {
        return entity instanceof Villager
                && (entity.entityTags().contains(BROKER_TAG) || entity.entityTags().contains(SCHOLAR_TAG));
    }

    private static int spawn(CommandSourceStack source, boolean broker) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        Villager villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
        if (villager == null) {
            source.sendFailure(Component.literal("Could not spawn the NPC here."));
            return 0;
        }
        ResourceKey<VillagerProfession> prof = broker ? VillagerProfession.ARMORER : VillagerProfession.LIBRARIAN;
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), prof).withLevel(2));
        villager.addTag(broker ? BROKER_TAG : SCHOLAR_TAG);
        villager.setCustomName(Component.translatable(broker ? "npc.gatebound.broker" : "npc.gatebound.scholar"));
        villager.setCustomNameVisible(true);
        villager.setNoAi(true);
        villager.setPersistenceRequired();
        source.sendSuccess(() -> Component.literal("Spawned a " + (broker ? "Skill Broker" : "Scholar")), true);
        return 1;
    }

    private static void openTrade(ServerPlayer player, Villager villager, ServerLevel level, boolean broker) {
        int lvl = PlayerDataStore.of(player).level;
        MerchantOffers old = villager.getOffers();
        long day = level.getGameTime() / 24000L;
        Long last = LAST_RESTOCK_DAY.put(villager.getUUID(), day);
        boolean restock = last == null || last != day;

        MerchantOffers offers = broker ? brokerOffers(lvl) : scholarOffers(lvl);
        if (!restock) {
            for (int n = 0; n < offers.size() && n < old.size(); n++) {
                for (int u = 0; u < old.get(n).getUses(); u++) {
                    offers.get(n).increaseUses();
                }
            }
        }
        villager.overrideOffers(offers);
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), 2);
    }

    private static MerchantOffers brokerOffers(int level) {
        MerchantOffers o = new MerchantOffers();
        o.add(sell(shards(10 + level / 3), TradeItems.RESPEC_TOKEN.get(), 2));
        o.add(sell(shards(tomePrice(level)), TradeItems.TOMES.get(Stat.STRENGTH).get(), TradeConfig.offerStock));
        o.add(sell(shards(tomePrice(level)), TradeItems.TOMES.get(Stat.AGILITY).get(), TradeConfig.offerStock));
        o.add(sell(shards(tomePrice(level)), TradeItems.TOMES.get(Stat.VITALITY).get(), TradeConfig.offerStock));
        o.add(new MerchantOffer(new ItemCost(Items.DIAMOND, 1), new ItemStack(TradeItems.GATE_SHARD.get(), 5), 8, 0, 0f));
        return o;
    }

    private static MerchantOffers scholarOffers(int level) {
        MerchantOffers o = new MerchantOffers();
        o.add(sell(shards(2 + level / 20), TradeItems.INFO_SCROLL.get(), 10));
        o.add(sell(shards(tomePrice(level)), TradeItems.TOMES.get(Stat.INTELLECT).get(), TradeConfig.offerStock));
        o.add(sell(shards(tomePrice(level)), TradeItems.TOMES.get(Stat.LUCK).get(), TradeConfig.offerStock));
        o.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(TradeItems.GATE_SHARD.get(), 2), 16, 0, 0f));
        return o;
    }

    private static int tomePrice(int level) {
        return 6 + level / 4;
    }

    private static ItemCost shards(int count) {
        return new ItemCost(TradeItems.GATE_SHARD.get(), Math.max(1, Math.min(64, count)));
    }

    private static MerchantOffer sell(ItemCost cost, Item result, int maxUses) {
        return new MerchantOffer(cost, new ItemStack(result), maxUses, 0, 0f);
    }
}
