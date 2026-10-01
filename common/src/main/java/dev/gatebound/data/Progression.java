package dev.gatebound.data;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.SyncDataPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Enemy;

/** Server-side progression logic: kill XP, level-ups, applying stats and syncing to the client. */
public final class Progression {
    private Progression() {
    }

    public static void init() {
        PlayerEvent.PLAYER_JOIN.register(Progression::refresh);
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> refresh(player));
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player) {
                giveXp(player, Math.max(1, (int) (entity.getMaxHealth() / 2)));
            }
            return EventResult.pass();
        });
    }

    public static void giveXp(ServerPlayer player, int amount) {
        PlayerData data = PlayerDataStore.of(player);
        int gained = data.addXp(amount);
        if (gained > 0) {
            player.sendSystemMessage(Component.literal("[Gatebound] Level up! You are now level " + data.level
                    + " (+" + gained * PlayerData.STAT_POINTS_PER_LEVEL + " stat points)"));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        }
        save(player, data);
    }

    /** Persist, re-apply attribute effects and push a fresh snapshot to the client. */
    public static void save(ServerPlayer player, PlayerData data) {
        PlayerDataStore.markDirty(player);
        refresh(player);
    }

    public static void refresh(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        StatEffects.apply(player, data);
        NetworkManager.sendToPlayer(player, SyncDataPayload.of(data));
    }
}
