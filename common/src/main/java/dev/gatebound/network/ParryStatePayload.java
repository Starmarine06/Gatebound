package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: a parry attempt just started, so the HUD can show the window and cooldown. */
public record ParryStatePayload(int windowTicks, int cooldownTicks) implements CustomPacketPayload {
    public static final Type<ParryStatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "parry_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ParryStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ParryStatePayload::windowTicks,
            ByteBufCodecs.VAR_INT, ParryStatePayload::cooldownTicks,
            ParryStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
