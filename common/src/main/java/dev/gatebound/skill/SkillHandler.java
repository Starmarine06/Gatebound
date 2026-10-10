package dev.gatebound.skill;

import dev.gatebound.compat.Compat;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.skill.mage.MageConfig;
import dev.gatebound.skill.mage.MageCore;
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
    /** The tick each Mage last cast, for the one-spell-per-second limit. */
    private static final Map<UUID, Integer> LAST_CAST = new HashMap<>();

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
                if (player.isSleepingLongEnough() && ManaState.get(player) < ManaState.max(data)) {
                    // A night's sleep restores the whole pool.
                    ManaState.set(player, ManaState.max(data));
                    sendMana(player, true);
                    Compat.overlay(player, Component.literal("You wake with your mana restored."));
                }
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
            LAST_CAST.remove(player.getUUID());
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
        List<Skill> skills = Skills.available(data.classId, data.elements, data.picks, data.level);
        if (slot < 0 || slot >= skills.size() || !player.isAlive()) {
            return;
        }
        Skill skill = skills.get(slot);
        boolean mage = GateboundClass.MAGE.id().equals(data.classId);
        int now = player.level().getServer().getTickCount();
        if (mage) {
            Integer last = LAST_CAST.get(player.getUUID());
            if (last != null && now - last < MageConfig.castGapTicks) {
                Compat.overlay(player, Component.literal("Your magic is still settling."));
                return;
            }
        }
        // Skills have no cooldowns: mana is the only limit.
        if (!ManaState.spend(player, skill.manaCost())) {
            Compat.overlay(player, Component.translatable("skill.gatebound.not_enough_mana"));
            return;
        }
        sendMana(player, true);
        if (mage) {
            LAST_CAST.put(player.getUUID(), now);
            MageCore.strain(player);
            if (skill.element() != null && skill.minLevel() < 100) { // the level 100 forms play their own transformation
                dev.gatebound.skill.fx.CastFx.cast(player, skill.element());
            }
        } else {
            dev.gatebound.skill.fx.ClassCastFx.cast(player, skill);
        }

        boolean connected = skill.effect().cast(player, player.level(), data);
        net.minecraft.sounds.SoundEvent signature = dev.gatebound.sound.GateboundSounds.skill(skill.id());
        if (signature != null && connected) {
            player.level().playSound(null, player.blockPosition(), signature, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        }
        if (!connected) {
            Compat.overlay(player, Component.translatable("skill.gatebound.no_target"));
        }
        PlayerDataStore.markDirty(player);
    }
}
