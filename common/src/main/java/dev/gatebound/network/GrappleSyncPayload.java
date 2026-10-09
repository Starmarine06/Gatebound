package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/** Server to client: every anchored compound-bow grapple (who, where it is anchored, rope length), so each client draws the lead. */
public record GrappleSyncPayload(List<Line> lines) implements CustomPacketPayload {
    public record Line(UUID player, double x, double y, double z, double rope) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Line::player,
                ByteBufCodecs.DOUBLE, Line::x,
                ByteBufCodecs.DOUBLE, Line::y,
                ByteBufCodecs.DOUBLE, Line::z,
                ByteBufCodecs.DOUBLE, Line::rope,
                Line::new);
    }

    public static final Type<GrappleSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "grapple_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GrappleSyncPayload> CODEC = StreamCodec.composite(
            Line.CODEC.apply(ByteBufCodecs.list()), GrappleSyncPayload::lines,
            GrappleSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
