package dev.gatebound.skill;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import dev.gatebound.echo.EchoManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.gatebound.item.KnifeItems;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Rogue skills and the Shadow / Mark / Ambush system.
 * Shadow (invisible, aggro dropped) is broken by taking damage or attacking; the first attack out of it is an Ambush.
 * Marks make an enemy take extra damage from its marker. Shadow Meld hides the Rogue inside another entity's shadow,
 * Shadow Walk hops between shadows while W is held, and Shadow Possession (level 100) binds enemies with living shadow.
 * All state is in memory only: nothing is saved, and everything is cleared on quit, death and server stop.
 */
public final class RogueSkills {
    public static final int STEP_COST = 8;
    public static final int KNIVES_COST = 10;
    public static final int VANISH_COST = 18;
    public static final int MARK_COST = 12;
    public static final int MELD_COST = 16;
    public static final int VENOM_COST = 14;
    public static final int BACKSTAB_COST = 14;
    public static final int SMOKE_COST = 20;
    public static final int FAN_COST = 22;
    public static final int DANCE_BLADES_COST = 30;
    public static final int EXECUTE_COST = 35;
    public static final int SHADOW_DANCE_COST = 45;
    public static final int CUTS_COST = 60;
    public static final int POSSESSION_COST = 80;

    /** Entity tag put on mobs whose AI Shadow Possession switched off, so a stray one is always freed when it loads. */
    private static final String FROZEN_TAG = "gatebound_shadow_bound";

    private enum State { NONE, SHADOW, MELD, DANCE }

    private record MarkInfo(UUID owner, long until) {
    }

    private static final class Meld {
        UUID host;
        final long start;
        long lastDrain;
        boolean walking;
        Vec3 lastHostPos;

        Meld(UUID host, long start) {
            this.host = host;
            this.start = start;
            this.lastDrain = start;
        }
    }

    private static final class Walk {
        final Vec3 dest;
        final UUID entity;
        final long deadline;

        Walk(Vec3 dest, UUID entity, long deadline) {
            this.dest = dest;
            this.entity = entity;
            this.deadline = deadline;
        }
    }

    private static final class Poss {
        final long start;
        long until;
        final long hardEnd;
        final List<UUID> bound = new ArrayList<>();
        final Map<UUID, Long> boundAt = new HashMap<>();
        final Set<UUID> bosses = new HashSet<>();
        final Set<UUID> possessed = new HashSet<>();
        final Map<UUID, Long> pulling = new HashMap<>();
        final Map<UUID, Long> thrown = new HashMap<>();
        int sneakTicks;
        int armCursor;

        Poss(long start, long until) {
            this.start = start;
            this.until = until;
            this.hardEnd = until + 200;
        }
    }

    private record Task(long at, Consumer<MinecraftServer> run) {
    }

    private static final Map<UUID, Long> SHADOW = new HashMap<>();
    private static final Map<UUID, Long> DANCE = new HashMap<>();
    private static final Map<UUID, Long> VENOM = new HashMap<>();
    private static final Map<UUID, MarkInfo> MARKS = new HashMap<>();
    private static final Map<UUID, Meld> MELD = new HashMap<>();
    private static final Map<UUID, Walk> WALK = new HashMap<>();
    private static final Map<UUID, Long> HOP_READY = new HashMap<>();
    private static final Map<UUID, Poss> POSSESSION = new HashMap<>();
    /** Every mob currently held by some Rogue's possession. */
    private static final Set<UUID> BOUND_ALL = new HashSet<>();
    private static final List<Task> TASKS = new ArrayList<>();

    /** Guards our own damage from re-entering the hurt event. */
    private static boolean applying;

    private RogueSkills() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static void init() {
        TickEvent.SERVER_POST.register(RogueSkills::tick);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity, source);
            return EventResult.pass();
        });
        // A crash while hidden would leave the saved player invulnerable: undo it on the next join.
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player.entityTags().contains(HIDDEN_TAG) && !HIDDEN.contains(player.getUUID())) {
                player.removeTag(HIDDEN_TAG);
                Compat.setPermanentlyInvulnerable(player, false);
            }
        });
        PlayerEvent.PLAYER_QUIT.register(player -> clearPlayer(player.level().getServer(), player.getUUID()));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> clearPlayer(player.level().getServer(), player.getUUID()));
        // A mob saved while frozen (chunk unload, crash) wakes up again when it next loads.
        EntityEvent.ADD.register((entity, world) -> {
            if (entity instanceof Mob mob && mob.entityTags().contains(FROZEN_TAG) && !BOUND_ALL.contains(mob.getUUID())) {
                mob.setNoAi(false);
                mob.removeTag(FROZEN_TAG);
            }
            return EventResult.pass();
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            for (UUID id : new ArrayList<>(POSSESSION.keySet())) {
                endPossession(server, id, false);
            }
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            SHADOW.clear();
            DANCE.clear();
            VENOM.clear();
            MARKS.clear();
            MELD.clear();
            WALK.clear();
            HOP_READY.clear();
            POSSESSION.clear();
            BOUND_ALL.clear();
            TASKS.clear();
        });
    }

    private static void clearPlayer(MinecraftServer server, UUID id) {
        if (HIDDEN.contains(id) && server != null && playerOf(server, id) != null) {
            setHidden(playerOf(server, id), false);
        }
        HIDDEN.remove(id);
        SHADOW.remove(id);
        DANCE.remove(id);
        VENOM.remove(id);
        MELD.remove(id);
        WALK.remove(id);
        HOP_READY.remove(id);
        MARKS.values().removeIf(m -> m.owner().equals(id));
        endPossession(server, id, false);
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        MARKS.remove(entity.getUUID());
        MinecraftServer server = entity.level().getServer();
        if (entity instanceof ServerPlayer player) {
            player.removeEffect(MobEffects.INVISIBILITY);
            clearPlayer(server, player.getUUID());
            return;
        }
        if (source.getEntity() instanceof ServerPlayer killer) {
            Poss ps = POSSESSION.get(killer.getUUID());
            if (ps != null) {
                ps.until = Math.min(ps.hardEnd, ps.until + 20);
            }
        }
        if (BOUND_ALL.remove(entity.getUUID())) {
            for (Poss ps : POSSESSION.values()) {
                forget(ps, entity.getUUID());
            }
        }
    }

    private static void forget(Poss ps, UUID id) {
        ps.bound.remove(id);
        ps.boundAt.remove(id);
        ps.bosses.remove(id);
        ps.possessed.remove(id);
        ps.pulling.remove(id);
    }

    // ---------------------------------------------------------------- helpers

    private static long now(ServerPlayer p) {
        return p.level().getServer().getTickCount();
    }

    private static void sound(ServerPlayer player, ServerLevel level, SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0f, pitch);
    }

    private static boolean isRogue(ServerPlayer p) {
        return GateboundClass.ROGUE.id().equals(PlayerDataStore.of(p).classId);
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static boolean validTarget(LivingEntity e, ServerPlayer p) {
        return e != p && e.isAlive() && !(e instanceof Player) && !EchoManager.isEcho(e) && !(e instanceof dev.gatebound.trade.GateboundNpc);
    }

    private static boolean isBoss(LivingEntity e) {
        String id = typeId(e);
        return RogueConfig.meldBlocked(id) || RogueConfig.possessionBlocked(id);
    }

    /** Living, valid, non-player entities along the line of sight, nearest first. */
    private static List<LivingEntity> targets(ServerPlayer p, ServerLevel level, double range, int max, net.minecraft.core.particles.ParticleOptions trail) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : SkillTargeting.ray(p, level, range, 12, trail)) {
            if (validTarget(e, p)) {
                out.add(e);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<LivingEntity> enemies(ServerPlayer p, ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : SkillTargeting.enemiesAround(p, level, radius)) {
            if (validTarget(e, p)) {
                out.add(e);
            }
        }
        return out;
    }

    /** First living entity (players included, caster excluded) on the line of sight. */
    private static LivingEntity lookedAt(ServerPlayer p, ServerLevel level, double range) {
        Vec3 from = p.getEyePosition();
        Vec3 end = from.add(p.getLookAngle().scale(range));
        BlockHitResult block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        final Vec3 rayEnd = end;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, p.getBoundingBox().expandTowards(rayEnd.subtract(from)).inflate(1.0),
                x -> x instanceof LivingEntity le && le.isAlive() && !le.isSpectator())) {
            if (e.getBoundingBox().inflate(0.3).clip(from, rayEnd).isPresent()) {
                double d = e.distanceToSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = (LivingEntity) e;
                }
            }
        }
        return best;
    }

    private static boolean miss(ServerPlayer p, int cost, String hint) {
        ManaState.add(p, cost);
        Compat.overlay(p, Component.literal(hint).withStyle(ChatFormatting.GRAY));
        return true; // the hint replaces the generic no-target message
    }

    private static void schedule(long at, Consumer<MinecraftServer> run) {
        TASKS.add(new Task(at, run));
    }

    private static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private static void moveTo(Entity e, ServerLevel level, Vec3 pos) {
        Compat.moveKeepingRotation(e, level, pos);
    }

    private static boolean clear(ServerLevel level, Entity who, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who)).getType() == HitResult.Type.MISS;
    }

    private static Vec3 flatLook(LivingEntity e) {
        Vec3 look = e.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** True when the attacker is behind the victim (the inverse of the parry's "in front" test, with the same 60 degree arc). */
    private static boolean isBehind(ServerPlayer attacker, LivingEntity victim) {
        Vec3 to = attacker.position().subtract(victim.position());
        Vec3 flatTo = new Vec3(to.x, 0, to.z);
        if (flatTo.lengthSqr() < 1.0E-4) {
            return true;
        }
        return flatLook(victim).dot(flatTo.normalize()) < -0.5;
    }

    // ---------------------------------------------------------------- shadow state

    private static boolean inDance(ServerPlayer p, long now) {
        return DANCE.getOrDefault(p.getUUID(), 0L) > now;
    }

    private static boolean inShadow(ServerPlayer p, long now) {
        return SHADOW.getOrDefault(p.getUUID(), 0L) > now || MELD.containsKey(p.getUUID()) || inDance(p, now);
    }

    /** Dance and Possession run without any mana upkeep. */
    private static boolean isFree(ServerPlayer p, long now) {
        return inDance(p, now) || POSSESSION.containsKey(p.getUUID());
    }

    private static void enterShadow(ServerPlayer p, ServerLevel level, int ticks) {
        SHADOW.put(p.getUUID(), now(p) + ticks);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks + 10, 0, false, false));
        dropAggro(p, level);
    }

    private static void breakShadow(ServerPlayer p, ServerLevel level) {
        UUID id = p.getUUID();
        boolean was = SHADOW.remove(id) != null | MELD.remove(id) != null;
        WALK.remove(id);
        if (was) {
            p.removeEffect(MobEffects.INVISIBILITY);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
        }
    }

    /** Spends the Rogue's shadow on an attack. NONE means the attack is a normal one. */
    private static State takeShadow(ServerPlayer p, ServerLevel level, long now) {
        if (inDance(p, now)) {
            return State.DANCE;
        }
        boolean melded = MELD.containsKey(p.getUUID());
        if (melded || SHADOW.getOrDefault(p.getUUID(), 0L) > now) {
            breakShadow(p, level);
            return melded ? State.MELD : State.SHADOW;
        }
        return State.NONE;
    }

    private static void dropAggro(ServerPlayer p, ServerLevel level) {
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32), x -> x.getTarget() == p)) {
            m.setTarget(null);
            m.setLastHurtByMob(null);
        }
    }

    // ---------------------------------------------------------------- damage

    private static double ambushMult(PlayerData data, State st) {
        return st == State.DANCE ? RogueConfig.danceAmbush : RogueConfig.ambushBase + RogueConfig.ambushPerLevel * data.level;
    }

    /**
     * Damage multiplier for a hit by this Rogue on the victim: mark bonus, ambush (from Shadow, from behind, from a meld),
     * guaranteed crit on a marked ambush, and the Luck / Agility passive crit. Spends the shadow if it was up.
     */
    private static double multiplier(ServerPlayer p, ServerLevel level, LivingEntity victim, boolean forceBehind, boolean forceAmbush) {
        long now = now(p);
        PlayerData data = PlayerDataStore.of(p);
        double f = 1;
        MarkInfo mark = MARKS.get(victim.getUUID());
        boolean marked = mark != null && mark.owner().equals(p.getUUID()) && mark.until() > now;
        if (marked) {
            f *= 1 + RogueConfig.markBonus;
        }
        State st = takeShadow(p, level, now);
        boolean ambush = st != State.NONE || forceAmbush;
        boolean crit = false;
        if (ambush) {
            f *= st == State.NONE ? ambushMult(data, State.SHADOW) : ambushMult(data, st);
            if (st == State.MELD) {
                f *= RogueConfig.meldMult;
            }
            if (marked) {
                crit = true;
                ManaState.add(p, RogueConfig.markedAmbushMana);
            }
        }
        if ((ambush && isBehind(p, victim)) || forceBehind) {
            f *= RogueConfig.behindMult;
        }
        if (isRogue(p)) {
            if (!crit && p.getRandom().nextDouble() < Math.min(RogueConfig.maxCritChance, RogueConfig.critPerLuck * data.get(Stat.LUCK))) {
                crit = true;
            }
            if (crit) {
                f *= Math.min(RogueConfig.maxCritMult, RogueConfig.critBase + RogueConfig.critDamagePerAgility * data.get(Stat.AGILITY));
            }
        } else if (crit) {
            f *= RogueConfig.critBase;
        }
        if (ambush) {
            Compat.overlay(p, Component.literal("AMBUSH x" + String.format("%.1f", f)).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
            level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY(0.6), victim.getZ(), 14, 0.3, 0.4, 0.3, 0.25);
            level.sendParticles(ParticleTypes.SQUID_INK, victim.getX(), victim.getY(0.5), victim.getZ(), 10, 0.3, 0.4, 0.3, 0.05);
            sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_CRIT), 0.7f);
        } else if (crit) {
            level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY(0.6), victim.getZ(), 8, 0.3, 0.4, 0.3, 0.2);
        }
        return f;
    }

    private static void applyVenom(ServerPlayer p, LivingEntity victim, long now) {
        if (VENOM.getOrDefault(p.getUUID(), 0L) <= now) {
            return;
        }
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
        MarkInfo mark = MARKS.get(victim.getUUID());
        if (mark != null && mark.owner().equals(p.getUUID()) && mark.until() > now) {
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 1));
        }
    }

    /** A skill hit: runs the full Rogue multiplier and damages through the magic source like the other classes' skills. */
    private static void hit(ServerPlayer p, ServerLevel level, LivingEntity target, double damage, boolean forceBehind, boolean forceAmbush) {
        double f = multiplier(p, level, target, forceBehind, forceAmbush);
        applyVenom(p, target, now(p));
        rawHurt(p, level, target, (float) (damage * f));
    }

    private static void rawHurt(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        applying = true;
        try {
            Compat.setInvulnerableTime(target, 0);
            target.hurtServer(level, level.damageSources().indirectMagic(p, p), amount);
        } finally {
            applying = false;
        }
    }

    private static void mark(ServerPlayer p, LivingEntity target, int ticks) {
        MARKS.put(target.getUUID(), new MarkInfo(p.getUUID(), now(p) + ticks));
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
    }

    /** Called from CombatHandler.onHurt before anything else. */
    public static EventResult onHurt(LivingEntity victim, DamageSource source, float amount, ServerLevel level) {
        if (applying || amount <= 0) {
            return EventResult.pass();
        }
        long now = level.getServer().getTickCount();
        if (victim instanceof ServerPlayer rogue) {
            Poss ps = POSSESSION.get(rogue.getUUID());
            if (ps != null && source.is(DamageTypeTags.IS_PROJECTILE) && source.getDirectEntity() instanceof Projectile proj
                    && ManaState.get(rogue) >= RogueConfig.deflectMana) {
                ManaState.add(rogue, -RogueConfig.deflectMana);
                level.sendParticles(ParticleTypes.SQUID_INK, proj.getX(), proj.getY(), proj.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
                sound(rogue, level, GateboundSounds.of(SoundEvents.SHIELD_BLOCK.value()), 1.4f);
                proj.discard();
                return EventResult.interruptFalse();
            }
            if ((MELD.containsKey(rogue.getUUID()) || WALK.containsKey(rogue.getUUID()))
                    && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                return EventResult.interruptFalse(); // the body is only shadow while melded or walking
            }
            if (!inDance(rogue, now) && (SHADOW.containsKey(rogue.getUUID()) || MELD.containsKey(rogue.getUUID()))) {
                breakShadow(rogue, level);
            }
            return EventResult.pass();
        }
        if (!(source.getEntity() instanceof ServerPlayer p) || !(victim instanceof Mob mob) || EchoManager.isEcho(mob)
                || !CombatHandler.isMelee(source)) {
            return EventResult.pass();
        }
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (isRogue(p) && KnifeItems.isMeleeWeapon(held)) {
            // Rogues fight with knives only: other weapons (axes included) can be held and used, but do no damage.
            if (now % 20 == 0) {
                Compat.overlay(p, Component.literal("A Rogue only fights with knives.").withStyle(ChatFormatting.GRAY));
            }
            return EventResult.interruptFalse();
        }
        Poss ps = POSSESSION.get(p.getUUID());
        if (ps != null && ps.bound.contains(mob.getUUID())) {
            onBoundHit(p, level, ps, mob);
        }
        double f = multiplier(p, level, mob, false, false);
        if (KnifeItems.isKnife(held) && isBehind(p, mob)) {
            f *= RogueConfig.knifeBehindMult;
            level.sendParticles(ParticleTypes.CRIT, mob.getX(), mob.getY(0.6), mob.getZ(), 6, 0.25, 0.3, 0.25, 0.1);
        }
        applyVenom(p, mob, now);
        float extra = (float) (amount * (f - 1));
        if (extra > 0.01f) {
            // Applied next tick: damaging inside the event would be swallowed by the victim's invulnerability frames.
            UUID victimId = mob.getUUID();
            UUID owner = p.getUUID();
            schedule(now + 1, server -> {
                ServerPlayer sp = playerOf(server, owner);
                if (sp != null && sp.level() instanceof ServerLevel l && l.getEntity(victimId) instanceof LivingEntity le && le.isAlive()) {
                    rawHurt(sp, l, le, extra);
                }
            });
        }
        return EventResult.pass();
    }

    // ---------------------------------------------------------------- tick

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            for (Task t : TASKS) {
                if (t.at() <= now) {
                    due.add(t);
                }
            }
            TASKS.removeAll(due);
            for (Task t : due) {
                t.run().accept(server);
            }
        }
        if (now % 20 == 0 && !MARKS.isEmpty()) {
            MARKS.values().removeIf(m -> m.until() <= now);
        }
        if (now % 5 == 0) {
            enforceArmor(server);
        }
        if (now % 40 == 0) {
            // Rogues see well in the dark: a hidden, permanent-feeling Night Vision (no icon, no particles).
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isAlive() && isRogue(p)) {
                    p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 440, 0, true, false, false));
                }
            }
        }
        if (SHADOW.isEmpty() && MELD.isEmpty() && POSSESSION.isEmpty() && WALK.isEmpty() && DANCE.isEmpty() && HIDDEN.isEmpty()) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            setHidden(p, p.isAlive() && inShadow(p, now));
            if (!SHADOW.containsKey(id) && !MELD.containsKey(id) && !POSSESSION.containsKey(id) && !DANCE.containsKey(id)) {
                continue;
            }
            if (!p.isAlive() || p.isSpectator()) {
                clearPlayer(server, id);
                continue;
            }
            ServerLevel level = p.level();
            if (inShadow(p, now) || SHADOW.containsKey(id)) {
                tickShadow(p, level, now);
            }
            if (MELD.containsKey(id)) {
                tickMeld(p, level, now);
            }
            tickWalk(p, level, now);
            if (POSSESSION.containsKey(id)) {
                tickPossession(server, p, level, now);
            }
        }
    }

    /** Rogues can only wear chainmail: anything else they put on is handed straight back. */
    private static void enforceArmor(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator() || !isRogue(p)) {
                continue;
            }
            for (net.minecraft.world.entity.EquipmentSlot slot : KnifeItems.ARMOR_SLOTS) {
                net.minecraft.world.item.ItemStack stack = p.getItemBySlot(slot);
                if (KnifeItems.isForbiddenArmor(stack)) {
                    p.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                    if (!p.getInventory().add(stack)) {
                        p.spawnAtLocation(p.level(), stack);
                    }
                    Compat.overlay(p, Component.literal("A Rogue can only wear chainmail.").withStyle(ChatFormatting.GRAY));
                }
            }
        }
    }

    /** Players we made invulnerable so mobs cannot target them (vanilla targeting skips invulnerable players). */
    private static final Set<UUID> HIDDEN = new HashSet<>();
    private static final String HIDDEN_TAG = "gatebound_hidden";

    private static void setHidden(ServerPlayer p, boolean hidden) {
        UUID id = p.getUUID();
        if (hidden && !HIDDEN.contains(id) && !Compat.isPermanentlyInvulnerable(p)) {
            HIDDEN.add(id);
            p.addTag(HIDDEN_TAG);
            Compat.setPermanentlyInvulnerable(p, true);
        } else if (!hidden && HIDDEN.remove(id)) {
            p.removeTag(HIDDEN_TAG);
            Compat.setPermanentlyInvulnerable(p, false);
        }
    }

    private static void tickShadow(ServerPlayer p, ServerLevel level, long now) {
        UUID id = p.getUUID();
        if (DANCE.containsKey(id) && DANCE.get(id) <= now) {
            DANCE.remove(id);
        }
        if (!MELD.containsKey(id) && !inDance(p, now) && SHADOW.getOrDefault(id, 0L) <= now) {
            SHADOW.remove(id);
            WALK.remove(id);
            p.removeEffect(MobEffects.INVISIBILITY);
            return;
        }
        if (now % 3 == 0 || MELD.containsKey(id) || WALK.containsKey(id)) {
            dropAggro(p, level);
        }
        if (now % 40 == 0) {
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
        }
    }

    // ---------------------------------------------------------------- shadow meld

    public static boolean shadowMeld(ServerPlayer p, ServerLevel level, PlayerData data) {
        UUID id = p.getUUID();
        if (MELD.containsKey(id)) {
            ManaState.add(p, MELD_COST);
            endMeld(p, level, "You step out of the shadow.");
            return true;
        }
        LivingEntity host = lookedAt(p, level, RogueConfig.meldRange);
        if (host != null && RogueConfig.meldBlocked(typeId(host))) {
            return miss(p, MELD_COST, "Its shadow is too strong to hide in.");
        }
        long now = now(p);
        Meld m = new Meld(host == null ? id : host.getUUID(), now);
        m.lastHostPos = host == null ? p.position() : host.position();
        MELD.put(id, m);
        SHADOW.put(id, now + 40);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
        dropAggro(p, level);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 25, 0.4, 0.6, 0.4, 0.02);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 0.6f);
        Compat.overlay(p, Component.literal(host == null ? "You melt into your own shadow." : "You melt into the shadow of " + host.getName().getString() + ".")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    private static void endMeld(ServerPlayer p, ServerLevel level, String message) {
        if (MELD.remove(p.getUUID()) == null) {
            return;
        }
        WALK.remove(p.getUUID());
        SHADOW.put(p.getUUID(), now(p) + 20);
        level.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0.04);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.1f);
        if (message != null) {
            Compat.overlay(p, Component.literal(message).withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tickMeld(ServerPlayer p, ServerLevel level, long now) {
        UUID id = p.getUUID();
        Meld m = MELD.get(id);
        PlayerData data = PlayerDataStore.of(p);
        boolean free = isFree(p, now);
        if (now - m.start > 5 && p.isShiftKeyDown()) {
            endMeld(p, level, "You step out of the shadow.");
            return;
        }
        long cap = (RogueConfig.meldBaseSeconds + data.level / 10) * 20L;
        if (!free && now - m.start > cap) {
            endMeld(p, level, "The shadow fades.");
            return;
        }
        if (!free && now - m.lastDrain >= 20) {
            m.lastDrain = now;
            double have = ManaState.get(p);
            if (have < RogueConfig.drainPerSecond) {
                endMeld(p, level, "Out of mana: the shadow lets go.");
                return;
            }
            ManaState.set(p, have - RogueConfig.drainPerSecond);
        }
        SHADOW.put(id, now + 40);
        if (m.walking || m.host.equals(id)) {
            return;
        }
        Entity host = level.getEntity(m.host);
        if (!(host instanceof LivingEntity h) || !h.isAlive() || h.isRemoved()) {
            if (m.lastHostPos != null) {
                moveTo(p, level, m.lastHostPos);
            }
            endMeld(p, level, "Your host is gone.");
            return;
        }
        m.lastHostPos = h.position();
        Vec3 above = topOf(h);
        if (p.position().distanceToSqr(above) > 0.0025) {
            moveTo(p, level, above);
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
    }

    // ---------------------------------------------------------------- shadow walk

    private static void tickWalk(ServerPlayer p, ServerLevel level, long now) {
        UUID id = p.getUUID();
        Walk w = WALK.get(id);
        if (w != null) {
            advanceWalk(p, level, w, now);
            return;
        }
        if (!inShadow(p, now) || HOP_READY.getOrDefault(id, 0L) > now) {
            return;
        }
        if (!Compat.input(p).forward()) {
            return;
        }
        Meld m = MELD.get(id);
        UUID currentHost = m == null ? null : m.host;
        Walk next = findNode(p, level, currentHost, now);
        if (next == null) {
            HOP_READY.put(id, now + 10);
            level.sendParticles(ParticleTypes.SMOKE, p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.2, 0.2, 0.0);
            return;
        }
        if (!isFree(p, now)) {
            double have = ManaState.get(p);
            if (have < RogueConfig.hopCost) {
                HOP_READY.put(id, now + 20);
                Compat.overlay(p, Component.translatable("skill.gatebound.not_enough_mana"));
                return;
            }
            ManaState.set(p, have - RogueConfig.hopCost);
        }
        WALK.put(id, next);
        if (m != null) {
            m.walking = true;
        }
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.6f);
    }

    private static void advanceWalk(ServerPlayer p, ServerLevel level, Walk w, long now) {
        UUID id = p.getUUID();
        Vec3 target = w.dest;
        Entity targetEntity = null;
        if (w.entity != null) {
            targetEntity = level.getEntity(w.entity);
            if (targetEntity instanceof LivingEntity le && le.isAlive()) {
                target = topOf(le);
            } else {
                targetEntity = null;
            }
        }
        Vec3 pos = p.position();
        Vec3 delta = target.subtract(pos);
        double len = delta.length();
        boolean timedOut = now > w.deadline;
        if (len <= 0.8 || timedOut) {
            finishWalk(p, level, w, targetEntity, now);
            return;
        }
        Vec3 next = pos.add(delta.scale(Math.min(RogueConfig.hopSpeed, len) / len));
        if (!clear(level, p, pos.add(0, 0.9, 0), next.add(0, 0.9, 0))) {
            finishWalk(p, level, w, null, now);
            return;
        }
        moveTo(p, level, next);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        level.sendParticles(ParticleTypes.SQUID_INK, pos.x, pos.y + 0.6, pos.z, 4, 0.15, 0.3, 0.15, 0.01);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.3, pos.z, 2, 0.15, 0.1, 0.15, 0.01);
    }

    /** Where a melded Rogue floats: just above the host's head, so the view is clear. */
    private static Vec3 topOf(LivingEntity h) {
        return new Vec3(h.getX(), h.getBoundingBox().maxY + 0.2, h.getZ());
    }

    private static void finishWalk(ServerPlayer p, ServerLevel level, Walk w, Entity into, long now) {
        UUID id = p.getUUID();
        WALK.remove(id);
        HOP_READY.put(id, now + RogueConfig.hopDelayTicks);
        Meld m = MELD.get(id);
        if (into != null) {
            if (m == null) {
                m = new Meld(into.getUUID(), now);
                MELD.put(id, m);
            }
            m.host = into.getUUID();
            m.lastHostPos = into.position();
        } else if (m != null) {
            m.host = id;
            m.lastHostPos = p.position();
        }
        if (m != null) {
            m.walking = false;
        }
        SHADOW.put(id, Math.max(SHADOW.getOrDefault(id, 0L), now + 40));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.8, p.getZ(), 12, 0.3, 0.4, 0.3, 0.02);
    }

    /** The shadow the Rogue would hop into: an entity in the look cone wins, otherwise a dark floor spot in it. */
    private static Walk findNode(ServerPlayer p, ServerLevel level, UUID currentHost, long now) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double cos = Math.cos(Math.toRadians(RogueConfig.coneDegrees));
        double range = RogueConfig.walkRange;
        long deadline = now + 40;

        LivingEntity bestEntity = null;
        double bestAngle = 0;
        double bestDist = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range),
                x -> x != p && x.isAlive() && !x.isSpectator() && !x.getUUID().equals(currentHost) && !RogueConfig.meldBlocked(typeId(x)))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > range || dist < 1.0) {
                continue;
            }
            double dot = to.scale(1 / dist).dot(look);
            if (dot < cos || !clear(level, p, eye, e.getBoundingBox().getCenter())) {
                continue;
            }
            double angle = Math.toDegrees(Math.acos(Math.min(1, dot)));
            if (bestEntity == null || angle < bestAngle - 1.5 || (Math.abs(angle - bestAngle) <= 1.5 && dist < bestDist)) {
                bestEntity = e;
                bestAngle = angle;
                bestDist = dist;
            }
        }
        if (bestEntity != null) {
            return new Walk(bestEntity.position(), bestEntity.getUUID(), deadline);
        }

        // Dark spots: a coarse fan of rays; each ray looks for floor with low light along and at its end.
        Vec3 best = null;
        double spotAngle = 0;
        double spotDist = 0;
        float baseYaw = p.getYRot();
        float basePitch = p.getXRot();
        double cone = RogueConfig.coneDegrees;
        for (int yi = -2; yi <= 2; yi++) {
            for (int pi = 0; pi <= 2; pi++) {
                float yaw = (float) (baseYaw + yi * cone / 2);
                float pitch = Math.min(85f, basePitch + pi * 20f);
                Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
                Vec3 end = eye.add(dir.scale(range));
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                double maxT = hit.getType() == HitResult.Type.MISS ? range : Math.max(0, eye.distanceTo(hit.getLocation()) - 0.3);
                for (double t = 3; t <= maxT + 0.01; t += 2) {
                    Vec3 spot = darkFloor(p, level, eye.add(dir.scale(Math.min(t, maxT))));
                    if (spot == null) {
                        continue;
                    }
                    Vec3 to = spot.add(0, 1, 0).subtract(eye);
                    double dist = to.length();
                    if (dist < 3 || dist > range) {
                        continue;
                    }
                    double dot = to.scale(1 / dist).dot(look);
                    if (dot < cos) {
                        continue;
                    }
                    double angle = Math.toDegrees(Math.acos(Math.min(1, dot)));
                    if (best == null || angle < spotAngle - 1.5 || (Math.abs(angle - spotAngle) <= 1.5 && dist < spotDist)) {
                        best = spot;
                        spotAngle = angle;
                        spotDist = dist;
                    }
                }
            }
        }
        return best == null ? null : new Walk(best, null, deadline);
    }

    /** A dark, standable floor position at or just below the sample point, or null. */
    private static Vec3 darkFloor(ServerPlayer p, ServerLevel level, Vec3 sample) {
        BlockPos feet = BlockPos.containing(sample);
        for (int dy = 0; dy < 5; dy++) {
            BlockPos below = feet.below(dy + 1);
            BlockPos at = feet.below(dy);
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                if (level.getMaxLocalRawBrightness(at) > RogueConfig.darkLightLevel) {
                    return null;
                }
                Vec3 dest = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                AABB box = p.getBoundingBox().move(dest.subtract(p.position()));
                return level.noCollision(p, box) ? dest : null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- basic skills

    public static boolean shadowStep(ServerPlayer player, ServerLevel level, PlayerData data) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        player.setDeltaMovement(flat.x * 1.9, 0.2, flat.z * 1.9);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 1));
        PlayerCombat.grantIFrames(player, 4);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 0.5, player.getZ(), 15, 0.3, 0.4, 0.3, 0.02);
        sound(player, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.4f);
        return true;
    }

    public static boolean throwingKnives(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = targets(p, level, 24, 3, ParticleTypes.CRIT);
        sound(p, level, GateboundSounds.of(SoundEvents.ARROW_SHOOT), 1.6f);
        if (hits.isEmpty()) {
            return miss(p, KNIVES_COST, "No target for your knives.");
        }
        double damage = 3 + 0.25 * data.get(Stat.AGILITY);
        for (LivingEntity e : hits) {
            hit(p, level, e, damage, false, false);
            mark(p, e, 80);
        }
        return true;
    }

    public static boolean vanish(ServerPlayer p, ServerLevel level, PlayerData data) {
        int ticks = (6 + data.level / 10) * 20;
        enterShadow(p, level, ticks);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 25, 0.4, 0.6, 0.4, 0.02);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 0.8f);
        Compat.overlay(p, Component.literal("Shadow: your next strike is an Ambush.").withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    public static boolean markForDeath(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = targets(p, level, 30, 1, ParticleTypes.SQUID_INK);
        if (hits.isEmpty()) {
            return miss(p, MARK_COST, "Look at an enemy to mark it.");
        }
        LivingEntity t = hits.get(0);
        mark(p, t, RogueConfig.markSeconds * 20);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, t.getX(), t.getY(0.5), t.getZ(), 20, 0.4, 0.6, 0.4, 0.04);
        sound(p, level, GateboundSounds.of(SoundEvents.WITHER_SPAWN), 1.8f);
        Compat.overlay(p, Component.literal("Marked " + t.getName().getString() + " for " + RogueConfig.markSeconds + " s: +"
                + Math.round(RogueConfig.markBonus * 100) + "% damage from you.").withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    public static boolean venomBlade(ServerPlayer p, ServerLevel level, PlayerData data) {
        VENOM.put(p.getUUID(), now(p) + 400);
        level.sendParticles(ParticleTypes.ITEM_SLIME, p.getX(), p.getY() + 1, p.getZ(), 15, 0.4, 0.5, 0.4, 0.05);
        sound(p, level, GateboundSounds.of(SoundEvents.BREWING_STAND_BREW), 1.2f);
        Compat.overlay(p, Component.literal("Venom Blade: your hits poison for 20 s (wither on marked foes).").withStyle(ChatFormatting.GREEN));
        return true;
    }

    /** Position just behind the victim where the Rogue can stand, or null. */
    private static Vec3 behindSpot(ServerPlayer p, ServerLevel level, LivingEntity t) {
        Vec3 face = flatLook(t);
        for (double[] option : new double[][]{{-1.3, 0}, {1.3, 0}, {0, 1.3}, {0, -1.3}}) {
            Vec3 side = new Vec3(-face.z, 0, face.x);
            Vec3 dest = t.position().add(face.scale(option[0])).add(side.scale(option[1]));
            AABB box = p.getBoundingBox().move(dest.subtract(p.position()));
            if (level.noCollision(p, box)) {
                return dest;
            }
        }
        return null;
    }

    private static boolean jumpBehind(ServerPlayer p, ServerLevel level, LivingEntity t) {
        Vec3 dest = behindSpot(p, level, t);
        if (dest == null) {
            return false;
        }
        level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 15, 0.3, 0.6, 0.3, 0.2);
        Vec3 face = t.position().subtract(dest);
        float yaw = (float) (Math.toDegrees(Math.atan2(-face.x, face.z)));
        p.teleportTo(level, dest.x, dest.y, dest.z, Set.of(), yaw, 0f, false);
        level.sendParticles(ParticleTypes.SQUID_INK, dest.x, dest.y + 1, dest.z, 10, 0.3, 0.6, 0.3, 0.05);
        return true;
    }

    public static boolean backstab(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = targets(p, level, 24, 1, ParticleTypes.SQUID_INK);
        if (hits.isEmpty()) {
            return miss(p, BACKSTAB_COST, "Look at an enemy to backstab it.");
        }
        LivingEntity t = hits.get(0);
        if (!jumpBehind(p, level, t)) {
            return miss(p, BACKSTAB_COST, "No room to get behind it.");
        }
        hit(p, level, t, 6 + 0.6 * data.get(Stat.AGILITY) + 0.05 * data.level, true, false);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY(0.5), t.getZ(), 1, 0, 0, 0, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 1.1f);
        return true;
    }

    public static boolean smokeBomb(ServerPlayer p, ServerLevel level, PlayerData data) {
        for (LivingEntity e : enemies(p, level, 6)) {
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0));
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
            if (e instanceof Mob m) {
                m.setTarget(null);
            }
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.8, p.getZ(), 120, 3, 0.8, 3, 0.03);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX(), p.getY() + 0.5, p.getZ(), 30, 2.5, 0.3, 2.5, 0.02);
        sound(p, level, GateboundSounds.of(SoundEvents.FIRECHARGE_USE), 0.6f);
        enterShadow(p, level, 60);
        return true;
    }

    public static boolean fanOfKnives(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> around = enemies(p, level, 6);
        if (around.isEmpty()) {
            return miss(p, FAN_COST, "No enemies within reach.");
        }
        double damage = 4 + 0.4 * data.get(Stat.AGILITY);
        for (LivingEntity e : around) {
            hit(p, level, e, damage, false, false);
            mark(p, e, 100);
            EchoTrail.line(level, p.position().add(0, 1, 0), e.getEyePosition());
        }
        level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1, p.getZ(), 40, 2.5, 0.4, 2.5, 0.3);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 1.4f);
        return true;
    }

    /** Marked enemies first, then the nearest. */
    private static void sortForStrikes(List<LivingEntity> list, ServerPlayer p, long now) {
        list.sort(Comparator.<LivingEntity, Integer>comparing(e -> {
            MarkInfo m = MARKS.get(e.getUUID());
            return m != null && m.owner().equals(p.getUUID()) && m.until() > now ? 0 : 1;
        }).thenComparingDouble(e -> e.distanceToSqr(p)));
    }

    public static boolean bladeDance(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> around = enemies(p, level, 12);
        if (around.isEmpty()) {
            return miss(p, DANCE_BLADES_COST, "No enemies close enough to dance through.");
        }
        long now = now(p);
        sortForStrikes(around, p, now);
        Vec3 origin = p.position();
        UUID owner = p.getUUID();
        double damage = 5 + 0.5 * data.get(Stat.AGILITY) + 0.05 * data.level;
        for (int i = 0; i < 5; i++) {
            final UUID victim = around.get(i % around.size()).getUUID();
            schedule(now + 1 + i * 3L, server -> {
                ServerPlayer sp = playerOf(server, owner);
                if (sp == null || !sp.isAlive() || !(sp.level() instanceof ServerLevel l) || !(l.getEntity(victim) instanceof LivingEntity t) || !t.isAlive()) {
                    return;
                }
                if (jumpBehind(sp, l, t)) {
                    hit(sp, l, t, damage, true, false);
                    l.sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY(0.5), t.getZ(), 1, 0, 0, 0, 0);
                    sound(sp, l, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 1.5f);
                }
            });
        }
        schedule(now + 1 + 5 * 3L, server -> {
            ServerPlayer sp = playerOf(server, owner);
            if (sp != null && sp.isAlive() && sp.level() instanceof ServerLevel l) {
                l.sendParticles(ParticleTypes.PORTAL, sp.getX(), sp.getY() + 1, sp.getZ(), 15, 0.3, 0.6, 0.3, 0.2);
                moveTo(sp, l, origin);
                sound(sp, l, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.2f);
            }
        });
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.5f);
        return true;
    }

    public static boolean execute(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> hits = targets(p, level, 30, 1, ParticleTypes.SQUID_INK);
        if (hits.isEmpty()) {
            return miss(p, EXECUTE_COST, "Look at an enemy to execute it.");
        }
        LivingEntity t = hits.get(0);
        double missing = 1 - (t.getHealth() + t.getAbsorptionAmount()) / t.getMaxHealth();
        boolean kill = !isBoss(t) && t.getHealth() < t.getMaxHealth() * 0.25f;
        double damage = kill ? 100000 : (8 + 0.8 * data.get(Stat.AGILITY)) * (1 + 2 * Math.max(0, missing));
        hit(p, level, t, damage, false, false);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY(0.5), t.getZ(), 1, 0, 0, 0, 0);
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_STRONG), 0.7f);
        if (t.isDeadOrDying()) {
            ManaState.add(p, 20);
            MARKS.remove(t.getUUID());
            // The mark jumps to the nearest enemy that is still standing.
            LivingEntity next = null;
            double best = 100;
            for (LivingEntity e : enemies(p, level, 10)) {
                double d = e.distanceToSqr(t);
                if (e != t && !e.isDeadOrDying() && d < best) {
                    best = d;
                    next = e;
                }
            }
            if (next != null) {
                mark(p, next, RogueConfig.markSeconds * 20);
            }
            Compat.overlay(p, Component.literal("EXECUTED! +20 mana").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        }
        return true;
    }

    public static boolean shadowDance(ServerPlayer p, ServerLevel level, PlayerData data) {
        long now = now(p);
        DANCE.put(p.getUUID(), now + 200);
        enterShadow(p, level, 200);
        p.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 2));
        level.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.8, 0.5, 0.06);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 0.5f);
        Compat.overlay(p, Component.literal("Shadow Dance: every hit is an Ambush for 10 s.").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        return true;
    }

    public static boolean thousandCuts(ServerPlayer p, ServerLevel level, PlayerData data) {
        List<LivingEntity> around = enemies(p, level, 16);
        if (around.isEmpty()) {
            return miss(p, CUTS_COST, "No enemies within 16 blocks.");
        }
        long now = now(p);
        if (around.size() > 24) {
            sortForStrikes(around, p, now);
            around = new ArrayList<>(around.subList(0, 24));
        }
        for (LivingEntity e : around) {
            mark(p, e, 120);
        }
        int n = around.size();
        int gap = Math.max(1, 40 / n);
        UUID owner = p.getUUID();
        double damage = 5 + 0.5 * data.get(Stat.AGILITY) + 0.05 * data.level;
        for (int i = 0; i < n; i++) {
            final UUID victim = around.get(i).getUUID();
            schedule(now + 2 + (long) i * gap, server -> {
                ServerPlayer sp = playerOf(server, owner);
                if (sp == null || !sp.isAlive() || !(sp.level() instanceof ServerLevel l) || !(l.getEntity(victim) instanceof LivingEntity t) || !t.isAlive()) {
                    return;
                }
                hit(sp, l, t, damage, false, false);
                EchoTrail.line(l, sp.position().add(0, 1, 0), t.getEyePosition());
                l.sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY(0.5), t.getZ(), 1, 0, 0, 0, 0);
                sound(sp, l, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 1.2f + (float) (sp.getRandom().nextDouble() * 0.6));
            });
        }
        sound(p, level, GateboundSounds.of(SoundEvents.WITHER_SHOOT), 1.6f);
        Compat.overlay(p, Component.literal("Thousand Cuts: " + n + " enemies marked.").withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    // ---------------------------------------------------------------- shadow possession

    private static void bind(ServerPlayer p, ServerLevel level, Poss ps, Mob mob, long now) {
        if (BOUND_ALL.contains(mob.getUUID())) {
            return;
        }
        boolean boss = RogueConfig.possessionBlocked(typeId(mob));
        if (boss) {
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 3));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1));
            ps.bosses.add(mob.getUUID());
        } else {
            if (!mob.isNoAi()) {
                mob.setNoAi(true);
                mob.addTag(FROZEN_TAG);
            }
            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 254));
        }
        ps.bound.add(mob.getUUID());
        ps.boundAt.put(mob.getUUID(), now);
        BOUND_ALL.add(mob.getUUID());
        level.sendParticles(ParticleTypes.SQUID_INK, mob.getX(), mob.getY() + 0.2, mob.getZ(), 15, 0.4, 0.1, 0.4, 0.02);
    }

    private static void release(MinecraftServer server, UUID id) {
        BOUND_ALL.remove(id);
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(id) instanceof Mob mob) {
                if (mob.entityTags().contains(FROZEN_TAG)) {
                    mob.setNoAi(false);
                    mob.removeTag(FROZEN_TAG);
                }
                MobEffectInstance slow = mob.getEffect(MobEffects.SLOWNESS);
                if (slow != null && (slow.getAmplifier() == 254 || slow.getAmplifier() == 3)) {
                    mob.removeEffect(MobEffects.SLOWNESS);
                }
                return;
            }
        }
    }

    private static void endPossession(MinecraftServer server, UUID owner, boolean announce) {
        Poss ps = POSSESSION.remove(owner);
        if (ps == null) {
            return;
        }
        for (UUID id : new ArrayList<>(ps.bound)) {
            release(server, id);
        }
        ps.bound.clear();
        ServerPlayer p = server == null ? null : playerOf(server, owner);
        if (announce && p != null) {
            Compat.overlay(p, Component.literal("The shadows let go.").withStyle(ChatFormatting.GRAY));
            sound(p, p.level(), GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 0.5f);
        }
    }

    private static double attackPower(ServerPlayer p, PlayerData data) {
        double base = Math.max(p.getAttributeValue(Attributes.ATTACK_DAMAGE), 4 + 0.25 * data.get(Stat.AGILITY));
        return base * (1 + 0.01 * data.level);
    }

    public static boolean shadowPossession(ServerPlayer p, ServerLevel level, PlayerData data) {
        UUID id = p.getUUID();
        long now = now(p);
        Poss active = POSSESSION.get(id);
        if (active != null) {
            // Casting again is free: pull the bound enemies in, or with sneak jump to the nearest one.
            ManaState.add(p, POSSESSION_COST);
            active.sneakTicks = 0;
            if (p.isShiftKeyDown()) {
                return hopToBound(p, level, active, data);
            }
            return pullBound(p, level, active, now);
        }
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double range = RogueConfig.possessionRange;
        double cos = Math.cos(Math.toRadians(60));
        List<Mob> candidates = new ArrayList<>();
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(range), x -> x instanceof Enemy && validTarget(x, p))) {
            Vec3 to = m.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            MarkInfo mark = MARKS.get(m.getUUID());
            boolean marked = mark != null && mark.owner().equals(id) && mark.until() > now;
            if (dist > range || BOUND_ALL.contains(m.getUUID())) {
                continue;
            }
            if (marked || dist < 8 || to.scale(1 / dist).dot(look) >= cos) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return miss(p, POSSESSION_COST, "No enemies for your shadow to reach.");
        }
        candidates.sort(Comparator.<Mob, Integer>comparing(m -> {
            MarkInfo mk = MARKS.get(m.getUUID());
            return mk != null && mk.owner().equals(id) && mk.until() > now ? 0 : 1;
        }).thenComparingDouble(m -> {
            Vec3 to = m.getBoundingBox().getCenter().subtract(eye);
            return -to.normalize().dot(look);
        }));
        int seconds = 10 + data.level / 20;
        Poss ps = new Poss(now, now + seconds * 20L);
        POSSESSION.put(id, ps);
        int n = 0;
        for (Mob m : candidates) {
            if (n >= RogueConfig.possessionMaxBound) {
                break;
            }
            bind(p, level, ps, m, now);
            n++;
        }
        sound(p, level, GateboundSounds.of(SoundEvents.WARDEN_SONIC_CHARGE), 1.4f);
        Compat.overlay(p, Component.literal("Shadow Possession: " + n + " bound for " + seconds + " s. Cast again to pull, sneak-cast to leap, hold sneak to end.")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        return true;
    }

    private static List<Mob> boundMobs(ServerLevel level, Poss ps) {
        List<Mob> out = new ArrayList<>();
        for (UUID id : ps.bound) {
            if (level.getEntity(id) instanceof Mob m && m.isAlive()) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean pullBound(ServerPlayer p, ServerLevel level, Poss ps, long now) {
        int n = 0;
        for (Mob m : boundMobs(level, ps)) {
            if (!ps.bosses.contains(m.getUUID())) {
                ps.pulling.put(m.getUUID(), now + 30);
                n++;
            }
        }
        sound(p, level, GateboundSounds.of(SoundEvents.CHAIN_PLACE), 0.6f);
        if (n == 0) {
            Compat.overlay(p, Component.literal("Nothing bound that can be pulled.").withStyle(ChatFormatting.GRAY));
        }
        return true;
    }

    private static boolean hopToBound(ServerPlayer p, ServerLevel level, Poss ps, PlayerData data) {
        Mob nearest = null;
        double best = Double.MAX_VALUE;
        for (Mob m : boundMobs(level, ps)) {
            double d = m.distanceToSqr(p);
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        if (nearest == null || !jumpBehind(p, level, nearest)) {
            Compat.overlay(p, Component.literal("No bound enemy to leap to.").withStyle(ChatFormatting.GRAY));
            return true;
        }
        hit(p, level, nearest, attackPower(p, data), true, true);
        sound(p, level, GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), 1.5f);
        return true;
    }

    /** A melee hit on a bound enemy: possessed ones are thrown, the rest make every arm strike at once. */
    private static void onBoundHit(ServerPlayer p, ServerLevel level, Poss ps, Mob mob) {
        PlayerData data = PlayerDataStore.of(p);
        if (ps.possessed.contains(mob.getUUID())) {
            LivingEntity victim = null;
            double best = 12 * 12;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(12),
                    x -> x != mob && x instanceof Enemy && validTarget(x, p))) {
                double d = e.distanceToSqr(mob);
                if (d < best) {
                    best = d;
                    victim = e;
                }
            }
            Vec3 dir = victim != null ? victim.position().subtract(mob.position()).normalize() : p.getLookAngle();
            UUID id = mob.getUUID();
            forget(ps, id);
            release(level.getServer(), id);
            mob.setDeltaMovement(dir.x * 1.5, 0.45, dir.z * 1.5);
            mob.needsSync = true;
            ps.thrown.put(id, now(p) + 25);
            EchoTrail.line(level, p.position().add(0, 0.1, 0), mob.position());
            sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_KNOCKBACK), 0.7f);
            return;
        }
        armStrike(p, level, ps, data);
    }

    private static void armStrike(ServerPlayer p, ServerLevel level, Poss ps, PlayerData data) {
        List<Mob> targets = new ArrayList<>();
        for (Mob m : boundMobs(level, ps)) {
            if (m.distanceToSqr(p) <= RogueConfig.possessionRange * RogueConfig.possessionRange) {
                targets.add(m);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        float damage = (float) attackPower(p, data);
        for (int i = 0; i < RogueConfig.possessionArms; i++) {
            Mob t = targets.get(Math.floorMod(ps.armCursor++, targets.size()));
            EchoTrail.line(level, p.position().add(0, 0.1, 0), t.position().add(0, 0.5, 0));
            rawHurt(p, level, t, damage);
            t.setDeltaMovement(Vec3.ZERO);
            level.sendParticles(ParticleTypes.CRIT, t.getX(), t.getY(0.5), t.getZ(), 4, 0.3, 0.3, 0.3, 0.1);
        }
        sound(p, level, GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), 0.7f);
    }

    private static void tickPossession(MinecraftServer server, ServerPlayer p, ServerLevel level, long now) {
        UUID id = p.getUUID();
        Poss ps = POSSESSION.get(id);
        if (ps.until <= now) {
            endPossession(server, id, true);
            return;
        }
        ps.sneakTicks = p.isShiftKeyDown() ? ps.sneakTicks + 1 : 0;
        if (ps.sneakTicks >= 20) {
            endPossession(server, id, true);
            return;
        }
        PlayerData data = PlayerDataStore.of(p);
        for (UUID bid : new ArrayList<>(ps.bound)) {
            Entity e = level.getEntity(bid);
            if (!(e instanceof Mob m) || !m.isAlive()) {
                forget(ps, bid);
                release(server, bid);
                continue;
            }
            boolean boss = ps.bosses.contains(bid);
            if (!boss && !ps.pulling.containsKey(bid)) {
                m.setDeltaMovement(Vec3.ZERO);
            }
            if (now % 20 == 0) {
                m.addEffect(boss ? new MobEffectInstance(MobEffects.SLOWNESS, 60, 3) : new MobEffectInstance(MobEffects.SLOWNESS, 60, 254));
                if (boss) {
                    m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1));
                }
            }
            if (now % 4 == 0) {
                // The shadow reaches along the ground from the Rogue's feet.
                Vec3 from = new Vec3(p.getX(), p.getY() + 0.05, p.getZ());
                Vec3 to = new Vec3(m.getX(), p.getY() + 0.05, m.getZ());
                int steps = (int) Math.min(24, Math.max(2, from.distanceTo(to) / 0.8));
                for (int i = 1; i <= steps; i++) {
                    Vec3 pt = from.add(to.subtract(from).scale(i / (double) steps));
                    level.sendParticles(ParticleTypes.SQUID_INK, pt.x, pt.y, pt.z, 1, 0.05, 0, 0.05, 0);
                }
            }
            Long since = ps.boundAt.get(bid);
            if (!boss && since != null && now - since >= 40 && ps.possessed.add(bid)) {
                m.addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) Math.max(20, ps.until - now), 0, false, false));
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, m.getX(), m.getY(0.5), m.getZ(), 20, 0.3, 0.6, 0.3, 0.04);
                sound(p, level, GateboundSounds.of(SoundEvents.WITHER_AMBIENT), 1.8f);
            }
            if (ps.possessed.contains(bid) && now % 20 == 10) {
                possessedStrike(p, level, m, data);
            }
        }
        for (Map.Entry<UUID, Long> pull : new ArrayList<>(ps.pulling.entrySet())) {
            if (!(level.getEntity(pull.getKey()) instanceof Mob m) || !m.isAlive() || pull.getValue() <= now) {
                ps.pulling.remove(pull.getKey());
                continue;
            }
            Vec3 to = p.position().subtract(m.position());
            double dist = to.length();
            if (dist <= 2.3) {
                ps.pulling.remove(pull.getKey());
                m.setDeltaMovement(Vec3.ZERO);
                hit(p, level, m, attackPower(p, data), false, true);
                continue;
            }
            Vec3 next = m.position().add(to.scale(Math.min(1.6, dist - 2.0) / dist));
            if (!clear(level, m, m.position().add(0, 0.5, 0), next.add(0, 0.5, 0))) {
                ps.pulling.remove(pull.getKey());
                continue;
            }
            moveTo(m, level, next);
            m.setDeltaMovement(Vec3.ZERO);
            level.sendParticles(ParticleTypes.SQUID_INK, m.getX(), m.getY(0.5), m.getZ(), 3, 0.2, 0.3, 0.2, 0.01);
        }
        for (Map.Entry<UUID, Long> fly : new ArrayList<>(ps.thrown.entrySet())) {
            if (!(level.getEntity(fly.getKey()) instanceof Mob m) || !m.isAlive() || fly.getValue() <= now) {
                ps.thrown.remove(fly.getKey());
                continue;
            }
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, m.getBoundingBox().inflate(0.5),
                    x -> x != m && x instanceof Enemy && validTarget(x, p))) {
                float dmg = (float) (6 + 0.5 * data.get(Stat.AGILITY) + 0.1 * data.level);
                rawHurt(p, level, other, dmg);
                rawHurt(p, level, m, dmg * 0.5f);
                level.sendParticles(ParticleTypes.EXPLOSION, other.getX(), other.getY(0.5), other.getZ(), 1, 0, 0, 0, 0);
                sound(p, level, GateboundSounds.of(SoundEvents.GENERIC_EXPLODE.value()), 1.4f);
                ps.thrown.remove(fly.getKey());
                break;
            }
        }
        if (now % Math.max(1, RogueConfig.armIntervalTicks) == 0) {
            armStrike(p, level, ps, data);
        }
    }

    /** A possessed enemy swings at its nearest neighbour. */
    private static void possessedStrike(ServerPlayer p, ServerLevel level, Mob puppet, PlayerData data) {
        LivingEntity victim = null;
        double best = 3.5 * 3.5;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, puppet.getBoundingBox().inflate(3.5),
                x -> x != puppet && x instanceof Enemy && !BOUND_ALL.contains(x.getUUID()) && validTarget(x, p))) {
            double d = e.distanceToSqr(puppet);
            if (d < best) {
                best = d;
                victim = e;
            }
        }
        if (victim == null) {
            return;
        }
        double base = puppet.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? puppet.getAttributeValue(Attributes.ATTACK_DAMAGE) : 2;
        rawHurt(p, level, victim, (float) (Math.max(2, base) * (1 + 0.01 * data.level)));
        Compat.swing(puppet, net.minecraft.world.InteractionHand.MAIN_HAND);
        EchoTrail.line(level, puppet.getEyePosition(), victim.getEyePosition());
    }

    /** Small particle-line helper (SQUID_INK / SMOKE) used by shadow strikes. */
    private static final class EchoTrail {
        static void line(ServerLevel level, Vec3 from, Vec3 to) {
            Vec3 d = to.subtract(from);
            int steps = (int) Math.min(40, Math.max(1, d.length() / 0.6));
            for (int i = 0; i <= steps; i++) {
                Vec3 pt = from.add(d.scale(i / (double) steps));
                level.sendParticles(i % 2 == 0 ? ParticleTypes.SQUID_INK : ParticleTypes.SMOKE, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
            }
        }
    }
}
