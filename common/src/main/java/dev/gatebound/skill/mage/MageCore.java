package dev.gatebound.skill.mage;

import dev.gatebound.compat.MobTypes;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.combat.PlayerCombat;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import dev.gatebound.data.Element;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.item.KnifeItems;
import dev.gatebound.item.StaffItems;
import net.minecraft.ChatFormatting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.Stat;
import dev.gatebound.echo.EchoManager;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared machinery for the Mage elements: damage, projectiles that fly tick by tick, lingering zones, timed buffs,
 * the level 100 forms and temporary blocks. All state is in memory only and cleared on quit, death and server stop.
 */
public final class MageCore {
    /** Something that runs every server tick until it returns true. */
    @FunctionalInterface
    public interface Ticker extends dev.gatebound.skill.fx.Tasks.Ticker {
    }

    /** Called when a bolt ends: target is null when it hit a block or ran out of range. */
    @FunctionalInterface
    public interface Impact {
        void hit(ServerLevel level, Vec3 pos, LivingEntity target);
    }

    /** An active elemental form and when it ends. */
    private record Form(Element element, long until) {
    }

    /** A temporary block change and when to restore it. */
    private record Temp(ServerLevel level, BlockPos pos, BlockState original, Block placed, long restoreAt) {
    }

    private static final Map<UUID, Form> FORMS = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> BUFFS = new HashMap<>();
    private static final List<Temp> TEMPS = new ArrayList<>();

    /** Guards our own damage from re-entering hurt hooks. */
    private static boolean applying;

    /** Static holder; not instantiable. */
    private MageCore() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Registers the mage tick and cleanup hooks. */
    public static void init() {
        TickEvent.SERVER_POST.register(MageCore::tick);
        dev.gatebound.skill.fx.Tasks.init();
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            // A Resurrection blessing is about to cancel this death, so keep the player's state.
            if (entity instanceof ServerPlayer player && !buffed(player, "resurrection")) {
                clearPlayer(player.getUUID());
            }
            return EventResult.pass();
        });
        PlayerEvent.PLAYER_QUIT.register(player -> clearPlayer(player.getUUID()));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> clearPlayer(player.getUUID()));
        LifecycleEvent.SERVER_STOPPING.register(server -> restoreAll());
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            FORMS.clear();
            BUFFS.clear();
            TEMPS.clear();
        });
        EntityEvent.LIVING_HURT.register(MageCore::onHurt);
        dev.gatebound.skill.fx.Props.init();
        dev.gatebound.skill.fx.FormFx.init();
        dev.gatebound.skill.fx.FormSync.init();
        FireSkills.init();
        IceSkills.init();
        LightningSkills.init();
        WindSkills.init();
        EarthSkills.init();
        LightSkills.init();
        DarkSkills.init();
    }

    /** A Mage fights with a staff (or bare hands): any other melee weapon, axes included, can be held but does no damage. */
    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (applying || !(victim instanceof Mob mob) || EchoManager.isEcho(mob) || !CombatHandler.isMelee(source)
                || !(source.getEntity() instanceof ServerPlayer p)
                || !GateboundClass.MAGE.id().equals(PlayerDataStore.of(p).classId)) {
            return EventResult.pass();
        }
        ItemStack held = p.getMainHandItem();
        if (KnifeItems.hasAttackDamage(held) && !StaffItems.isStaff(held)) {
            long now = p.level().getServer().getTickCount();
            if (now % 20 == 0) {
                Compat.overlay(p, Component.literal("A Mage only fights with a staff.").withStyle(ChatFormatting.GRAY));
            }
            return EventResult.interruptFalse();
        }
        return EventResult.pass();
    }

    /** Drops all of a player's mage state. */
    private static void clearPlayer(UUID id) {
        FORMS.remove(id);
        BUFFS.remove(id);
    }

    /**
     * Casting strains the body. Each cast spends stamina; a cast with none left backlashes for damage (never lethal).
     * Called by SkillHandler for every Mage cast, after the mana is paid.
     */
    public static void strain(ServerPlayer p) {
        double max = PlayerCombat.maxStamina(p);
        boolean spent = PlayerCombat.stamina(p) < 1.0;
        PlayerCombat.spendStamina(p, max * MageConfig.strainCost);
        if (spent) {
            float hurt = (float) Math.min(Math.max(2.0, p.getMaxHealth() * MageConfig.backlashFraction), p.getHealth() - 1.0f);
            if (hurt > 0) {
                p.hurtServer(p.level(), p.level().damageSources().magic(), hurt);
                Compat.overlay(p, Component.literal("Your body rebels against the strain!").withStyle(ChatFormatting.RED));
            }
        }
    }

    /** Slows a Mage whose stamina is low. */
    private static void tickStrain(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive() || !GateboundClass.MAGE.id().equals(PlayerDataStore.of(p).classId)) {
                continue;
            }
            double ratio = PlayerCombat.stamina(p) / Math.max(1, PlayerCombat.maxStamina(p));
            if (ratio < MageConfig.slowBelow) {
                int amp = ratio < MageConfig.heavySlowBelow ? 1 : 0;
                p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, amp, false, false, true));
            }
        }
    }

    /** Per-tick driver: runs tickers, delayed actions and block restores. */
    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 10 == 0) {
            tickStrain(server);
        }
        if (!TEMPS.isEmpty()) {
            for (Temp t : new ArrayList<>(TEMPS)) {
                if (t.restoreAt() <= now) {
                    restore(t);
                    TEMPS.remove(t);
                }
            }
        }
    }

    // ---------------------------------------------------------------- damage and targets

    /** Spell damage scaled by the player's level and stats. */
    public static float dmg(PlayerData data, double base) {
        return (float) ((base + MageConfig.damagePerIntellect * data.get(Stat.INTELLECT)) * (1 + MageConfig.damagePerLevel * data.level));
    }

    /** True while a spell is dealing damage (guards re-entrant hurt hooks). */
    public static boolean applying() {
        return applying;
    }

    /** Deals spell damage to a target on behalf of the player. */
    public static void strike(ServerPlayer p, ServerLevel level, LivingEntity target, float amount) {
        boolean was = applying;
        applying = true;
        try {
            Compat.setInvulnerableTime(target, 0);
            target.hurtServer(level, level.damageSources().indirectMagic(p, p), amount);
        } finally {
            applying = was;
        }
    }

    /** True if the entity is a valid spell target for the owner. */
    public static boolean valid(ServerPlayer owner, LivingEntity e) {
        return e != owner && e.isAlive() && !(e instanceof Player) && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) &&!(e instanceof Mob mob && EchoManager.isEcho(mob));
    }

    /** Things that are not alive but can be destroyed by an attack: end crystals and missiles (fireballs) not fired by a player. */
    public static boolean breakable(ServerPlayer owner, net.minecraft.world.entity.Entity e) {
        if (!e.isAlive() || e.isRemoved()) {
            return false;
        }
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
            return true;
        }
        return e instanceof net.minecraft.world.entity.projectile.Projectile pr && pr.getOwner() != owner && !(pr.getOwner() instanceof Player)
                && pr.isPickable();
    }

    /** Hurts a breakable object (an end crystal blows up, a fireball is swatted away). */
    public static void smash(ServerPlayer owner, ServerLevel level, net.minecraft.world.entity.Entity object) {
        boolean was = applying;
        applying = true;
        try {
            object.hurtServer(level, level.damageSources().indirectMagic(owner, owner), 10.0f);
        } finally {
            applying = was;
        }
    }

    /** Magic that sweeps an area also breaks end crystals and swats hostile fireballs inside it. */
    private static void breakAround(ServerPlayer owner, ServerLevel level, AABB box, Vec3 at, double radius) {
        for (net.minecraft.world.entity.Entity o : level.getEntities(owner, box, x -> breakable(owner, x))) {
            double dx = o.getX() - at.x;
            double dz = o.getZ() - at.z;
            if (dx * dx + dz * dz <= (radius + 0.6) * (radius + 0.6)) {
                smash(owner, level, o);
            }
        }
    }

    /** Hostile or neutral living things within a radius of a point (players and echoes excluded); also breaks end crystals there. */
    public static List<LivingEntity> foesAt(ServerPlayer owner, ServerLevel level, Vec3 at, double radius) {
        AABB box = new AABB(at, at).inflate(radius, Math.max(2.0, radius * 0.6), radius);
        breakAround(owner, level, box, at, radius);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> valid(owner, x))) {
            double dx = e.getX() - at.x;
            double dz = e.getZ() - at.z;
            if (dx * dx + dz * dz <= radius * radius) {
                out.add(e);
            }
        }
        return out;
    }

    /** Valid spell targets around the player within a radius. */
    public static List<LivingEntity> foes(ServerPlayer p, ServerLevel level, double radius) {
        return foesAt(p, level, p.position(), radius);
    }

    /** Where the player is looking: the first solid block within range, or the end of the line. */
    public static Vec3 aimSpot(ServerPlayer p, ServerLevel level, double range) {
        Vec3 from = p.getEyePosition();
        Vec3 end = from.add(p.getLookAngle().scale(range));
        BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

    /** The player's look direction flattened onto the horizontal plane. */
    public static Vec3 flatLook(ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        return flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** Knocks an entity away from a point. */
    public static void knock(LivingEntity e, Vec3 from, double power, double up) {
        Vec3 away = e.position().subtract(from);
        Vec3 dir = new Vec3(away.x, 0, away.z);
        dir = dir.lengthSqr() < 1.0e-4 ? Vec3.ZERO : dir.normalize();
        e.push(dir.x * power, up, dir.z * power);
        e.needsSync = true;
    }

    /** Syncs the player's skill state to the client. */
    public static void send(ServerPlayer p) {
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
    }

    /** Plays a sound at a position. */
    public static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        dev.gatebound.skill.fx.Tasks.sound(level, at, sound, volume, pitch);
    }

    /** Spawns particles at a position. */
    public static void particles(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread, double speed) {
        dev.gatebound.skill.fx.Tasks.particles(level, type, at, count, spread, speed);
    }

    /** Looks a player up by UUID. */
    public static ServerPlayer playerOf(MinecraftServer server, UUID id) {
        return dev.gatebound.skill.fx.Tasks.playerOf(server, id);
    }

    /** Finds any loaded entity by UUID across levels. */
    public static LivingEntity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof LivingEntity e && e.isAlive()) {
                return e;
            }
        }
        return null;
    }

    /** A lightning strike that only looks and sounds like one (no damage, no fire). */
    public static void visualBolt(ServerLevel level, Vec3 at) {
        net.minecraft.world.entity.LightningBolt bolt = MobTypes.LIGHTNING_BOLT
                .spawn(level, BlockPos.containing(at), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.setVisualOnly(true);
        }
    }

    /** Bright flash of light at a position. */
    public static void flash(ServerLevel level, double x, double y, double z) {
        dev.gatebound.skill.fx.Tasks.flash(level, x, y, z);
    }

    /** A line of particles between two points. */
    public static void line(ServerLevel level, ParticleOptions type, Vec3 from, Vec3 to) {
        dev.gatebound.skill.fx.Tasks.line(level, type, from, to);
    }

    /** Pays health for a spell, never taking the last point. */
    public static void sacrifice(ServerPlayer p, float hp) {
        float pay = Math.min(hp, p.getHealth() - 1.0f);
        if (pay > 0) {
            p.hurtServer(p.level(), p.level().damageSources().magic(), pay);
        }
    }

    /** Carries the player in the direction they look for a while, hovering when no key is held. Fall damage is cancelled. */
    public static void flight(ServerPlayer p, int ticks) {
        UUID id = p.getUUID();
        long[] end = {-1};
        run((server, now) -> {
            ServerPlayer o = playerOf(server, id);
            if (end[0] < 0) {
                end[0] = now + ticks;
            }
            if (o == null || now >= end[0]) {
                return true;
            }
            Compat.Input in = Compat.input(o);
            Vec3 dir = Vec3.ZERO;
            {
                if (in.forward()) {
                    dir = o.getLookAngle().scale(0.9);
                } else if (in.backward()) {
                    dir = o.getLookAngle().scale(-0.4);
                } else if (in.jump()) {
                    dir = new Vec3(0, 0.6, 0);
                } else if (in.shift()) {
                    dir = new Vec3(0, -0.5, 0);
                }
            }
            o.setDeltaMovement(dir);
            o.fallDistance = 0;
            send(o);
            return false;
        });
    }

    // ---------------------------------------------------------------- tickers, bolts, zones

    /** Starts a per-tick effect that runs until it finishes. */
    public static void run(dev.gatebound.skill.fx.Tasks.Ticker ticker) {
        dev.gatebound.skill.fx.Tasks.run(ticker);
    }

    /** Runs an action after some ticks. */
    public static void after(int ticks, Consumer<MinecraftServer> action) {
        dev.gatebound.skill.fx.Tasks.after(ticks, action);
    }

    /** A projectile that flies tick by tick, hits the first enemy or block in its path, and trails particles. */
    public static void bolt(ServerPlayer p, ServerLevel level, Vec3 from, Vec3 dir, double speed, double range, double radius,
                            ParticleOptions trail, Impact impact) {
        bolt(p, level, from, dir, speed, range, radius, trail == null ? null : (lv, a, b, tick) -> {
            int steps = Math.max(1, (int) Math.ceil(speed * 2));
            for (int i = 0; i <= steps; i++) {
                Vec3 q = a.lerp(b, i / (double) steps);
                lv.sendParticles(trail, q.x, q.y, q.z, 1, 0.03, 0.03, 0.03, 0.0);
            }
        }, impact);
    }

    /** Draws a bolt's path for one tick of flight; {@code tick} counts up from the start. */
    @FunctionalInterface
    public interface Trail {
        void draw(ServerLevel level, Vec3 from, Vec3 to, long tick);
    }

    /** A projectile with its own trail drawing (see Fx.trail). */
    public static void bolt(ServerPlayer p, ServerLevel level, Vec3 from, Vec3 dir, double speed, double range, double radius,
                            Trail trail, Impact impact) {
        UUID owner = p.getUUID();
        long[] age = {0};
        Vec3 heading = dir.normalize();
        Vec3[] pos = {from};
        double[] travelled = {0};
        run((server, now) -> {
            ServerPlayer o = playerOf(server, owner);
            if (o == null) {
                return true;
            }
            Vec3 start = pos[0];
            Vec3 want = start.add(heading.scale(speed));
            BlockHitResult block = level.clip(new ClipContext(start, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o));
            Vec3 end = block.getType() == HitResult.Type.MISS ? want : block.getLocation();
            AABB box = new AABB(start, end).inflate(radius);
            LivingEntity hit = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> valid(o, x))) {
                double d = e.distanceToSqr(start);
                if (d < best) {
                    best = d;
                    hit = e;
                }
            }
            if (trail != null) {
                trail.draw(level, start, end, age[0]++);
            }
            if (hit == null) {
                net.minecraft.world.entity.Entity object = null;
                double nearest = Double.MAX_VALUE;
                for (net.minecraft.world.entity.Entity x : level.getEntities(o, box, y -> breakable(o, y))) {
                    double d = x.distanceToSqr(start);
                    if (d < nearest) {
                        nearest = d;
                        object = x;
                    }
                }
                if (object != null) {
                    Vec3 where = object.position().add(0, object.getBbHeight() * 0.5, 0);
                    smash(o, level, object);
                    impact.hit(level, where, null);
                    return true;
                }
            }
            if (hit != null) {
                impact.hit(level, hit.position().add(0, hit.getBbHeight() * 0.5, 0), hit);
                return true;
            }
            if (block.getType() != HitResult.Type.MISS) {
                impact.hit(level, end, null);
                return true;
            }
            pos[0] = end;
            travelled[0] += speed;
            if (travelled[0] >= range) {
                impact.hit(level, end, null);
                return true;
            }
            return false;
        });
    }

    /**
     * A lingering area. Every {@code every} ticks the pulse runs on the foes inside it; {@code visuals} runs every other tick.
     * It also ends if the owner leaves.
     */
    public static void zone(ServerPlayer p, ServerLevel level, Vec3 center, double radius, Predicate<LivingEntity> inside, int ticks,
                            int every, Consumer<List<LivingEntity>> pulse, Consumer<ServerLevel> visuals) {
        UUID owner = p.getUUID();
        long[] start = {-1};
        run((server, now) -> {
            if (start[0] < 0) {
                start[0] = now;
            }
            ServerPlayer o = playerOf(server, owner);
            long age = now - start[0];
            if (o == null || age >= ticks) {
                return true;
            }
            if (age % 2 == 0 && visuals != null) {
                visuals.accept(level);
            }
            if (age % every == 0 && pulse != null) {
                List<LivingEntity> in = new ArrayList<>();
                for (LivingEntity e : foesAt(o, level, center, radius)) {
                    if (inside == null || inside.test(e)) {
                        in.add(e);
                    }
                }
                pulse.accept(in);
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- buffs and forms

    /** Gives the player a named timed buff. */
    public static void buff(ServerPlayer p, String key, int ticks) {
        BUFFS.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(key, (long) p.level().getServer().getTickCount() + ticks);
    }

    /** True if the player has the named buff. */
    public static boolean buffed(ServerPlayer p, String key) {
        Map<String, Long> map = BUFFS.get(p.getUUID());
        if (map == null) {
            return false;
        }
        Long until = map.get(key);
        if (until == null) {
            return false;
        }
        if (until <= p.level().getServer().getTickCount()) {
            map.remove(key);
            return false;
        }
        return true;
    }

    /** Every player currently in a form, with its element (used to tell clients whom to skin). */
    public static Map<UUID, Element> activeForms(long now) {
        Map<UUID, Element> out = new HashMap<>();
        for (Map.Entry<UUID, Form> e : FORMS.entrySet()) {
            if (e.getValue().until() > now) {
                out.put(e.getKey(), e.getValue().element());
            }
        }
        return out;
    }

    /** True if the player is in the given elemental form. */
    public static boolean inForm(ServerPlayer p, Element element) {
        Form f = FORMS.get(p.getUUID());
        return f != null && f.element() == element && f.until() > p.level().getServer().getTickCount();
    }

    /**
     * Starts a 100-level form. {@code aura} runs every {@code every} ticks while it lasts. A second cast refreshes it.
     */
    public static void form(ServerPlayer p, Element element, int every, Consumer<ServerPlayer> aura) {
        UUID id = p.getUUID();
        long until = p.level().getServer().getTickCount() + MageConfig.formSeconds * 20L;
        boolean fresh = !FORMS.containsKey(id);
        FORMS.put(id, new Form(element, until));
        if (!fresh) {
            return;
        }
        long[] start = {-1};
        run((server, now) -> {
            if (start[0] < 0) {
                start[0] = now;
            }
            ServerPlayer o = playerOf(server, id);
            Form f = FORMS.get(id);
            if (o == null || f == null || f.until() <= now) {
                FORMS.remove(id);
                if (o != null) {
                    Compat.overlay(o, Component.literal("Your form fades."));
                }
                return true;
            }
            if ((now - start[0]) % every == 0) {
                aura.accept(o);
            }
            return false;
        });
    }

    /** A cast in a matching form costs nothing: the mana is handed back. */
    public static void freeCast(ServerPlayer p, Element element, int cost) {
        if (inForm(p, element)) {
            ManaState.add(p, cost);
            SkillHandler.sendMana(p, true);
        }
    }

    // ---------------------------------------------------------------- temporary blocks

    /** Places a block that goes back to what it replaced after the given ticks (and when the server stops). */
    public static boolean tempBlock(ServerLevel level, BlockPos pos, BlockState state, int ticks) {
        BlockState original = level.getBlockState(pos);
        if (!original.isAir() && original.getFluidState().isEmpty()) {
            return false;
        }
        level.setBlock(pos, state, 3);
        TEMPS.add(new Temp(level, pos.immutable(), original, state.getBlock(), level.getServer().getTickCount() + ticks));
        return true;
    }

    /** Restores one temporary block to its original state. */
    private static void restore(Temp t) {
        if (t.level().getBlockState(t.pos()).is(t.placed())) {
            t.level().setBlock(t.pos(), t.original(), 3);
        }
    }

    /** Restores every temporary block. */
    private static void restoreAll() {
        for (Temp t : TEMPS) {
            restore(t);
        }
        TEMPS.clear();
    }
}
