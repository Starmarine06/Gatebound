package dev.gatebound.dungeon;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parties: a party shares one instance of each gate dungeon, and splits dungeon XP and shards between its members who
 * are on the same floor. Parties live in memory only (they dissolve on a server restart); the id is what gate instances
 * are keyed by, so a solo player is keyed by their own UUID instead.
 */
public final class Parties {
    private static final String PREFIX = "[Party] ";
    private static final long INVITE_TICKS = 1200;
    public static final int MAX_SIZE = 6;

    public static final class Party {
        public final UUID id = UUID.randomUUID();
        public UUID leader;
        public final Set<UUID> members = new LinkedHashSet<>();
    }

    private record Invite(UUID party, long expires) {
    }

    private static final Map<UUID, Party> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Invite> INVITES = new HashMap<>();

    private Parties() {
    }

    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(player -> leave(player.level().getServer(), player.getUUID(), true));
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            var party = Commands.literal("party");
            party.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                ServerPlayer me = ctx.getSource().getPlayerOrException();
                return invite(me, EntityArgument.getPlayer(ctx, "player"));
            })));
            party.then(Commands.literal("accept").executes(ctx -> accept(ctx.getSource().getPlayerOrException())));
            party.then(Commands.literal("leave").executes(ctx -> {
                ServerPlayer me = ctx.getSource().getPlayerOrException();
                if (!inParty(me)) {
                    ctx.getSource().sendFailure(Component.literal("You are not in a party"));
                    return 0;
                }
                leave(ctx.getSource().getServer(), me.getUUID(), false);
                return 1;
            }));
            party.then(Commands.literal("kick").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                ServerPlayer me = ctx.getSource().getPlayerOrException();
                return kick(me, EntityArgument.getPlayer(ctx, "player"));
            })));
            party.then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getPlayerOrException())));
            dispatcher.register(Commands.literal("gatebound").then(party));
        });
    }

    public static Party of(ServerPlayer p) {
        return BY_PLAYER.get(p.getUUID());
    }

    public static boolean inParty(ServerPlayer p) {
        return BY_PLAYER.containsKey(p.getUUID());
    }

    /** What a player's gate dungeons are keyed by: the party id, or their own UUID when alone. */
    public static UUID instanceKey(ServerPlayer p) {
        Party party = of(p);
        return party != null ? party.id : p.getUUID();
    }

    /** How many of the given players (including this one) share a reward with the player: their party members among them. */
    public static int shareCount(ServerPlayer p, List<ServerPlayer> present) {
        Party party = of(p);
        if (party == null) {
            return 1;
        }
        int n = 0;
        for (ServerPlayer o : present) {
            if (party.members.contains(o.getUUID())) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** An even share of a reward total, never less than 1 for a non-empty reward. */
    public static int share(int total, int ways) {
        return total <= 0 ? 0 : Math.max(1, total / Math.max(1, ways));
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(PREFIX + msg).withStyle(color));
    }

    private static void tellAll(MinecraftServer server, Party party, String msg) {
        for (UUID id : party.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                tell(p, msg, ChatFormatting.AQUA);
            }
        }
    }

    private static int invite(ServerPlayer me, ServerPlayer other) {
        MinecraftServer server = me.level().getServer();
        if (me == other) {
            tell(me, "You cannot invite yourself.", ChatFormatting.RED);
            return 0;
        }
        Party party = of(me);
        if (party != null && !party.leader.equals(me.getUUID())) {
            tell(me, "Only the party leader can invite.", ChatFormatting.RED);
            return 0;
        }
        if (party != null && party.members.size() >= MAX_SIZE) {
            tell(me, "The party is full.", ChatFormatting.RED);
            return 0;
        }
        if (inParty(other)) {
            tell(me, other.getName().getString() + " is already in a party.", ChatFormatting.RED);
            return 0;
        }
        if (party == null) {
            party = new Party();
            party.leader = me.getUUID();
            party.members.add(me.getUUID());
            BY_PLAYER.put(me.getUUID(), party);
        }
        INVITES.put(other.getUUID(), new Invite(party.id, server.overworld().getGameTime() + INVITE_TICKS));
        tell(me, "Invited " + other.getName().getString() + ".", ChatFormatting.AQUA);
        tell(other, me.getName().getString() + " invited you to a party. Type /gatebound party accept (within a minute).", ChatFormatting.AQUA);
        return 1;
    }

    private static int accept(ServerPlayer me) {
        MinecraftServer server = me.level().getServer();
        Invite inv = INVITES.remove(me.getUUID());
        if (inv == null || inv.expires < server.overworld().getGameTime()) {
            tell(me, "You have no pending invite.", ChatFormatting.RED);
            return 0;
        }
        if (inParty(me)) {
            tell(me, "Leave your current party first.", ChatFormatting.RED);
            return 0;
        }
        Party party = null;
        for (Party p : new ArrayList<>(BY_PLAYER.values())) {
            if (p.id.equals(inv.party)) {
                party = p;
                break;
            }
        }
        if (party == null || party.members.size() >= MAX_SIZE) {
            tell(me, "That party is gone or full.", ChatFormatting.RED);
            return 0;
        }
        party.members.add(me.getUUID());
        BY_PLAYER.put(me.getUUID(), party);
        tellAll(server, party, me.getName().getString() + " joined the party.");
        return 1;
    }

    private static int kick(ServerPlayer me, ServerPlayer other) {
        Party party = of(me);
        if (party == null || !party.leader.equals(me.getUUID())) {
            tell(me, "Only a party leader can kick.", ChatFormatting.RED);
            return 0;
        }
        if (me == other || !party.members.contains(other.getUUID())) {
            tell(me, "That player is not in your party.", ChatFormatting.RED);
            return 0;
        }
        tell(other, "You were removed from the party.", ChatFormatting.RED);
        leave(me.level().getServer(), other.getUUID(), true);
        return 1;
    }

    private static int list(ServerPlayer me) {
        Party party = of(me);
        if (party == null) {
            tell(me, "You are not in a party.", ChatFormatting.GRAY);
            return 0;
        }
        MinecraftServer server = me.level().getServer();
        StringBuilder sb = new StringBuilder();
        for (UUID id : party.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p != null ? p.getName().getString() : "(offline)");
            if (id.equals(party.leader)) {
                sb.append(" (leader)");
            }
        }
        tell(me, sb.toString(), ChatFormatting.AQUA);
        return 1;
    }

    private static void leave(MinecraftServer server, UUID id, boolean silent) {
        Party party = BY_PLAYER.remove(id);
        INVITES.remove(id);
        if (party == null) {
            return;
        }
        party.members.remove(id);
        ServerPlayer gone = server.getPlayerList().getPlayer(id);
        if (gone != null && !silent) {
            tell(gone, "You left the party.", ChatFormatting.AQUA);
        }
        if (party.members.size() <= 1) {
            for (UUID m : party.members) {
                BY_PLAYER.remove(m);
                ServerPlayer p = server.getPlayerList().getPlayer(m);
                if (p != null) {
                    tell(p, "The party disbanded.", ChatFormatting.AQUA);
                }
            }
            party.members.clear();
            return;
        }
        if (party.leader.equals(id)) {
            party.leader = party.members.iterator().next();
        }
        tellAll(server, party, (gone != null ? gone.getName().getString() : "A member") + " left the party.");
    }
}
