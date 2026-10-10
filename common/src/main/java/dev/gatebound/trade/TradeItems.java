package dev.gatebound.trade;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.data.Stat;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.EnumMap;
import java.util.Map;

/** Items of the trading feature, in their own register. */
public final class TradeItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<Item> GATE_SHARD = ITEMS.register("gate_shard", () ->
            new Item(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).rarity(Rarity.UNCOMMON).setId(key("gate_shard"))));

    public static final Map<Stat, RegistrySupplier<Item>> TOMES = new EnumMap<>(Stat.class);

    static {
        for (Stat stat : Stat.values()) {
            String path = "tome_of_" + stat.id();
            TOMES.put(stat, ITEMS.register(path, () ->
                    new TomeItem(stat, new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16)
                            .rarity(Rarity.RARE).setId(key(path)))));
        }
    }

    public static final RegistrySupplier<Item> ECHO_SHARD = ITEMS.register("echo_shard", () ->
            new dev.gatebound.echo.EchoShardItem(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16)
                    .rarity(Rarity.RARE).setId(key("echo_shard"))));

    public static final RegistrySupplier<Item> MANA_POTION = ITEMS.register("mana_potion", () ->
            new dev.gatebound.skill.ManaPotionItem(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16)
                    .rarity(Rarity.UNCOMMON).setId(key("mana_potion"))));

    public static final RegistrySupplier<Item> INFO_SCROLL = ITEMS.register("info_scroll", () ->
            new InfoScrollItem(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16).setId(key("info_scroll"))));

    public static final RegistrySupplier<Item> RESPEC_TOKEN = ITEMS.register("respec_token", () ->
            new RespecTokenItem(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(16)
                    .rarity(Rarity.EPIC).setId(key("respec_token"))));

    /** Static holder; not instantiable. */
    private TradeItems() {
    }

    /** Registers the trade items. */
    public static void init() {
        ITEMS.register();
    }

    /** Item registry key for a path. */
    private static ResourceKey<Item> key(String path) {
        return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path));
    }
}
