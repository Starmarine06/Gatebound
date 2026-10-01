package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the class the player picked. The server validates it. */
public record ChooseClassPayload(String classId) implements CustomPacketPayload {
    public static final Type<ChooseClassPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "choose_class"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseClassPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ChooseClassPayload::classId, ChooseClassPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
