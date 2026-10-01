package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: cast the skill in this 0-based slot. The server checks class, level, mana and cooldown. */
public record UseSkillPayload(int slot) implements CustomPacketPayload {
    public static final Type<UseSkillPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "use_skill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UseSkillPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, UseSkillPayload::slot, UseSkillPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
