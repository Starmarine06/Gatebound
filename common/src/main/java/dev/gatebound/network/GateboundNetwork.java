package dev.gatebound.network;

import dev.gatebound.compat.Compat;
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
        Compat.registerS2C(PingPayload.TYPE, PingPayload.CODEC, (payload, context) -> {
            context.queue(() -> context.getPlayer().sendSystemMessage(Component.literal("[Gatebound] Server says: " + payload.message())));
            NetworkManager.sendToServer(new PongPayload());
        });

        Compat.registerS2C(SyncDataPayload.TYPE, SyncDataPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientPlayerData.update(payload)));

        Compat.registerS2C(OpenAwakeningPayload.TYPE, OpenAwakeningPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::openAwakening)));

        Compat.registerS2C(PostureSyncPayload.TYPE, PostureSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientCombatData.update(payload)));

        Compat.registerS2C(ParryStatePayload.TYPE, ParryStatePayload.CODEC, (payload, context) ->
                context.queue(() -> ClientCombatData.update(payload)));

        Compat.registerS2C(GrappleSyncPayload.TYPE, GrappleSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> dev.gatebound.data.ClientGrappleData.update(payload)));

        Compat.registerS2C(QuiverSyncPayload.TYPE, QuiverSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> dev.gatebound.data.ClientQuiverData.update(payload)));

        Compat.registerS2C(FormSyncPayload.TYPE, FormSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> dev.gatebound.data.ClientFormData.update(payload)));

        Compat.registerS2C(ManaSyncPayload.TYPE, ManaSyncPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientSkillData.update(payload)));

        Compat.registerS2C(SkillCooldownPayload.TYPE, SkillCooldownPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientSkillData.update(payload)));

        Compat.registerC2S(UseSkillPayload.TYPE, UseSkillPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        SkillHandler.use(player, payload.slot());
                    }
                }));

        Compat.registerC2S(PongPayload.TYPE, PongPayload.CODEC, (payload, context) ->
                context.queue(() -> Gatebound.LOGGER.info("Received pong from {}", context.getPlayer().getName().getString())));

        Compat.registerS2C(OpenElementPayload.TYPE, OpenElementPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> ClientHooks::openElement)));

        Compat.registerS2C(OpenSpellPickPayload.TYPE, OpenSpellPickPayload.CODEC, (payload, context) ->
                context.queue(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> ClientHooks.openSpellPick(payload.options()))));

        Compat.registerC2S(ChooseSpellPayload.TYPE, ChooseSpellPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.chooseSpell(player, payload.skillId());
                    }
                }));

        Compat.registerC2S(ChooseElementPayload.TYPE, ChooseElementPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.chooseElement(player, payload.elementId());
                    }
                }));

        Compat.registerC2S(ParryPayload.TYPE, ParryPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        CombatHandler.pressParry(player);
                    }
                }));

        Compat.registerC2S(SpendStatPayload.TYPE, SpendStatPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.spendStat(player, payload.statId(), payload.points());
                    }
                }));

        Compat.registerC2S(ChooseClassPayload.TYPE, ChooseClassPayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        Progression.chooseClass(player, payload.classId());
                    }
                }));
    }
}
