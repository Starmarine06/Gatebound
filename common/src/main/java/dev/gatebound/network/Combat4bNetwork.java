package dev.gatebound.network;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.ClientStaminaData;
import net.minecraft.server.level.ServerPlayer;

/** Payload registration for dodge, stamina and player posture. */
public final class Combat4bNetwork {
    private Combat4bNetwork() {
    }

    public static void init() {
        NetworkManager.registerS2C(PlayerStatusPayload.TYPE, PlayerStatusPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientStaminaData.update(payload)));

        NetworkManager.registerC2S(DodgePayload.TYPE, DodgePayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        PlayerCombat.dodge(player, payload.forward(), payload.strafe());
                    }
                }));
    }
}
