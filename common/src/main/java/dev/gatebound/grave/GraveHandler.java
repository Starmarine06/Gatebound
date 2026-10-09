package dev.gatebound.grave;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.gatebound.Gatebound;
import dev.gatebound.compat.Compat;
import dev.gatebound.dungeon.DungeonRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Turns a dying player's belongings into a grave (unless keepInventory is on), and keeps graves alive across floor rebuilds. */
public final class GraveHandler {
    /** A grave lifted out of a floor that is being rebuilt, to be set down again afterwards. */
    public record Saved(List<ItemStack> items, int xp, UUID owner, String ownerName, long created) {
    }

    private GraveHandler() {
    }

    public static void init() {
        // Registered after the quiver's death hook so a quiver, not a bare stack, is what gets stored.
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && player.level() instanceof ServerLevel level
                    && !player.isSpectator() && !Compat.keepInventory(level)) {
                bury(player, level);
            }
            return EventResult.pass();
        });
    }

    private static void bury(ServerPlayer player, ServerLevel level) {
        Inventory inv = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                items.add(s.copy());
            }
        }
        int xp = Math.min(player.totalExperience, 7 * player.experienceLevel + 100);
        if (items.isEmpty() && xp <= 0) {
            return;
        }
        BlockPos at = findSpot(level, player.blockPosition());
        if (at == null) {
            return; // nowhere to put it: let the vanilla drops happen
        }
        Direction facing = player.getDirection().getOpposite();
        if (!place(level, at, facing, new Saved(items, xp, player.getUUID(), player.getName().getString(),
                level.getServer().overworld().getGameTime()))) {
            return;
        }
        inv.clearContent();
        player.totalExperience = 0;
        player.experienceLevel = 0;
        player.experienceProgress = 0f;
        Gatebound.LOGGER.info("Grave for {} at {} in {}", player.getName().getString(), at.toShortString(), level.dimension().identifier());
        player.sendSystemMessage(Component.literal("[Grave] Your belongings rest in a grave at " + at.getX() + " " + at.getY() + " " + at.getZ() + ".")
                .withStyle(ChatFormatting.GRAY));
    }

    /** Places a grave and fills it. Returns false if the block could not be placed. */
    public static boolean place(ServerLevel level, BlockPos pos, Direction facing, Saved data) {
        level.setBlock(pos, GraveRegistries.GRAVE.get().defaultBlockState().setValue(GraveBlock.FACING, facing), 3);
        if (!(level.getBlockEntity(pos) instanceof GraveBlockEntity grave)) {
            return false;
        }
        grave.items.clear();
        grave.items.addAll(data.items());
        grave.xp = data.xp();
        grave.owner = data.owner();
        grave.ownerName = data.ownerName();
        grave.created = data.created();
        grave.setChanged();
        return true;
    }

    /** The nearest free block to a position (a grave never replaces anything solid). */
    public static BlockPos findSpot(ServerLevel level, BlockPos origin) {
        int minY = level.getMinY() + 1;
        int maxY = level.getMaxY() - 2;
        int y0 = Math.max(minY, Math.min(maxY, origin.getY()));
        for (int r = 0; r <= 3; r++) {
            for (int dy = 0; dy <= 6; dy++) {
                for (int s : new int[]{1, -1}) {
                    int y = y0 + dy * s;
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            BlockPos p = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                            if (level.isLoaded(p) && level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty()
                                    && !level.getBlockState(p.below()).isAir()) {
                                return p;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Lifts every grave in the box out of the world (so a floor rebuild cannot wipe them) and returns their contents. */
    public static List<Saved> collect(ServerLevel level, AABB box) {
        List<Saved> out = new ArrayList<>();
        int cx0 = (int) Math.floor(box.minX) >> 4;
        int cx1 = (int) Math.floor(box.maxX) >> 4;
        int cz0 = (int) Math.floor(box.minZ) >> 4;
        int cz1 = (int) Math.floor(box.maxZ) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (be instanceof GraveBlockEntity g && box.contains(be.getBlockPos().getX() + 0.5, be.getBlockPos().getY() + 0.5,
                            be.getBlockPos().getZ() + 0.5)) {
                        out.add(new Saved(new ArrayList<>(g.items), g.xp, g.owner, g.ownerName, g.created));
                        level.removeBlock(be.getBlockPos(), false);
                    }
                }
            }
        }
        return out;
    }

    /** Sets lifted graves back down at and around a spot. */
    public static void restore(ServerLevel level, List<Saved> graves, BlockPos around) {
        int n = 0;
        for (Saved s : graves) {
            BlockPos spot = findSpot(level, around.offset(n++ * 2 - graves.size(), 0, 2));
            if (spot != null && !place(level, spot, Direction.NORTH, s)) {
                Gatebound.LOGGER.warn("Could not restore a grave of {}", s.ownerName());
            }
        }
    }

    /** Dimension check used by the break protection: graves are never breakable in the dungeon or elsewhere. */
    public static boolean isDungeon(ServerLevel level) {
        return level.dimension().equals(DungeonRegistries.DUNGEON);
    }
}
