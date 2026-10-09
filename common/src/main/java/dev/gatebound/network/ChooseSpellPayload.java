package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the spell a multi-element Mage picked. The server checks it is one of the offered options. */
public record ChooseSpellPayload(String skillId) implements CustomPacketPayload {
    public static final Type<ChooseSpellPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "choose_spell"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseSpellPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ChooseSpellPayload::skillId, ChooseSpellPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
