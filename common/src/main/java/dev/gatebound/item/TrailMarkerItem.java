package dev.gatebound.item;

import dev.gatebound.sound.GateboundSounds;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.compat.Compat;
import dev.gatebound.compat.MobTypes;
import dev.gatebound.dungeon.DungeonConfig;
import dev.gatebound.dungeon.DungeonRegistries;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Right-click to leave a floating mark where you stand, so you can tell where you have already been. Works only inside
 * the dungeon dimension, and each mark fades after a few minutes. A player can have only so many out at once; the
 * oldest goes first. A mark is an invisible armor stand with a visible name (tag gatebound_marker_<expiry tick>).
 */
public final class TrailMarkerItem extends Item {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    /** The plain marker (gold), and one per dye colour: crafting a marker with a dye picks its colour. */
    public static final RegistrySupplier<Item> TRAIL_MARKER = marker("trail_marker", null);
    public static final Map<DyeColor, RegistrySupplier<Item>> DYED = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor dye : DyeColor.values()) {
            DYED.put(dye, marker("trail_marker_" + dye.getName(), dye));
        }
    }

    private static final String TAG = "gatebound_marker";
    private static final int PLAIN_COLOR = 0xFFAA00;
    private static final Map<UUID, ArrayDeque<UUID>> PLACED = new HashMap<>();

    private final int color;

    /** Creates a trail marker of a colour. */
    private TrailMarkerItem(Properties properties, int color) {
        super(properties);
        this.color = color;
    }

    /** Registers a coloured marker item. */
    private static RegistrySupplier<Item> marker(String id, DyeColor dye) {
        return ITEMS.register(id, () -> new TrailMarkerItem(new Item.Properties()
                .arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(64)
                .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, id))),
                dye == null ? PLAIN_COLOR : dye.getTextColor() & 0xFFFFFF));
    }

    /** Registers the marker items and their dungeon expiry tick. */
    public static void init() {
        ITEMS.register();
        TickEvent.SERVER_POST.register(server -> {
            ServerLevel dungeon = server.getLevel(DungeonRegistries.DUNGEON);
            if (dungeon == null) {
                return;
            }
            if (server.getTickCount() % 10 == 3) {
                clearLeftBehind(server, dungeon);
            }
            if (server.getTickCount() % 100 != 11) {
                return;
            }
            long now = server.overworld().getGameTime();
            for (ServerPlayer p : dungeon.players()) {
                for (ArmorStand stand : dungeon.getEntitiesOfClass(ArmorStand.class, p.getBoundingBox().inflate(96, 48, 96),
                        s -> s.entityTags().contains(TAG))) {
                    if (expiry(stand) <= now) {
                        stand.discard();
                    }
                }
            }
        });
    }

    /** ArmorStand.setMarker is private; the marker flag removes the hitbox. */
    private static void markerOnly(ArmorStand stand) {
        try {
            java.lang.reflect.Method m = ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
            m.setAccessible(true);
            m.invoke(stand, true);
        } catch (ReflectiveOperationException e) {
            Gatebound.LOGGER.warn("Could not make a trail marker hitbox-free: {}", e.toString());
        }
    }

    /** A player's marks vanish as soon as they leave the floor they placed them on (or the dungeon). */
    private static void clearLeftBehind(net.minecraft.server.MinecraftServer server, ServerLevel dungeon) {
        int total = dev.gatebound.dungeon.DungeonStore.get(server).totalFloors;
        var it = PLACED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(e.getKey());
            int here = owner != null && owner.level().dimension().equals(DungeonRegistries.DUNGEON)
                    ? dev.gatebound.dungeon.Spire.floorAt(owner.getX(), owner.getZ(), total) : -1;
            var marks = e.getValue().iterator();
            while (marks.hasNext()) {
                Entity mark = dungeon.getEntity(marks.next());
                if (mark == null || !mark.isAlive()) {
                    marks.remove();
                } else if (here <= 0 || dev.gatebound.dungeon.Spire.floorAt(mark.getX(), mark.getZ(), total) != here) {
                    mark.discard();
                    marks.remove();
                }
            }
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /** Expiry tick stored on a marker stand. */
    private static long expiry(Entity stand) {
        for (String t : stand.entityTags()) {
            if (t.startsWith(TAG + "_")) {
                try {
                    return Long.parseLong(t.substring(TAG.length() + 1));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)
                || !sl.dimension().equals(DungeonRegistries.DUNGEON)) {
            Compat.overlay(player, Component.literal("Trail markers only work inside the dungeon.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        ArmorStand stand = MobTypes.ARMOR_STAND.create(sl, EntitySpawnReason.COMMAND);
        if (stand == null) {
            return InteractionResult.FAIL;
        }
        long expires = sl.getServer().overworld().getGameTime() + DungeonConfig.markerTicks;
        stand.snapTo(sp.getX(), sp.getY() + 0.2, sp.getZ(), 0, 0);
        stand.addTag(TAG);
        stand.addTag(TAG + "_" + expires);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        Compat.setPermanentlyInvulnerable(stand, true);
        stand.setSilent(true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(Component.literal("X").withStyle(s -> s.withColor(color).withBold(true)));
        markerOnly(stand); // no hitbox, so it cannot be hit, pushed or dressed: a mark stays until it fades
        sl.addFreshEntity(stand);
        sl.playSound(null, sp.blockPosition(), GateboundSounds.custom("item.trail_marker"), SoundSource.PLAYERS, 0.6f, 1f);

        ArrayDeque<UUID> mine = PLACED.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>());
        mine.add(stand.getUUID());
        while (mine.size() > DungeonConfig.markerMax) {
            Entity old = sl.getEntity(mine.poll());
            if (old != null) {
                old.discard();
            }
        }
        if (!player.getAbilities().instabuild) {
            ItemStack stack = player.getItemInHand(hand);
            stack.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}
