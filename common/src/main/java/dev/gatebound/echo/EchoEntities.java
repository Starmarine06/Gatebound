package dev.gatebound.echo;

import dev.architectury.registry.level.entity.EntityAttributeRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** The single entity type behind every Echo. Which mob it looks like is synced data; its AI is entirely ours. */
public final class EchoEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Gatebound.MOD_ID, Registries.ENTITY_TYPE);

    public static final RegistrySupplier<EntityType<EchoEntity>> ECHO = ENTITY_TYPES.register("echo", () ->
            EntityType.Builder.of(EchoEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f)
                    .eyeHeight(1.6f)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo"))));

    private EchoEntities() {
    }

    /** Must run before any client renderer registration. */
    public static void init() {
        ENTITY_TYPES.register();
        EntityAttributeRegistry.register(ECHO, EchoEntity::createAttributes);
    }
}
