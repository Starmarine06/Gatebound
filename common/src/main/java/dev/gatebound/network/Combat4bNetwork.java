package dev.gatebound.network;

import dev.gatebound.compat.Compat;
import dev.gatebound.combat.PlayerCombat;
import dev.gatebound.data.ClientStaminaData;
import net.minecraft.server.level.ServerPlayer;

/** Payload registration for dodge, stamina and player posture. */
public final class Combat4bNetwork {
    /** Static holder; not instantiable. */
    private Combat4bNetwork() {
    }

    /** Registers the combat packets. */
    public static void init() {
        Compat.registerS2C(PlayerStatusPayload.TYPE, PlayerStatusPayload.CODEC, (payload, context) ->
                context.queue(() -> ClientStaminaData.update(payload)));

        Compat.registerC2S(DodgePayload.TYPE, DodgePayload.CODEC, (payload, context) ->
                context.queue(() -> {
                    if (context.getPlayer() instanceof ServerPlayer player) {
                        PlayerCombat.dodge(player, payload.forward(), payload.strafe());
                    }
                }));
    }
}
