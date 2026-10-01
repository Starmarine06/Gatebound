package dev.gatebound.data;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.OpenAwakeningPayload;
import dev.gatebound.network.OpenElementPayload;
import dev.gatebound.skill.MagicConfig;
import java.util.ArrayList;
import java.util.List;
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
        PlayerEvent.PLAYER_JOIN.register(player -> {
            refresh(player);
            openAwakeningIfNeeded(player);
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd, reason) -> refresh(player));
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof Enemy && source.getEntity() instanceof ServerPlayer player) {
                giveXp(player, Math.max(1, (int) (entity.getMaxHealth() / 2)));
            }
            return EventResult.pass();
        });
    }

    /** Applies a class choice. Ignored (with a message) if the player already has a class or the id is unknown. */
    public static void chooseClass(ServerPlayer player, String classId) {
        PlayerData data = PlayerDataStore.of(player);
        GateboundClass chosen = GateboundClass.byId(classId);
        if (chosen == null || !data.classId.isEmpty()) {
            player.sendSystemMessage(Component.literal("[Gatebound] You cannot choose a class right now."));
            return;
        }
        data.classId = chosen.id();
        for (Stat s : Stat.values()) {
            data.stats[s.ordinal()] += chosen.bonus(s);
        }
        save(player, data);
        player.sendSystemMessage(Component.literal("[Gatebound] You awakened as a ")
                .append(Component.translatable(chosen.nameKey())).append("."));
        if (chosen == GateboundClass.MAGE) {
            NetworkManager.sendToPlayer(player, new OpenElementPayload());
        }
    }

    /**
     * A Mage picks their main element; the server then rolls for bonus elements.
     * Each extra element is chosen at random from the ones they do not yet have.
     */
    public static void chooseElement(ServerPlayer player, String elementId) {
        PlayerData data = PlayerDataStore.of(player);
        Element chosen = Element.byId(elementId);
        if (chosen == null || !GateboundClass.MAGE.id().equals(data.classId) || !data.elements.isEmpty()) {
            player.sendSystemMessage(Component.literal("[Gatebound] You cannot choose an element right now."));
            return;
        }
        data.elements.add(chosen.id());
        player.sendSystemMessage(Component.literal("[Gatebound] Your primary affinity is ")
                .append(Component.translatable(chosen.nameKey())).append("."));

        List<Element> remaining = new ArrayList<>(List.of(Element.values()));
        remaining.remove(chosen);
        double chance = MagicConfig.secondElementChance;
        while (!remaining.isEmpty() && player.getRandom().nextDouble() < chance) {
            Element extra = remaining.remove(player.getRandom().nextInt(remaining.size()));
            data.elements.add(extra.id());
            player.sendSystemMessage(Component.literal("[Gatebound] Rare awakening! You also gained ")
                    .append(Component.translatable(extra.nameKey())).append(" magic.")
                    .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
            chance = MagicConfig.thirdElementChance;
        }
        save(player, data);
    }

    /** Server-validated stat spending; used by both the GUI packet and the command. */
    public static boolean spendStat(ServerPlayer player, String statId, int points) {
        Stat stat = Stat.byId(statId);
        PlayerData data = PlayerDataStore.of(player);
        if (stat == null || !data.spend(stat, points)) {
            return false;
        }
        save(player, data);
        return true;
    }

    /** Wipes the class and spent stat points, refunding points for the current level. */
    public static void resetClass(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        data.classId = "";
        data.elements.clear();
        java.util.Arrays.fill(data.stats, Stat.BASE_VALUE);
        data.statPoints = (data.level - 1) * PlayerData.STAT_POINTS_PER_LEVEL;
        save(player, data);
        openAwakeningIfNeeded(player);
    }

    public static void openAwakeningIfNeeded(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        if (data.classId.isEmpty()) {
            NetworkManager.sendToPlayer(player, new OpenAwakeningPayload());
        } else if (GateboundClass.MAGE.id().equals(data.classId) && data.elements.isEmpty()) {
            // A Mage who left before picking an element is asked again on the next join.
            NetworkManager.sendToPlayer(player, new OpenElementPayload());
        }
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
