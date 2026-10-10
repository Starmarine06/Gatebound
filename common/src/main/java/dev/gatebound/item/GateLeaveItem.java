package dev.gatebound.item;

import dev.gatebound.compat.Compat;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.dungeon.DungeonManager;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A one-use scroll that does what /gatebound gate leave does: sends you home from the dungeon. Does nothing outside it. */
public final class GateLeaveItem extends Item {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    public static final RegistrySupplier<Item> GATE_LEAVE = ITEMS.register("gate_leave", () -> new GateLeaveItem(new Item.Properties()
            .arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16)
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "gate_leave")))));

    /** Creates the gate-leave item. */
    private GateLeaveItem(Properties properties) {
        super(properties);
    }

    /** Registers the gate-leave item. */
    public static void init() {
        ITEMS.register();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp && DungeonManager.leave(sp)) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            return InteractionResult.SUCCESS;
        }
        Compat.overlay(player, Component.literal("The scroll only works inside a dungeon.").withStyle(ChatFormatting.GRAY));
        return InteractionResult.FAIL;
    }
}
