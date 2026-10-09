package dev.gatebound.mob;

import dev.gatebound.sound.GateboundSounds;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.level.entity.EntityAttributeRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.dungeon.DungeonRegistries;
import dev.gatebound.dungeon.FloorPopulator;
import dev.gatebound.dungeon.Spire;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Spire's own monsters: 19 entity types that wear vanilla models with their own textures, names, stats and
 * abilities. Each rank (E to S) has two regular monsters and a guardian, plus the Sovereign of the Spire.
 * Floors up to {@code DungeonConfig.customMobFloor} get custom guardians among vanilla regulars; above it everything
 * is custom (see {@link FloorPopulator}).
 */
public final class SpireMobs {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Gatebound.MOD_ID, Registries.ENTITY_TYPE);

    /** What a custom monster does besides hitting: an effect on whoever it strikes, and whether it slams the ground. */
    private record Def(String id, int band, boolean boss, Holder<MobEffect> effect, int effectTicks, int fireSeconds,
                       Supplier<EntityType<? extends Mob>> type) {
    }

    private static final List<Def> DEFS = new ArrayList<>();
    private static final Map<EntityType<?>, Def> BY_TYPE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_SLAM = new HashMap<>();

    // E, the crypts
    public static final RegistrySupplier<EntityType<Zombie>> CRYPT_SHAMBLER = zombie("crypt_shambler", 0, false, MobEffects.HUNGER, 100, 0);
    public static final RegistrySupplier<EntityType<Skeleton>> BONE_WARDEN = skeleton("bone_warden", 0, false, MobEffects.SLOWNESS, 60, 0);
    public static final RegistrySupplier<EntityType<Zombie>> CRYPT_LORD = zombie("crypt_lord", 0, true, MobEffects.WEAKNESS, 120, 0);
    // D, the mossy caverns
    public static final RegistrySupplier<EntityType<Spider>> CAVERN_LURKER = spider("cavern_lurker", 1, false, MobEffects.POISON, 80, 0);
    public static final RegistrySupplier<EntityType<Zombie>> MOSSBACK = zombie("mossback", 1, false, MobEffects.POISON, 60, 0);
    public static final RegistrySupplier<EntityType<Vindicator>> HOLLOW_KNIGHT = vindicator("hollow_knight", 1, true, MobEffects.SLOWNESS, 100, 0);
    // C, the flooded halls
    public static final RegistrySupplier<EntityType<Zombie>> DROWNED_HUSK = zombie("drowned_husk", 2, false, MobEffects.SLOWNESS, 80, 0);
    public static final RegistrySupplier<EntityType<Skeleton>> TIDE_STALKER = skeleton("tide_stalker", 2, false, MobEffects.SLOWNESS, 100, 0);
    public static final RegistrySupplier<EntityType<WitherSkeleton>> DEEP_WARDEN = witherSkeleton("deep_warden", 2, true, MobEffects.WEAKNESS, 140, 0);
    // B, the lava halls
    public static final RegistrySupplier<EntityType<Vindicator>> MAGMA_BRUTE = vindicator("magma_brute", 3, false, null, 0, 4);
    public static final RegistrySupplier<EntityType<Witch>> EMBER_WITCH = witch("ember_witch", 3, false, MobEffects.WEAKNESS, 100, 3);
    public static final RegistrySupplier<EntityType<Evoker>> CINDER_MAGUS = evoker("cinder_magus", 3, true, null, 0, 6);
    // A, the ashen reaches
    public static final RegistrySupplier<EntityType<WitherSkeleton>> ASH_REVENANT = witherSkeleton("ash_revenant", 4, false, MobEffects.WITHER, 80, 0);
    public static final RegistrySupplier<EntityType<Skeleton>> CINDER_ARCHER = skeleton("cinder_archer", 4, false, null, 0, 5);
    public static final RegistrySupplier<EntityType<Ravager>> ASHEN_COLOSSUS = ravager("ashen_colossus", 4, true, MobEffects.WITHER, 120, 0);
    // S, the void sanctum
    public static final RegistrySupplier<EntityType<Vindicator>> VOID_WALKER = vindicator("void_walker", 5, false, MobEffects.BLINDNESS, 60, 0);
    public static final RegistrySupplier<EntityType<Evoker>> RIFT_CASTER = evoker("rift_caster", 5, false, MobEffects.WITHER, 80, 0);
    public static final RegistrySupplier<EntityType<WitherSkeleton>> VOID_SOVEREIGN = witherSkeleton("void_sovereign", 5, true, MobEffects.WITHER, 160, 0);
    /** The last guardian of the whole tower. */
    public static final RegistrySupplier<EntityType<WitherSkeleton>> SPIRE_SOVEREIGN = witherSkeleton("spire_sovereign", 5, true, MobEffects.WITHER, 200, 8);

    private SpireMobs() {
    }

    public static void init() {
        ENTITY_TYPES.register();
        for (Def d : DEFS) {
            // Each type keeps the attributes of the vanilla mob it wears.
            EntityAttributeRegistry.register(() -> (EntityType<? extends LivingEntity>) d.type().get(), attributesFor(d));
        }
        EntityEvent.LIVING_HURT.register((victim, source, amount) -> {
            if (victim instanceof ServerPlayer player && source.getEntity() instanceof Mob attacker) {
                Def d = BY_TYPE.get(attacker.getType());
                if (d != null) {
                    if (d.effect() != null) {
                        player.addEffect(new MobEffectInstance(d.effect(), d.effectTicks(), d.boss() ? 1 : 0));
                    }
                    if (d.fireSeconds() > 0) {
                        player.setRemainingFireTicks(Math.max(player.getRemainingFireTicks(), d.fireSeconds() * 20));
                    }
                }
            }
            return EventResult.pass();
        });
        TickEvent.SERVER_POST.register(server -> {
            if (server.getTickCount() % 20 != 9) {
                return;
            }
            ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
            if (dungeon == null) {
                return;
            }
            long now = server.getTickCount();
            for (ServerPlayer p : dungeon.players()) {
                for (Mob m : dungeon.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(12), mob -> mob.isAlive()
                        && mob.getTarget() != null && BY_TYPE.containsKey(mob.getType()) && BY_TYPE.get(mob.getType()).boss())) {
                    slam(dungeon, m, BY_TYPE.get(m.getType()), now);
                }
            }
        });
    }

    // ------------------------------------------------------------------ lookups used by the floor populator

    public static boolean isCustom(EntityType<?> type) {
        return BY_TYPE.containsKey(type);
    }

    /** The two regular monsters of a rank. */
    public static List<EntityType<? extends Mob>> regulars(int band) {
        List<EntityType<? extends Mob>> out = new ArrayList<>();
        for (Def d : DEFS) {
            if (d.band() == band && !d.boss() && !d.id().equals("spire_sovereign")) {
                out.add(d.type().get());
            }
        }
        return out;
    }

    /** The guardian of a rank; the last tower floor uses the Sovereign instead. */
    public static EntityType<? extends Mob> boss(int band, boolean sovereign) {
        if (sovereign) {
            return SPIRE_SOVEREIGN.get();
        }
        for (Def d : DEFS) {
            if (d.band() == band && d.boss() && !d.id().equals("spire_sovereign")) {
                return d.type().get();
            }
        }
        return CRYPT_LORD.get();
    }

    // ------------------------------------------------------------------ abilities

    /** The guardians stamp the ground when a player stands close: damage, a shove and a short effect. */
    private static void slam(ServerLevel level, Mob boss, Def d, long now) {
        if (NEXT_SLAM.getOrDefault(boss.getUUID(), 0L) > now) {
            return;
        }
        List<ServerPlayer> near = level.getEntitiesOfClass(ServerPlayer.class, boss.getBoundingBox().inflate(6, 2, 6), pl -> pl.isAlive() && !pl.isSpectator());
        if (near.isEmpty()) {
            return;
        }
        NEXT_SLAM.put(boss.getUUID(), now + 160);
        if (NEXT_SLAM.size() > 256) {
            NEXT_SLAM.values().removeIf(t -> t < now);
        }
        float dmg = 4f + 2f * d.band();
        for (ServerPlayer p : near) {
            Vec3 away = p.position().subtract(boss.position()).multiply(1, 0, 1);
            Vec3 dir = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
            p.hurtServer(level, level.damageSources().mobAttack(boss), dmg);
            p.setDeltaMovement(p.getDeltaMovement().add(dir.x * 1.1, 0.5, dir.z * 1.1));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
            if (d.effect() != null) {
                p.addEffect(new MobEffectInstance(d.effect(), d.effectTicks(), 1));
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, boss.getX(), boss.getY() + 0.2, boss.getZ(), 6, 2.5, 0.1, 2.5, 0.0);
        level.playSound(null, boss.blockPosition(), GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    // ------------------------------------------------------------------ registration helpers

    private static ResourceKey<EntityType<?>> key(String id) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, id));
    }

    private static <T extends Mob> RegistrySupplier<EntityType<T>> reg(String id, int band, boolean boss, Holder<MobEffect> effect,
                                                                    int effectTicks, int fire, EntityType.EntityFactory<T> factory,
                                                                    float w, float h, float eye) {
        RegistrySupplier<EntityType<T>> s = ENTITY_TYPES.register(id, () ->
                EntityType.Builder.of(factory, MobCategory.MONSTER).sized(w, h).eyeHeight(eye).clientTrackingRange(10).build(key(id)));
        Def def = new Def(id, band, boss, effect, effectTicks, fire, () -> s.get());
        DEFS.add(def);
        // The type object exists only once registered, so the reverse lookup is filled by the first query of the type.
        s.listen(type -> BY_TYPE.put(type, def));
        return s;
    }

    private static RegistrySupplier<EntityType<Zombie>> zombie(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Zombie::new, 0.6f, 1.95f, 1.74f);
    }

    private static RegistrySupplier<EntityType<Skeleton>> skeleton(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Skeleton::new, 0.6f, 1.99f, 1.74f);
    }

    private static RegistrySupplier<EntityType<WitherSkeleton>> witherSkeleton(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, WitherSkeleton::new, 0.7f, 2.4f, 2.1f);
    }

    private static RegistrySupplier<EntityType<Spider>> spider(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Spider::new, 1.4f, 0.9f, 0.65f);
    }

    private static RegistrySupplier<EntityType<Vindicator>> vindicator(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Vindicator::new, 0.6f, 1.95f, 1.74f);
    }

    private static RegistrySupplier<EntityType<Evoker>> evoker(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Evoker::new, 0.6f, 1.95f, 1.74f);
    }

    private static RegistrySupplier<EntityType<Witch>> witch(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Witch::new, 0.6f, 1.95f, 1.62f);
    }

    private static RegistrySupplier<EntityType<Ravager>> ravager(String id, int band, boolean boss, Holder<MobEffect> e, int t, int f) {
        return reg(id, band, boss, e, t, f, Ravager::new, 1.95f, 2.2f, 1.9f);
    }

    private static Supplier<AttributeSupplier.Builder> attributesFor(Def d) {
        return switch (d.id()) {
            case "crypt_shambler", "crypt_lord", "mossback", "drowned_husk" -> Zombie::createAttributes;
            case "bone_warden", "tide_stalker", "cinder_archer" -> AbstractSkeleton::createAttributes;
            case "deep_warden", "ash_revenant", "void_sovereign", "spire_sovereign" -> AbstractSkeleton::createAttributes;
            case "cavern_lurker" -> Spider::createAttributes;
            case "hollow_knight", "magma_brute", "void_walker" -> Vindicator::createAttributes;
            case "cinder_magus", "rift_caster" -> Evoker::createAttributes;
            case "ember_witch" -> Witch::createAttributes;
            default -> Ravager::createAttributes;
        };
    }
}
