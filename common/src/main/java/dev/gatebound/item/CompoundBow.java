package dev.gatebound.item;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The compound bow: a stronger, truer bow. Its arrows fly dead straight (no drop) with no spread and a little extra speed.
 * Grapple: crouch and hold your aim on a spot to set the anchor, shoot (crouch + right-click) to reel in, then hang until jump.
 */
public final class CompoundBow extends BowItem {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    public static final RegistrySupplier<Item> COMPOUND_BOW = ITEMS.register("compound_bow", () -> new CompoundBow(new Item.Properties()
            .arch$tab(GateboundRegistries.MAIN_TAB).durability(1000).enchantable(15).repairable(net.minecraft.world.item.Items.IRON_INGOT)
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "compound_bow")))));

    /** Arrow speed multiplier over a normal bow. */
    public static final float POWER_BONUS = 1.15f;
    public static final double GRAPPLE_RANGE = 48;
    public static final double GRAPPLE_SPEED = 1.3;
    /** In the dungeon dimension a hang lasts this long (seconds) before the line gives way; elsewhere it lasts until the player jumps. */
    public static final int DUNGEON_HANG_SECONDS = 15;
    public static final int GRAPPLE_COOLDOWN_TICKS = 20;

    /** Ticks the crouching player must keep aiming at the same spot before the anchor is set. */
    public static final int AIM_TICKS = 12;
    /** How long a set anchor waits for the shot before it is dropped. */
    public static final int ANCHOR_WAIT_TICKS = 20 * 20;

    /**
     * A grapple line. Stage 1, SET: the anchor is placed where the player held their aim (nothing moves yet).
     * Stage 2, engaged and pulling: the shot reels the player in. Stage 3, engaged and not pulling: they hang until jump.
     */
    private static final class Grapple {
        final Vec3 anchor;
        final ResourceKey<Level> dim;
        long start;
        double rope;
        boolean engaged;
        boolean pulling;
        /** Game time the player started hanging (0 = not hanging yet). */
        long hangStart;

        Grapple(Vec3 anchor, ResourceKey<Level> dim, long start, double rope) {
            this.anchor = anchor;
            this.dim = dim;
            this.start = start;
            this.rope = rope;
        }
    }

    /** What a crouching player is aiming at, and for how long they have held it. */
    private static final class Aim {
        net.minecraft.core.BlockPos block;
        int ticks;
    }

    private static final Map<UUID, Aim> AIMS = new HashMap<>();

    private static final Map<UUID, Grapple> GRAPPLES = new HashMap<>();
    private static final Map<UUID, Long> READY = new HashMap<>();

    /** Only Rangers can use the compound bow: draw it, shoot it or grapple with it. */
    private static boolean usable(Player player) {
        String id = player instanceof ServerPlayer sp
                ? dev.gatebound.data.PlayerDataStore.of(sp).classId
                : dev.gatebound.data.ClientPlayerData.classId;
        return dev.gatebound.data.GateboundClass.RANGER.id().equals(id);
    }

    private CompoundBow(Properties properties) {
        super(properties);
    }

    public static void init() {
        ITEMS.register();
        TickEvent.SERVER_POST.register(CompoundBow::tick);
        TickEvent.SERVER_POST.register(CompoundBow::tickFlights);
        PlayerEvent.PLAYER_QUIT.register(player -> {
            GRAPPLES.remove(player.getUUID());
            READY.remove(player.getUUID());
            hang(player, false);
        });
        // A crash while hanging would leave the saved player floating: undo it on the next join.
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player.entityTags().contains(HANG_TAG) && !GRAPPLES.containsKey(player.getUUID())) {
                hang(player, false);
            }
        });
    }

    /** A compound arrow in flight: it keeps its launch speed (air drag would make a gravity-free arrow crawl and hang), then falls after a while. */
    private record Flight(ResourceKey<Level> dim, long born, double speed) {
    }

    private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();
    /** Ticks an arrow flies straight before gravity returns (about 120 blocks at full draw). */
    public static final int STRAIGHT_TICKS = 40;

    @Override
    protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float power, float uncertainty, float angle, LivingEntity target) {
        super.shootProjectile(shooter, projectile, index, power * POWER_BONUS, 0.0f, angle, target);
        projectile.setNoGravity(true);
        if (shooter.level() instanceof ServerLevel level) {
            FLIGHTS.put(projectile.getUUID(), new Flight(level.dimension(), level.getServer().getTickCount(), projectile.getDeltaMovement().length()));
        }
    }

    private static void tickFlights(net.minecraft.server.MinecraftServer server) {
        if (FLIGHTS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        for (UUID id : new java.util.ArrayList<>(FLIGHTS.keySet())) {
            Flight f = FLIGHTS.get(id);
            ServerLevel level = server.getLevel(f.dim());
            net.minecraft.world.entity.Entity e = level == null ? null : level.getEntity(id);
            if (e == null || !e.isAlive()) {
                FLIGHTS.remove(id);
                continue;
            }
            Vec3 v = e.getDeltaMovement();
            if (now - f.born() >= STRAIGHT_TICKS) {
                e.setNoGravity(false); // out of range: drop like any arrow
                FLIGHTS.remove(id);
            } else if (v.lengthSqr() > 0.25) {
                e.setDeltaMovement(v.normalize().scale(f.speed()));
            } else {
                e.setNoGravity(false); // stuck or spent: never leave it hanging
                FLIGHTS.remove(id);
            }
        }
    }

    /**
     * Crouch + right-click with an anchor set: the shot, which reels you in to hang there.
     * Jump lets go. A plain right-click (not crouching) draws the bow as usual.
     */
    private InteractionResult superUse(Level level, Player player, InteractionHand hand) {
        //? if >=26.1 {
        return super.use(level, player, hand);
        //?} else {
        /*return super.use(level, player, hand).getResult();
        *///?}
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!usable(player)) {
            if (player instanceof ServerPlayer sp) {
                Compat.overlay(sp, Component.literal("Only Rangers can use the compound bow."));
            }
            return InteractionResult.FAIL;
        }
        if (!(player instanceof ServerPlayer sp)) {
            // Client side: while crouching this is a grapple click, so do not start the draw animation.
            return player.isShiftKeyDown() ? InteractionResult.SUCCESS : superUse(level, player, hand);
        }
        if (!sp.isShiftKeyDown()) { // whichever key the player has bound to crouch
            return superUse(level, player, hand);
        }
        long now = sp.level().getServer().getTickCount();
        Grapple existing = GRAPPLES.get(sp.getUUID());
        if (existing == null) {
            Compat.overlay(sp, Component.literal("Crouch and hold your aim on a spot to set the anchor."));
            return InteractionResult.FAIL;
        }
        if (existing.engaged) {
            return InteractionResult.SUCCESS; // already reeling or hanging
        }
        // The shot: reel in. Gravity is switched off for the pull and the hang that follows, and a grounded player is lifted a little.
        existing.engaged = true;
        existing.pulling = true;
        existing.start = now;
        hang(sp, true);
        if (sp.onGround() && existing.anchor.y > sp.getY() + 2.0
                && sp.level().noCollision(sp, sp.getBoundingBox().move(0, 0.75, 0))) {
            sp.connection.teleport(sp.getX(), sp.getY() + 0.75, sp.getZ(), sp.getYRot(), sp.getXRot());
        }
        sp.getItemInHand(hand).hurtAndBreak(1, sp, hand);
        sp.level().playSound(null, sp.blockPosition(), GateboundSounds.of(SoundEvents.CROSSBOW_SHOOT), SoundSource.PLAYERS, 1.0f, 0.7f);
        Compat.overlay(sp, Component.literal("Reeling in. You will hang there until you jump."));
        return InteractionResult.SUCCESS;
    }

    private static boolean holdsCompoundBow(ServerPlayer p) {
        return usable(p) && (p.getMainHandItem().getItem() instanceof CompoundBow || p.getOffhandItem().getItem() instanceof CompoundBow);
    }

    /** Crouching with the bow: keep aiming at one spot to set the anchor there. */
    private static void tickAim(ServerPlayer p, long now) {
        UUID id = p.getUUID();
        if (GRAPPLES.containsKey(id) || !p.isAlive() || !p.isShiftKeyDown() || !holdsCompoundBow(p)
                || READY.getOrDefault(id, 0L) > now) {
            AIMS.remove(id);
            return;
        }
        Vec3 from = p.getEyePosition();
        Vec3 end = from.add(p.getLookAngle().scale(GRAPPLE_RANGE));
        BlockHitResult hit = p.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) {
            AIMS.remove(id);
            return;
        }
        Aim aim = AIMS.computeIfAbsent(id, k -> new Aim());
        if (!hit.getBlockPos().equals(aim.block)) {
            aim.block = hit.getBlockPos().immutable();
            aim.ticks = 0;
        } else {
            aim.ticks++;
        }
        Vec3 spot = hit.getLocation();
        p.level().sendParticles(ParticleTypes.END_ROD, spot.x, spot.y, spot.z, 1, 0.05, 0.05, 0.05, 0.0);
        if (aim.ticks >= AIM_TICKS) {
            AIMS.remove(id);
            double dist = spot.distanceTo(p.position().add(0, p.getBbHeight() * 0.5, 0));
            GRAPPLES.put(id, new Grapple(spot, p.level().dimension(), now, dist));
            p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.CHAIN_PLACE), SoundSource.PLAYERS, 1.0f, 1.2f);
            Compat.overlay(p, Component.literal("Anchor set. Shoot to reel in."));
        }
    }

    private static final String HANG_TAG = "gatebound_hanging";

    private static void hang(net.minecraft.world.entity.player.Player p, boolean on) {
        p.setNoGravity(on);
        if (on) {
            p.addTag(HANG_TAG);
        } else {
            p.removeTag(HANG_TAG);
        }
    }

    private static void release(ServerPlayer p, long now) {
        GRAPPLES.remove(p.getUUID());
        hang(p, false);
        READY.put(p.getUUID(), now + GRAPPLE_COOLDOWN_TICKS);
        p.setDeltaMovement(p.getDeltaMovement().scale(0.8));
        p.connection.send(new ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
    }

    private static boolean hadLines;

    /** Tells every client where each line is anchored, so the lead is drawn for everyone (see client/GrappleLeashLayer). */
    private static void sync(net.minecraft.server.MinecraftServer server, long now) {
        if (now % 4 != 0 || (GRAPPLES.isEmpty() && !hadLines)) {
            return;
        }
        java.util.List<dev.gatebound.network.GrappleSyncPayload.Line> lines = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Grapple> e : GRAPPLES.entrySet()) {
            Grapple g = e.getValue();
            lines.add(new dev.gatebound.network.GrappleSyncPayload.Line(e.getKey(), g.anchor.x, g.anchor.y, g.anchor.z, g.rope));
        }
        hadLines = !lines.isEmpty();
        dev.gatebound.network.GrappleSyncPayload payload = new dev.gatebound.network.GrappleSyncPayload(lines);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            dev.architectury.networking.NetworkManager.sendToPlayer(p, payload);
        }
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        long now = server.getTickCount();
        sync(server, now);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            tickAim(p, now);
        }
        AIMS.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        if (GRAPPLES.isEmpty()) {
            return;
        }
        for (UUID id : new java.util.ArrayList<>(GRAPPLES.keySet())) {
            Grapple g = GRAPPLES.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || !p.level().dimension().equals(g.dim)) {
                GRAPPLES.remove(id);
                if (p != null) {
                    hang(p, false);
                }
                continue;
            }
            long age = now - g.start;
            Vec3 body = p.position().add(0, p.getBbHeight() * 0.5, 0);
            Vec3 off = body.subtract(g.anchor);
            double dist = off.length();
            if (!g.engaged) {
                // Anchor set, waiting for the shot: the line is only a marker. Drop it if it goes stale or out of reach.
                if (age > ANCHOR_WAIT_TICKS || !holdsCompoundBow(p) || dist > GRAPPLE_RANGE * 1.5) {
                    GRAPPLES.remove(id);
                } else {
                    g.rope = Math.max(g.rope, dist); // follows the player until the shot, so the marker line stays straight
                }
                continue;
            }
            // Let go: jump (after a moment, so the click that anchored it does not count) or time out.
            boolean inDungeon = p.level().dimension().equals(dev.gatebound.dungeon.DungeonRegistries.DUNGEON);
            long hangLimit = DUNGEON_HANG_SECONDS * 20L;
            if (inDungeon && g.hangStart > 0) {
                long left = hangLimit - (now - g.hangStart);
                if (left == 100) {
                    Compat.overlay(p, Component.literal("The line is giving way..."));
                }
            }
            if ((Compat.input(p).jump() && age > 8) || (inDungeon && g.hangStart > 0 && now - g.hangStart > hangLimit)
                    || (g.pulling && age > 100)) { // a pull that never arrives (blocked) is dropped after 5 s
                release(p, now);
                continue;
            }
            if (g.pulling && dist < 2.0) {
                // Arrived: stop dead and keep hanging right there (gravity stays off) until the player jumps.
                g.pulling = false;
                g.hangStart = now;
                g.rope = Math.max(dist, 0.5);
                p.setDeltaMovement(Vec3.ZERO);
                p.connection.send(new ClientboundSetEntityMotionPacket(p));
                p.resetFallDistance();
                Compat.overlay(p, Component.literal("Hanging. Jump to let go."));
                continue;
            }
            if (g.pulling) {
                p.setDeltaMovement(off.scale(-1).normalize().scale(GRAPPLE_SPEED));
                g.rope = Math.min(g.rope, dist);
            } else if (dist > g.rope && dist > 1.0e-3) {
                // Hanging: the line is taut. Cancel motion away from the anchor and ease back onto the rope's length.
                Vec3 r = off.scale(1.0 / dist);
                p.setDeltaMovement(r.scale(-Math.min((dist - g.rope) * 0.35, 0.7)));
            } else {
                // Slack: nothing to do, but a hanging player never takes fall damage while the line holds.
                p.resetFallDistance();
                continue;
            }
            p.connection.send(new ClientboundSetEntityMotionPacket(p));
            p.resetFallDistance();
        }
    }

}
