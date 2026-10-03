package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: the player's stamina and posture, for the HUD. */
public record PlayerStatusPayload(int stamina, int maxStamina, int posture, int maxPosture, boolean staggered, int dodgeCooldownTicks)
        implements CustomPacketPayload {
    public static final Type<PlayerStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "player_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerStatusPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayerStatusPayload::stamina,
            ByteBufCodecs.VAR_INT, PlayerStatusPayload::maxStamina,
            ByteBufCodecs.VAR_INT, PlayerStatusPayload::posture,
            ByteBufCodecs.VAR_INT, PlayerStatusPayload::maxPosture,
            ByteBufCodecs.BOOL, PlayerStatusPayload::staggered,
            ByteBufCodecs.VAR_INT, PlayerStatusPayload::dodgeCooldownTicks,
            PlayerStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
