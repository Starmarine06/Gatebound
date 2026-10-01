package dev.gatebound.network;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: a skill was cast and is now on cooldown, so the HUD can show it. */
public record SkillCooldownPayload(String skillId, int ticks) implements CustomPacketPayload {
    public static final Type<SkillCooldownPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "skill_cooldown"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkillCooldownPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SkillCooldownPayload::skillId,
            ByteBufCodecs.VAR_INT, SkillCooldownPayload::ticks,
            SkillCooldownPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
