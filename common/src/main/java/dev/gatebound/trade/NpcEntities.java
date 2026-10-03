package dev.gatebound.trade;

import dev.architectury.registry.level.entity.EntityAttributeRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.villager.Villager;

/** Entity type of the Gatebound NPCs (Skill Broker and Scholar share one type; the role is an entity tag). */
public final class NpcEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Gatebound.MOD_ID, Registries.ENTITY_TYPE);

    public static final RegistrySupplier<EntityType<GateboundNpc>> NPC = ENTITY_TYPES.register("npc", () ->
            EntityType.Builder.of(GateboundNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f)
                    .eyeHeight(1.62f)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "npc"))));

    private NpcEntities() {
    }

    public static void init() {
        ENTITY_TYPES.register();
        EntityAttributeRegistry.register(NPC, Villager::createAttributes);
    }
}
