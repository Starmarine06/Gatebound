package dev.gatebound.grave;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;

/** Registries of the grave a dead player leaves behind: the block, its item and its block entity. */
public final class GraveRegistries {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Gatebound.MOD_ID, Registries.BLOCK);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Gatebound.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<Block> GRAVE = BLOCKS.register("grave", () ->
            new GraveBlock(BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "grave")))
                    .noOcclusion()
                    .strength(-1.0f, 3600000.0f)
                    .lightLevel(state -> 4)));

    public static final RegistrySupplier<Item> GRAVE_ITEM = ITEMS.register("grave", () ->
            new BlockItem(GRAVE.get(), new Item.Properties()
                    .arch$tab(GateboundRegistries.MAIN_TAB)
                    .useBlockDescriptionPrefix()
                    .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "grave")))));

    public static final RegistrySupplier<BlockEntityType<GraveBlockEntity>> GRAVE_ENTITY = ENTITIES.register("grave", () ->
            dev.gatebound.compat.Compat.blockEntityType(GraveBlockEntity::new, GRAVE.get()));

    private GraveRegistries() {
    }

    public static void init() {
        BLOCKS.register();
        ITEMS.register();
        ENTITIES.register();
        GraveHandler.init();
    }
}
