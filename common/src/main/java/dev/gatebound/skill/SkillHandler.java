package dev.gatebound.skill;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.network.ManaSyncPayload;
import dev.gatebound.network.SkillCooldownPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mana regeneration, skill casting, cooldowns and syncing them to the client. */
public final class SkillHandler {
    private static final Map<UUID, Map<String, Integer>> COOLDOWN_END = new HashMap<>();

    private SkillHandler() {
    }

    public static void init() {
        TickEvent.SERVER_POST.register(server -> {
            int tick = server.getTickCount();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.isAlive()) {
                    continue;
                }
                PlayerData data = PlayerDataStore.of(player);
                ManaState.add(player, ManaState.regenPerTick(data));
                if (tick % 5 == 0) {
                    sendMana(player, false);
                }
                if (tick % 100 == 0) {
                    // Mana lives in PlayerData; flag the save so it is written with the next world save.
                    PlayerDataStore.markDirty(player);
                }
            }
        });
        // Mana is deliberately NOT refilled on join or respawn: it is saved with the player and picks up where it left off.
        PlayerEvent.PLAYER_JOIN.register(player -> sendMana(player, true));
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> sendMana(player, true));
        PlayerEvent.PLAYER_QUIT.register(player -> {
            PlayerDataStore.markDirty(player);
            ManaState.forget(player.getUUID());
            COOLDOWN_END.remove(player.getUUID());
        });
    }

    /** Shortens every running skill cooldown by the given ticks (used by perfect dodges). */
    public static void reduceCooldowns(ServerPlayer player, int ticks) {
        Map<String, Integer> cooldowns = COOLDOWN_END.get(player.getUUID());
        if (cooldowns == null) {
            return;
        }
        int now = player.level().getServer().getTickCount();
        for (Map.Entry<String, Integer> e : cooldowns.entrySet()) {
            if (e.getValue() > now) {
                e.setValue(Math.max(now, e.getValue() - ticks));
                NetworkManager.sendToPlayer(player, new SkillCooldownPayload(e.getKey(), e.getValue() - now));
            }
        }
    }

    public static void sendMana(ServerPlayer player, boolean force) {
        int[] snap = ManaState.changedSnapshot(player, force);
        if (snap != null) {
            NetworkManager.sendToPlayer(player, new ManaSyncPayload(snap[0], snap[1]));
        }
    }

    /** Casts the skill in the given 0-based slot. Mana and cooldown are spent the moment it is cast, hit or miss. */
    public static void use(ServerPlayer player, int slot) {
        PlayerData data = PlayerDataStore.of(player);
        List<Skill> skills = Skills.available(data.classId, data.elements, data.level);
        if (slot < 0 || slot >= skills.size() || !player.isAlive()) {
            return;
        }
        Skill skill = skills.get(slot);
        // Skills have no cooldowns: mana is the only limit.
        if (!ManaState.spend(player, skill.manaCost())) {
            player.sendOverlayMessage(Component.translatable("skill.gatebound.not_enough_mana"));
            return;
        }
        sendMana(player, true);

        boolean connected = skill.effect().cast(player, player.level(), data);
        if (!connected) {
            player.sendOverlayMessage(Component.translatable("skill.gatebound.no_target"));
        }
        PlayerDataStore.markDirty(player);
    }
}
