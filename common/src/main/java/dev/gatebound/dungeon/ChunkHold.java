package dev.gatebound.dungeon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps chunks loaded while a build job works on them. Uses plain chunk tickets rather than ServerLevel.setChunkForced,
 * which was measured to block the server thread for tens to hundreds of milliseconds per chunk.
 */
public final class ChunkHold {
    private ChunkHold() {
    }

    public static void hold(ServerLevel level, int cx, int cz) {
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED, new ChunkPos(cx, cz), 0);
    }

    public static void release(ServerLevel level, int cx, int cz) {
        level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, new ChunkPos(cx, cz), 0);
        // Older builds used setChunkForced; make sure nothing is left over from them.
        level.setChunkForced(cx, cz, false);
    }
}
