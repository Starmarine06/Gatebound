package dev.gatebound.client;

//? if >=26.1 {
import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import dev.gatebound.Gatebound;
import dev.gatebound.mob.SpireMobs;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EvokerRenderer;
import net.minecraft.client.renderer.entity.RavagerRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.client.renderer.entity.VindicatorRenderer;
import net.minecraft.client.renderer.entity.WitchRenderer;
import net.minecraft.client.renderer.entity.WitherSkeletonRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.EvokerRenderState;
import net.minecraft.client.renderer.entity.state.IllagerRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.RavagerRenderState;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import net.minecraft.client.renderer.entity.state.WitchRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.monster.spider.Spider;

/**
 * Renderers of the Spire's custom monsters: the vanilla model of the mob each one wears, with its own texture
 * (textures/entity/&lt;id&gt;.png, found from the entity type held in the render state).
 */
public final class SpireMobClient {
    /** Static holder; not instantiable. */
    private SpireMobClient() {
    }

    /** Texture of a custom Spire mob by its type id. */
    private static Identifier texture(EntityRenderState state) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType);
        return Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "textures/entity/" + id.getPath() + ".png");
    }

    private static final class Zombies extends ZombieRenderer {
        Zombies(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(ZombieRenderState state) {
            return texture(state);
        }
    }

    private static final class Skeletons extends SkeletonRenderer {
        Skeletons(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(SkeletonRenderState state) {
            return texture(state);
        }
    }

    private static final class WitherSkeletons extends WitherSkeletonRenderer {
        WitherSkeletons(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(SkeletonRenderState state) {
            return texture(state);
        }
    }

    private static final class Spiders extends SpiderRenderer<Spider> {
        Spiders(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state) {
            return texture(state);
        }
    }

    private static final class Vindicators extends VindicatorRenderer {
        Vindicators(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(IllagerRenderState state) {
            return texture(state);
        }
    }

    private static final class Evokers extends EvokerRenderer<net.minecraft.world.entity.monster.illager.Evoker> {
        Evokers(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(EvokerRenderState state) {
            return texture(state);
        }
    }

    private static final class Witches extends WitchRenderer {
        Witches(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(WitchRenderState state) {
            return texture(state);
        }
    }

    private static final class Ravagers extends RavagerRenderer {
        Ravagers(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public Identifier getTextureLocation(RavagerRenderState state) {
            return texture(state);
        }
    }

    /** Called from ClientHooks.init(). */
    public static void init() {
        EntityRendererRegistry.register(SpireMobs.CRYPT_SHAMBLER, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.CRYPT_LORD, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.MOSSBACK, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.DROWNED_HUSK, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.BONE_WARDEN, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.TIDE_STALKER, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.CINDER_ARCHER, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.DEEP_WARDEN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.ASH_REVENANT, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.VOID_SOVEREIGN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.SPIRE_SOVEREIGN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.CAVERN_LURKER, Spiders::new);
        EntityRendererRegistry.register(SpireMobs.HOLLOW_KNIGHT, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.MAGMA_BRUTE, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.VOID_WALKER, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.CINDER_MAGUS, Evokers::new);
        EntityRendererRegistry.register(SpireMobs.RIFT_CASTER, Evokers::new);
        EntityRendererRegistry.register(SpireMobs.EMBER_WITCH, Witches::new);
        EntityRendererRegistry.register(SpireMobs.ASHEN_COLOSSUS, Ravagers::new);
    }
}
//?} else {
/*import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import dev.gatebound.Gatebound;
import dev.gatebound.mob.SpireMobs;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EvokerRenderer;
import net.minecraft.client.renderer.entity.RavagerRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.client.renderer.entity.VindicatorRenderer;
import net.minecraft.client.renderer.entity.WitchRenderer;
import net.minecraft.client.renderer.entity.WitherSkeletonRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;

// Pre-1.21.2: the renderer picks the texture from the entity itself instead of a render state.
public final class SpireMobClient {
    private SpireMobClient() {
    }

    private static ResourceLocation texture(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return ResourceLocation.fromNamespaceAndPath(Gatebound.MOD_ID, "textures/entity/" + id.getPath() + ".png");
    }

    private static final class Zombies extends ZombieRenderer {
        Zombies(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Zombie entity) {
            return texture(entity);
        }
    }

    private static final class Skeletons extends SkeletonRenderer {
        Skeletons(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(AbstractSkeleton entity) {
            return texture(entity);
        }
    }

    private static final class WitherSkeletons extends WitherSkeletonRenderer {
        WitherSkeletons(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(net.minecraft.world.entity.monster.WitherSkeleton entity) {
            return texture(entity);
        }
    }

    private static final class Spiders extends SpiderRenderer<Spider> {
        Spiders(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Spider entity) {
            return texture(entity);
        }
    }

    private static final class Vindicators extends VindicatorRenderer {
        Vindicators(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Vindicator entity) {
            return texture(entity);
        }
    }

    private static final class Evokers extends EvokerRenderer<Evoker> {
        Evokers(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Evoker entity) {
            return texture(entity);
        }
    }

    private static final class Witches extends WitchRenderer {
        Witches(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Witch entity) {
            return texture(entity);
        }
    }

    private static final class Ravagers extends RavagerRenderer {
        Ravagers(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(Ravager entity) {
            return texture(entity);
        }
    }

    // Called from ClientHooks.init().
    public static void init() {
        EntityRendererRegistry.register(SpireMobs.CRYPT_SHAMBLER, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.CRYPT_LORD, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.MOSSBACK, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.DROWNED_HUSK, Zombies::new);
        EntityRendererRegistry.register(SpireMobs.BONE_WARDEN, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.TIDE_STALKER, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.CINDER_ARCHER, Skeletons::new);
        EntityRendererRegistry.register(SpireMobs.DEEP_WARDEN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.ASH_REVENANT, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.VOID_SOVEREIGN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.SPIRE_SOVEREIGN, WitherSkeletons::new);
        EntityRendererRegistry.register(SpireMobs.CAVERN_LURKER, Spiders::new);
        EntityRendererRegistry.register(SpireMobs.HOLLOW_KNIGHT, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.MAGMA_BRUTE, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.VOID_WALKER, Vindicators::new);
        EntityRendererRegistry.register(SpireMobs.CINDER_MAGUS, Evokers::new);
        EntityRendererRegistry.register(SpireMobs.RIFT_CASTER, Evokers::new);
        EntityRendererRegistry.register(SpireMobs.EMBER_WITCH, Witches::new);
        EntityRendererRegistry.register(SpireMobs.ASHEN_COLOSSUS, Ravagers::new);
    }
}
*///?}
