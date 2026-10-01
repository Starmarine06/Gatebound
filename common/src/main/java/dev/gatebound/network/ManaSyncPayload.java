package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: current and maximum mana. */
public record ManaSyncPayload(int mana, int max) implements CustomPacketPayload {
    public static final Type<ManaSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "mana_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ManaSyncPayload::mana,
            ByteBufCodecs.VAR_INT, ManaSyncPayload::max,
            ManaSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
