package dev.gatebound.skill;

import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Mana does not trickle back quickly: it returns in half a day, by sleeping through the night, or by drinking one of these.
 * Right-click to drink; it restores a large part of the mana pool, for any class.
 */
public class ManaPotionItem extends Item {
    /** Share of the maximum mana restored. */
    public static final double FRACTION = 0.6;
    /** Always restores at least this much, so it is useful early on. */
    public static final int MINIMUM = 30;

    public ManaPotionItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        PlayerData data = PlayerDataStore.of(sp);
        double max = ManaState.max(data);
        if (ManaState.get(sp) >= max) {
            sp.sendOverlayMessage(Component.literal("Your mana is already full.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        double before = ManaState.get(sp);
        ManaState.add(sp, Math.max(MINIMUM, max * FRACTION));
        PlayerDataStore.markDirty(sp);
        SkillHandler.sendMana(sp, true);
        ItemStack stack = player.getItemInHand(hand);
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        server.playSound(null, sp.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 1f, 1f);
        server.sendParticles(ParticleTypes.ENCHANT, sp.getX(), sp.getY() + 1.2, sp.getZ(), 20, 0.4, 0.6, 0.4, 0.4);
        sp.sendOverlayMessage(Component.literal("+" + Math.round(ManaState.get(sp) - before) + " mana").withStyle(ChatFormatting.AQUA));
        return InteractionResult.SUCCESS;
    }
}
