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
            }
        });
        PlayerEvent.PLAYER_JOIN.register(player -> {
            ManaState.fill(player);
            sendMana(player, true);
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> {
            ManaState.fill(player);
            sendMana(player, true);
        });
        PlayerEvent.PLAYER_QUIT.register(player -> {
            ManaState.forget(player.getUUID());
            COOLDOWN_END.remove(player.getUUID());
        });
    }

    public static void sendMana(ServerPlayer player, boolean force) {
        int[] snap = ManaState.changedSnapshot(player, force);
        if (snap != null) {
            NetworkManager.sendToPlayer(player, new ManaSyncPayload(snap[0], snap[1]));
        }
    }

    /** Casts the skill in the given 0-based slot, if the player can. */
    public static void use(ServerPlayer player, int slot) {
        PlayerData data = PlayerDataStore.of(player);
        List<Skill> skills = Skills.available(data.classId, data.level);
        if (slot < 0 || slot >= skills.size() || !player.isAlive()) {
            return;
        }
        Skill skill = skills.get(slot);
        int now = player.level().getServer().getTickCount();
        Map<String, Integer> cooldowns = COOLDOWN_END.computeIfAbsent(player.getUUID(), id -> new HashMap<>());

        if (cooldowns.getOrDefault(skill.id(), 0) > now) {
            return;
        }
        if (ManaState.get(player) < skill.manaCost()) {
            player.sendOverlayMessage(Component.translatable("skill.gatebound.not_enough_mana"));
            return;
        }
        if (!skill.effect().cast(player, player.level(), data)) {
            player.sendOverlayMessage(Component.translatable("skill.gatebound.no_target"));
            return;
        }
        ManaState.spend(player, skill.manaCost());
        cooldowns.put(skill.id(), now + skill.cooldownTicks());
        NetworkManager.sendToPlayer(player, new SkillCooldownPayload(skill.id(), skill.cooldownTicks()));
        sendMana(player, true);
    }
}
