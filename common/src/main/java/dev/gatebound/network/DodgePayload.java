package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: dodge key pressed. forward and strafe are -1, 0 or 1 (strafe positive = left). */
public record DodgePayload(int forward, int strafe) implements CustomPacketPayload {
    public static final Type<DodgePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "dodge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DodgePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DodgePayload::forward,
            ByteBufCodecs.VAR_INT, DodgePayload::strafe,
            DodgePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
