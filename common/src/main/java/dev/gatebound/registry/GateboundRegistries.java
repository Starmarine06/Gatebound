package dev.gatebound.registry;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class GateboundRegistries {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Gatebound.MOD_ID, Registries.CREATIVE_MODE_TAB);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<CreativeModeTab> MAIN_TAB = TABS.register("main", () ->
            CreativeTabRegistry.create(Component.translatable("itemGroup.gatebound.main"),
                    () -> new ItemStack(GateboundRegistries.AWAKENING_STONE.get())));

    public static final RegistrySupplier<Item> AWAKENING_STONE = ITEMS.register("awakening_stone", () ->
            new Item(new Item.Properties().arch$tab(MAIN_TAB).stacksTo(1).setId(itemKey("awakening_stone"))));

    /** Static holder; not instantiable. */
    private GateboundRegistries() {
    }

    /** Registers the creative tab and items. */
    public static void init() {
        TABS.register();
        ITEMS.register();
    }

    /** Item registry key for a path. */
    private static ResourceKey<Item> itemKey(String path) {
        return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path));
    }
}
