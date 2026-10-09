package dev.gatebound.echo;

import dev.gatebound.compat.Compat;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.PlayerData;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillTargeting;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scout's Eye: the Summoner spell that lets you see through one of your echoes. Cast it while looking at an echo; your
 * camera moves into its eyes while your body stands still, protected. Press H to order that echo to scout for gates or
 * to fly to the selected gate while you watch. Sneak (or wait out the time limit) to return to your body.
 */
public final class EchoView {
    private static final int MAX_TICKS = 20 * 90;
    private static final int MANA_COST = 12;

    private static final class Session {
        final UUID echo;
        final int start;
        final int end;
        int order = -1;

        Session(UUID echo, int start) {
            this.echo = echo;
            this.start = start;
            this.end = start + MAX_TICKS;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private EchoView() {
    }

    public static void init() {
        TickEvent.SERVER_POST.register(EchoView::tick);
        PlayerEvent.PLAYER_QUIT.register(player -> end(player, false));
    }

    /** The skill effect. Returns false (and refunds the mana) if no echo is in the crosshair. */
    public static boolean cast(ServerPlayer player, ServerLevel level, PlayerData data) {
        EchoEntity target = null;
        for (LivingEntity hit : SkillTargeting.ray(player, level, 40, 6, ParticleTypes.WITCH)) {
            if (hit instanceof EchoEntity echo && EchoManager.isEcho(echo)) {
                EchoManager.Info info = EchoManager.info(echo);
                if (info != null && info.owner.equals(player.getUUID())) {
                    target = echo;
                    break;
                }
            }
        }
        if (target == null) {
            ManaState.add(player, MANA_COST);
            Compat.overlay(player, Component.literal("Look at one of your echoes to see through its eyes.")
                    .withStyle(ChatFormatting.GRAY));
            return true; // the message above replaces the generic no-target hint
        }
        end(player, false);
        SESSIONS.put(player.getUUID(), new Session(target.getUUID(), player.level().getServer().getTickCount()));
        NetworkManager.sendToPlayer(player, new EchoViewPayload(target.getId()));
        player.sendSystemMessage(Component.literal("[Echo] You see through " + target.mobName()
                + "'s eyes. Press H to give it orders. Sneak to return.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Order set through the Echoes screen while viewing: the order the viewed echo follows, or -1 for its group order. */
    public static int orderFor(UUID echoId) {
        for (Session s : SESSIONS.values()) {
            if (s.echo.equals(echoId)) {
                return s.order;
            }
        }
        return -1;
    }

    /** Name of the echo this player is currently viewing, or an empty string. */
    public static String viewingName(ServerPlayer player) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) {
            return "";
        }
        Entity e = player.level().getEntity(s.echo);
        return e instanceof EchoEntity echo ? echo.mobName() : "";
    }

    /** kind 0 = scout for gates, 1 = fly to the selected gate, 2 = stop viewing. */
    public static void order(ServerPlayer player, int kind) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null) {
            return;
        }
        switch (kind) {
            case 0 -> s.order = EchoRoster.SEEK;
            case 1 -> s.order = EchoRoster.GOTO;
            default -> end(player, true);
        }
    }

    public static void end(ServerPlayer player, boolean notify) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s == null) {
            return;
        }
        player.removeEffect(MobEffects.SLOWNESS);
        player.removeEffect(MobEffects.RESISTANCE);
        if (notify) {
            Compat.overlay(player, Component.literal("You return to your body.").withStyle(ChatFormatting.GRAY));
        }
        if (player.connection != null) {
            NetworkManager.sendToPlayer(player, new EchoViewPayload(-1));
        }
    }

    private static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (Map.Entry<UUID, Session> e : new HashMap<>(SESSIONS).entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                SESSIONS.remove(e.getKey());
                continue;
            }
            Session s = e.getValue();
            Entity echo = player.level().getEntity(s.echo);
            boolean gone = !(echo instanceof EchoEntity) || !echo.isAlive() || !EchoManager.isEcho(echo);
            if (!player.isAlive() || gone || now >= s.end || (player.isShiftKeyDown() && now - s.start > 10)) {
                end(player, true);
                continue;
            }
            if (now % 10 == 0) {
                // The body stays put and cannot be hurt while the mind is away.
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 9, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 30, 4, false, false));
            }
            if (now % 40 == 0) {
                int left = (s.end - now) / 20;
                Compat.overlay(player, Component.literal("Eye of " + ((EchoEntity) echo).mobName()
                        + " - H: orders, Sneak: return (" + left + "s)").withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
    }
}
