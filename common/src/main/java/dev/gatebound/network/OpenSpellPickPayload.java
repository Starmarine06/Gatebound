package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Server to client: open the spell choice screen with these skill ids as the options. */
public record OpenSpellPickPayload(List<String> options) implements CustomPacketPayload {
    public static final Type<OpenSpellPickPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "open_spell_pick"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSpellPickPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenSpellPickPayload::options, OpenSpellPickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
