package dev.gatebound.dungeon;

import dev.gatebound.compat.MobTypes;
import dev.gatebound.compat.Compat;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating text above every gate and floor portal that says where it leads. The labels are invisible, invulnerable
 * armor stands with a visible name (tag gatebound_label); they are created around players, refreshed when the text
 * changes and removed when their gate or portal is gone.
 */
public final class GateLabels {
    private static final String TAG = "gatebound_label";
    private static final int SCAN_RADIUS = 24;
    private static final int SWEEP_RADIUS = 16;

    private record Label(BlockPos pos, double baseY, Component top, Component bottom) {
    }

    private GateLabels() {
    }

    public static void init() {
        TickEvent.SERVER_POST.register(server -> {
            if (server.getTickCount() % 40 == 7) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    update(server, player);
                }
            }
        });
    }

    private static void update(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.level();
        DungeonStore store = DungeonStore.get(server);
        int total = store.ensureTower(server);
        boolean inSpire = level.dimension().equals(DungeonRegistries.DUNGEON);
        int px = player.blockPosition().getX();
        int py = player.blockPosition().getY();
        int pz = player.blockPosition().getZ();

        AABB entrance = null;
        if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && !store.entranceMin.equals(BlockPos.ZERO)) {
            entrance = new AABB(store.entranceMin.getX(), store.entranceMin.getY(), store.entranceMin.getZ(),
                    store.entranceMax.getX() + 1, store.entranceMax.getY() + 1, store.entranceMax.getZ() + 1).inflate(1.5);
        }
        List<Label> labels = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(px - SCAN_RADIUS, py - 5, pz - SCAN_RADIUS,
                px + SCAN_RADIUS, py + 6, pz + SCAN_RADIUS)) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof GateBlock) {
                if (entrance != null && entrance.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                    continue; // the tower's own entrance gate stays unlabeled
                }
                labels.add(gateLabel(store, level, pos.immutable(), total));
            } else if (state.getBlock() instanceof GateExitBlock
                    && state.getValue(GateExitBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                labels.add(exitLabel(level, pos.immutable(), state.getValue(GateExitBlock.KIND), inSpire, total));
            }
        }

        for (Label label : labels) {
            place(level, label);
        }

        // Remove labels near the player that no longer belong to any gate or portal.
        AABB sweep = player.getBoundingBox().inflate(SWEEP_RADIUS, 12, SWEEP_RADIUS);
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, sweep, s -> s.entityTags().contains(TAG))) {
            boolean owned = false;
            for (Label label : labels) {
                if (Math.abs(stand.getX() - (label.pos.getX() + 0.5)) < 0.6 && Math.abs(stand.getZ() - (label.pos.getZ() + 0.5)) < 0.6
                        && stand.getY() >= label.pos.getY() && stand.getY() < label.pos.getY() + 4) {
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                stand.discard();
            }
        }
    }

    private static Label gateLabel(DungeonStore store, ServerLevel level, BlockPos pos, int total) {
        for (DungeonStore.GateRef g : store.gates) {
            if (g.dim.equals(level.dimension()) && g.pos.equals(pos) && Spire.isGate(g.floor)) {
                GateRank rank = Spire.rank(g.floor, total);
                return new Label(pos, pos.getY() + 0.9,
                        Component.literal(rank.label() + " Gate").withStyle(rank.color(), ChatFormatting.BOLD),
                        Component.literal(DungeonConfig.gateLeaks ? "Monsters leak out until its guardian falls" : "A dungeon lies beyond")
                                .withStyle(DungeonConfig.gateLeaks ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
        }
        return new Label(pos, pos.getY() + 0.9,
                Component.literal("Gate").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal("Destination unknown").withStyle(ChatFormatting.GRAY));
    }

    private static Label exitLabel(ServerLevel level, BlockPos pos, GateExitBlock.Kind kind, boolean inSpire, int total) {
        int floor = inSpire ? Spire.floorAt(pos.getX() + 0.5, pos.getZ() + 0.5, total) : 0;
        double y = pos.getY() + 1.9; // above the two-block pane
        if (Spire.isGate(floor)) {
            return new Label(pos, y,
                    Component.literal(kind == GateExitBlock.Kind.HOME ? "Exit the Gate Dungeon" : "Back to the gate").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.literal(kind == GateExitBlock.Kind.HOME ? "The gate closes once its guardian has fallen" : "The gate stays open").withStyle(ChatFormatting.GRAY));
        }
        return switch (kind) {
            case UP -> new Label(pos, y,
                    Component.literal("Up to Floor " + (floor + 1)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal(floor > 0 && floor < total ? Spire.describe(floor + 1, total) : "Next floor").withStyle(ChatFormatting.GRAY));
            case DOWN -> floor <= 1
                    ? new Label(pos, y,
                    Component.literal("Leave the Spire").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.literal("Back to the surface").withStyle(ChatFormatting.GRAY))
                    : new Label(pos, y,
                    Component.literal("Down to Floor " + (floor - 1)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal(Spire.describe(floor - 1, total)).withStyle(ChatFormatting.GRAY));
            case HOME -> inSpire && floor == total
                    ? new Label(pos, y,
                    Component.literal("Leave the Spire").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.literal("You conquered the summit").withStyle(ChatFormatting.GRAY))
                    : new Label(pos, y,
                    Component.literal("Escape Rift").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                    Component.literal("Returns you to where you entered").withStyle(ChatFormatting.GRAY));
        };
    }

    private static void place(ServerLevel level, Label label) {
        double x = label.pos.getX() + 0.5;
        double z = label.pos.getZ() + 0.5;
        ensure(level, label, 0, x, label.baseY + 0.3, z, label.top);
        ensure(level, label, 1, x, label.baseY, z, label.bottom);
    }

    private static void ensure(ServerLevel level, Label label, int line, double x, double y, double z, Component text) {
        String lineTag = TAG + "_" + line;
        AABB box = new AABB(x - 0.3, label.pos.getY(), z - 0.3, x + 0.3, label.pos.getY() + 4, z + 0.3);
        List<ArmorStand> found = level.getEntitiesOfClass(ArmorStand.class, box, s -> s.entityTags().contains(lineTag));
        ArmorStand stand = found.isEmpty() ? null : found.get(0);
        for (int i = 1; i < found.size(); i++) {
            found.get(i).discard();
        }
        if (stand == null) {
            stand = MobTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            if (stand == null) {
                return;
            }
            stand.snapTo(x, y, z, 0, 0);
            stand.addTag(TAG);
            stand.addTag(lineTag);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            Compat.setPermanentlyInvulnerable(stand, true);
            stand.setSilent(true);
            stand.setCustomNameVisible(true);
            stand.setCustomName(text);
            level.addFreshEntity(stand);
        } else if (stand.getCustomName() == null || !stand.getCustomName().equals(text)) {
            stand.setCustomName(text);
        }
    }
}
