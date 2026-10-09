package dev.gatebound.mob;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.List;

/**
 * Materials the Spire's monsters drop, one per rank (E to S) plus a rare one from guardians. Recipes turn them into the
 * next step of vanilla progression (iron, gold, diamond, blaze rods, netherite, enchanted apples).
 */
public final class MobMaterials {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    private static final List<RegistrySupplier<Item>> BY_BAND = new ArrayList<>();

    public static final RegistrySupplier<Item> CRYPT_DUST = band("crypt_dust", Rarity.COMMON);
    public static final RegistrySupplier<Item> MOSSY_FANG = band("mossy_fang", Rarity.COMMON);
    public static final RegistrySupplier<Item> TIDE_SCALE = band("tide_scale", Rarity.UNCOMMON);
    public static final RegistrySupplier<Item> MAGMA_CORE = band("magma_core", Rarity.UNCOMMON);
    public static final RegistrySupplier<Item> ASH_HEART = band("ash_heart", Rarity.RARE);
    public static final RegistrySupplier<Item> VOID_ESSENCE = band("void_essence", Rarity.RARE);
    public static final RegistrySupplier<Item> SOVEREIGN_SIGIL = ITEMS.register("sovereign_sigil", () ->
            new Item(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).rarity(Rarity.EPIC).setId(key("sovereign_sigil"))));

    private MobMaterials() {
    }

    private static RegistrySupplier<Item> band(String id, Rarity rarity) {
        RegistrySupplier<Item> s = ITEMS.register(id, () ->
                new Item(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB).rarity(rarity).setId(key(id))));
        BY_BAND.add(s);
        return s;
    }

    /** The material of a difficulty band 0 (E) to 5 (S). */
    public static Item ofBand(int band) {
        return BY_BAND.get(Math.max(0, Math.min(BY_BAND.size() - 1, band))).get();
    }

    public static void init() {
        ITEMS.register();
    }

    private static ResourceKey<Item> key(String path) {
        return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path));
    }
}
