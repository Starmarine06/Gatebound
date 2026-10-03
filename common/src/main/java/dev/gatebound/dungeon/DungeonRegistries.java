package dev.gatebound.dungeon;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/** Own registries for the dungeon feature: gate block + item, exit block, dungeon dimension key. */
public final class DungeonRegistries {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Gatebound.MOD_ID, Registries.BLOCK);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);

    public static final ResourceKey<Level> DUNGEON = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "dungeon"));

    public static final RegistrySupplier<Block> GATE = BLOCKS.register("gate", () ->
            new GateBlock(BlockBehaviour.Properties.of()
                    .setId(blockKey("gate"))
                    .noCollision()
                    .noOcclusion()
                    .strength(3.0f)
                    .lightLevel(state -> 11)
                    .pushReaction(PushReaction.IMMOVEABLE)));

    public static final RegistrySupplier<Block> GATE_EXIT = BLOCKS.register("gate_exit", () ->
            new GateExitBlock(BlockBehaviour.Properties.of()
                    .setId(blockKey("gate_exit"))
                    .noCollision()
                    .noOcclusion()
                    .strength(-1.0f, 3600000.0f)
                    .lightLevel(state -> 13)
                    .pushReaction(PushReaction.IMMOVEABLE)));

    public static final RegistrySupplier<Item> GATE_ITEM = ITEMS.register("gate", () ->
            new BlockItem(GATE.get(), new Item.Properties()
                    .arch$tab(GateboundRegistries.MAIN_TAB)
                    .useBlockDescriptionPrefix()
                    .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "gate")))));

    private DungeonRegistries() {
    }

    public static void init() {
        BLOCKS.register();
        ITEMS.register();
    }

    private static ResourceKey<Block> blockKey(String path) {
        return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path));
    }
}
