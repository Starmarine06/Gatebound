package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to the attacking player: posture of the mob they just hit or parried, for the HUD target bar. */
public record PostureSyncPayload(int entityId, int value, int max, boolean staggered) implements CustomPacketPayload {
    public static final Type<PostureSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "posture_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PostureSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PostureSyncPayload::entityId,
            ByteBufCodecs.VAR_INT, PostureSyncPayload::value,
            ByteBufCodecs.VAR_INT, PostureSyncPayload::max,
            ByteBufCodecs.BOOL, PostureSyncPayload::staggered,
            PostureSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
