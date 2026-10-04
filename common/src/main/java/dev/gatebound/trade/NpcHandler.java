package dev.gatebound.trade;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.gatebound.Gatebound;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;

/**
 * Skill Broker and Scholar: custom {@link GateboundNpc} entities (invulnerable, stationary) with their own trade screen,
 * plus Gate Shard drops. The old vanilla-Villager NPCs (tagged villagers from earlier builds) are removed when they load.
 */
public final class NpcHandler {
    public static final String BROKER_TAG = "gatebound_broker";
    public static final String SCHOLAR_TAG = "gatebound_scholar";

    private NpcHandler() {
    }

    public static void init() {
        TradeItems.init();
        NpcTrades.init();

        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer
                    && entity.level() instanceof ServerLevel level
                    && entity.getRandom().nextDouble() < TradeConfig.shardDropChance) {
                int count = 1 + (entity.getMaxHealth() >= 50 ? TradeConfig.bossShardBonus : 0);
                entity.spawnAtLocation(level, new ItemStack(TradeItems.GATE_SHARD.get(), count));
            }
            return EventResult.pass();
        });

        // Orphans from the old villager-based NPCs: refuse to load them.
        EntityEvent.ADD.register((entity, level) -> {
            if (!level.isClientSide() && entity instanceof Villager && !(entity instanceof GateboundNpc)
                    && (entity.entityTags().contains(BROKER_TAG) || entity.entityTags().contains(SCHOLAR_TAG))) {
                Gatebound.LOGGER.info("[npc] removed an old villager-based NPC");
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
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
        return entity instanceof GateboundNpc;
    }

    /** Spawns a Skill Broker (true) or Scholar (false) at the position, or returns null if it could not be placed. */
    public static GateboundNpc spawnNpc(ServerLevel level, BlockPos pos, boolean broker) {
        GateboundNpc npc = NpcEntities.NPC.get().spawn(level, pos, EntitySpawnReason.COMMAND);
        if (npc == null) {
            return null;
        }
        ResourceKey<VillagerProfession> prof = broker ? VillagerProfession.ARMORER : VillagerProfession.LIBRARIAN;
        npc.setVillagerData(npc.getVillagerData().withProfession(level.registryAccess(), prof).withLevel(2));
        npc.addTag(broker ? BROKER_TAG : SCHOLAR_TAG);
        npc.setCustomName(Component.translatable(broker ? "npc.gatebound.broker" : "npc.gatebound.scholar"));
        npc.setCustomNameVisible(true);
        return npc;
    }

    private static int spawn(CommandSourceStack source, boolean broker) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        GateboundNpc npc = spawnNpc(level, pos, broker);
        if (npc == null) {
            source.sendFailure(Component.literal("Could not spawn the NPC here."));
            return 0;
        }
        if (source.getEntity() != null) {
            npc.lookAt(source.getEntity(), 360f, 360f);
            npc.setYHeadRot(npc.getYRot());
        }
        source.sendSuccess(() -> Component.literal("Spawned a " + (broker ? "Skill Broker" : "Scholar")), true);
        return 1;
    }
}
