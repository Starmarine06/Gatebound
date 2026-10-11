package dev.gatebound.dungeon;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.Gatebound;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An open gate is a breach: until its guardian is slain from inside the gate dungeon, it keeps leaking monsters into
 * the overworld, faster the longer it stays open. Leaked monsters are persistent and immune to sunlight.
 */
public final class GateLeak {
    public static final String TAG_LEAK = "gatebound_leak";

    private static final Map<BlockPos, Long> NEXT = new HashMap<>();
    private static final Set<BlockPos> WARNED = new HashSet<>();
    private static final Set<BlockPos> CLEANED = new HashSet<>();

    /** Static holder; not instantiable. */
    private GateLeak() {
    }

    /** Leaks monsters out of open gates over time and banishes them when a gate closes. */
    public static void tick(MinecraftServer server, DungeonStore store, long now) {
        if (!DungeonConfig.gateLeaks) {
            return;
        }
        for (DungeonStore.GateRef g : store.gates) {
            if (g.cleared && DungeonConfig.leakDespawnOnClose && CLEANED.add(g.pos.immutable())) {
                banish(server, g);
            }
            if (g.cleared || !Spire.isGate(g.floor)) {
                continue;
            }
            ServerLevel level = server.getLevel(g.dim);
            if (level == null || !level.hasChunkAt(g.pos) || !(level.getBlockState(g.pos).getBlock() instanceof GateBlock)) {
                continue;
            }
            long next = NEXT.getOrDefault(g.pos, 0L);
            if (now < next) {
                continue;
            }
            GateRank rank = GateRank.byIndex((g.floor - Spire.GATE_BASE) % 6);
            long age = now - g.created;
            long interval = Math.max(DungeonConfig.leakMinInterval, DungeonConfig.leakInterval - age / 600);
            NEXT.put(g.pos.immutable(), now + interval);

            int cap = DungeonConfig.leakCap + 3 * rank.ordinal();
            List<Mob> nearby = level.getEntitiesOfClass(Mob.class, new AABB(g.pos).inflate(40), m -> m.entityTags().contains(TAG_LEAK));
            if (nearby.size() >= cap) {
                continue;
            }
            int amount = Math.min(cap - nearby.size(), 1 + (int) (age / 36000));
            int spawned = 0;
            for (int i = 0; i < amount; i++) {
                if (spawnOne(level, g.pos, rank, level.getRandom())) {
                    spawned++;
                }
            }
            if (spawned > 0 && WARNED.add(g.pos.immutable())) {
                Component msg = Component.literal("[Gatebound] Monsters are pouring out of the gate! Enter it and slay its guardian to stop them.")
                        .withStyle(rank.color(), ChatFormatting.BOLD);
                for (ServerPlayer p : level.players()) {
                    if (p.distanceToSqr(g.pos.getX() + 0.5, g.pos.getY(), g.pos.getZ() + 0.5) < 64 * 64) {
                        p.sendSystemMessage(msg);
                    }
                }
            }
        }
    }

    /** The gate has closed: everything it leaked is pulled back through in a burst of smoke. */
    private static void banish(MinecraftServer server, DungeonStore.GateRef g) {
        ServerLevel level = server.getLevel(g.dim);
        if (level == null || !level.hasChunkAt(g.pos)) {
            CLEANED.remove(g.pos);
            return;
        }
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(g.pos).inflate(64), x -> x.entityTags().contains(TAG_LEAK))) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, m.getX(), m.getY() + m.getBbHeight() * 0.5, m.getZ(), 15, 0.3, 0.5, 0.3, 0.15);
            m.discard();
        }
        level.playSound(null, g.pos, GateboundSounds.of(SoundEvents.PORTAL_TRAVEL), SoundSource.HOSTILE, 0.6f, 0.5f);
    }

    /** Spawns one leaked monster in front of a gate. */
    private static boolean spawnOne(ServerLevel level, BlockPos gate, GateRank rank, RandomSource rng) {
        BlockState st = level.getBlockState(gate);
        Direction f = st.getBlock() instanceof GateBlock ? st.getValue(GateBlock.FACING) : Direction.SOUTH;
        for (int attempt = 0; attempt < 8; attempt++) {
            int out = 2 + rng.nextInt(4);
            int side = rng.nextInt(5) - 2;
            int x = gate.getX() + f.getStepX() * out + f.getStepZ() * side;
            int z = gate.getZ() + f.getStepZ() * out + f.getStepX() * side;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - gate.getY()) > 6) {
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getFluidState(pos).isEmpty() || !level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
                continue;
            }
            List<EntityType<? extends Mob>> pool = rank.mobPool();
            Mob mob = pool.get(rng.nextInt(pool.size())).spawn(level, pos, EntitySpawnReason.EVENT);
            if (mob == null) {
                continue;
            }
            double mult = 1.0 + 0.3 * rank.ordinal();
            AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(hp.getBaseValue() * mult);
                mob.setHealth(mob.getMaxHealth());
            }
            AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg != null) {
                dmg.setBaseValue(dmg.getBaseValue() * (1.0 + 0.15 * rank.ordinal()));
            }
            mob.addTag(TAG_LEAK);
            mob.setPersistenceRequired();
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 0.3, 0.6, 0.3, 0.2);
            level.playSound(null, pos, GateboundSounds.of(SoundEvents.PORTAL_TRAVEL), SoundSource.HOSTILE, 0.25f, 1.4f);
            Gatebound.LOGGER.debug("Gate at {} leaked a mob at {}", gate, pos);
            return true;
        }
        return false;
    }
}
