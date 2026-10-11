package dev.gatebound.dungeon;

import dev.gatebound.compat.Compat;
import dev.gatebound.sound.GateboundSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The end of a gate dungeon: once the guardian falls a countdown (set by the gate's rank) begins. Players must loot
 * and leave through the portal home before it runs out; anyone still inside when the dungeon collapses dies and,
 * unless configured otherwise, loses everything they carry.
 */
public final class GateCollapse {
    private static final String PREFIX = "[Gatebound] ";

    /** Static holder; not instantiable. */
    private GateCollapse() {
    }

    /** Ticks before a gate floor collapses, by rank. */
    public static long ticksFor(GateRank rank) {
        return Math.max(200L, Math.round(rank.collapseSeconds() * DungeonConfig.gateCollapseScale * 20.0));
    }

    /** The guardian of this gate dungeon just fell: start the clock and tell everyone on the floor. */
    public static void start(ServerLevel level, DungeonStore.FloorState fs, GateRank rank, List<ServerPlayer> here, long now) {
        if (!DungeonConfig.gateCollapse) {
            return;
        }
        fs.collapseAt = now + ticksFor(rank);
        String loss = DungeonConfig.gateCollapseLoseItems ? " and lose everything you carry" : "";
        for (ServerPlayer p : here) {
            p.sendSystemMessage(Component.literal(PREFIX + "The dungeon is unstable! It collapses in " + clock(fs.collapseAt - now)
                    + ". Loot the chests and leave through the portal, or you will die" + loss + "!")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.WARDEN_SONIC_BOOM), SoundSource.PLAYERS, 0.6f, 0.6f);
        }
    }

    /** Called once a second for every gate floor with a running countdown. */
    public static void tick(ServerLevel level, DungeonStore.FloorState fs, GateRank rank, long now) {
        if (fs.collapseAt <= 0) {
            return;
        }
        List<ServerPlayer> here = DungeonManager.playersOnFloor(level, fs.floor);
        long left = fs.collapseAt - now;
        if (left <= 0) {
            collapse(level, fs, rank, here);
            return;
        }
        long secs = (left + 19) / 20;
        if (!fs.upPortal.equals(net.minecraft.core.BlockPos.ZERO) && !here.isEmpty()) {
            // A column of light marks the way out; it turns the rank's colour and tightens as time runs out.
            var dust = Compat.dust(rank.rgb(), 1.6f);
            for (int dy = 0; dy < 14; dy += 2) {
                level.sendParticles(dust, fs.upPortal.getX() + 0.5, fs.upPortal.getY() + dy, fs.upPortal.getZ() + 0.5, 2, 0.25, 0.3, 0.25, 0);
            }
            level.sendParticles(ParticleTypes.END_ROD, fs.upPortal.getX() + 0.5, fs.upPortal.getY() + 1, fs.upPortal.getZ() + 0.5, 3, 0.3, 1.0, 0.3, 0.02);
        }
        boolean urgent = secs <= 10;
        ChatFormatting colour = urgent ? ChatFormatting.DARK_RED : secs <= 30 ? ChatFormatting.RED : ChatFormatting.GOLD;
        for (ServerPlayer p : here) {
            Compat.overlay(p, Component.literal("DUNGEON COLLAPSING  " + clock(left) + "  - reach the portal!")
                    .withStyle(colour, ChatFormatting.BOLD));
            if (urgent) {
                p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.ANVIL_LAND), SoundSource.PLAYERS, 1f, 0.5f + (10 - secs) * 0.05f);
            } else if (secs == 60 || secs == 30) {
                p.sendSystemMessage(Component.literal(PREFIX + secs + " seconds until the dungeon collapses!").withStyle(colour));
                p.level().playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.BELL_BLOCK), SoundSource.PLAYERS, 1f, 0.6f);
            }
            if (secs <= 30) {
                // Rubble raining down grows heavier as the end nears.
                level.sendParticles(ParticleTypes.ASH, p.getX(), p.getY() + 2, p.getZ(), 25, 4, 2, 4, 0.02);
            }
        }
    }

    /** Collapses the gate: the players on the floor die (and may lose their items). */
    private static void collapse(ServerLevel level, DungeonStore.FloorState fs, GateRank rank, List<ServerPlayer> here) {
        fs.collapseAt = 0;
        for (ServerPlayer p : new ArrayList<>(here)) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) {
                continue;
            }
            level.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 1, p.getZ(), 3, 0.6, 0.6, 0.6, 0);
            level.playSound(null, p.blockPosition(), GateboundSounds.of(SoundEvents.GENERIC_EXPLODE), SoundSource.PLAYERS, 1.5f, 0.7f);
            Compat.overlay(p, Component.literal("THE GATE HAS COLLAPSED").withStyle(rank.color(), ChatFormatting.BOLD));
            p.sendSystemMessage(Component.literal(PREFIX + "The dungeon collapsed around you."
                    + (DungeonConfig.gateCollapseLoseItems ? " Everything you carried is lost." : ""))
                    .withStyle(ChatFormatting.DARK_RED));
            if (DungeonConfig.gateCollapseLoseItems) {
                p.getInventory().clearContent();
            }
            p.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
        }
        fs.cleared = true;
    }

    /** Formats ticks as m:ss. */
    public static String clock(long ticks) {
        long s = Math.max(0, (ticks + 19) / 20);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
