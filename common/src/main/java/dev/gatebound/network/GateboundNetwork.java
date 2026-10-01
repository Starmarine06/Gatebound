package dev.gatebound.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.gatebound.Gatebound;
import dev.gatebound.client.ClientHooks;
import dev.gatebound.combat.CombatHandler;
import dev.gatebound.data.ClientCombatData;
import dev.gatebound.data.ClientPlayerData;
import dev.gatebound.data.ClientSkillData;
import dev.gatebound.skill.SkillHandler;
import dev.gatebound.data.Progression;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class GateboundNetwork {
    private GateboundNetwork() {
    }

    public static void init() {
        NetworkManager.registerS2C(PingPayload.TYPE, PingPayload.CODEC, (payload, context) -> {
            context.queue(() -> context.getPlayer().sendSystemMessage(Component.literal("[Gatebound] Server says: " + payload.message())));
            NetworkManager.sendToServer(new PongPayload());
        });

        NetworkManager.registerS2C(SyncDataPayload.TYPE, SyncDataPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientPlayerData.update(payload)));

        NetworkManager.registerS2C(OpenAwakeningPayload.TYPE, OpenAwakeningPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::openAwakening)));

        NetworkManager.registerS2C(PostureSyncPayload.TYPE, PostureSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientCombatData.update(payload)));

        NetworkManager.registerS2C(ParryStatePayload.TYPE, ParryStatePayload.CODEC, (payload, context) ->
                context.queue(() -> ClientCombatData.update(payload)));

        NetworkManager.registerS2C(ManaSyncPayload.TYPE, ManaSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientSkillData.update(payload)));

        NetworkManager.registerS2C(SkillCooldownPayload.TYPE, SkillCooldownPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientSkillData.update(payload)));

        NetworkManager.registerC2S(UseSkillPayload.TYPE, UseSkillPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        SkillHandler.use(player, payload.slot());
                    }
                }));

        NetworkManager.registerC2S(PongPayload.TYPE, PongPayload.CODEC, (payload, context) ->
                context.queue(() -> Gatebound.LOGGER.info("Received pong from {}", context.getPlayer().getName().getString())));

        NetworkManager.registerS2C(OpenElementPayload.TYPE, OpenElementPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::openElement)));

        NetworkManager.registerC2S(ChooseElementPayload.TYPE, ChooseElementPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.chooseElement(player, payload.elementId());
                    }
                }));

        NetworkManager.registerC2S(ParryPayload.TYPE, ParryPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        CombatHandler.pressParry(player);
                    }
                }));

        NetworkManager.registerC2S(SpendStatPayload.TYPE, SpendStatPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.spendStat(player, payload.statId(), payload.points());
                    }
                }));

        NetworkManager.registerC2S(ChooseClassPayload.TYPE, ChooseClassPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.chooseClass(player, payload.classId());
                    }
                }));
    }
}
