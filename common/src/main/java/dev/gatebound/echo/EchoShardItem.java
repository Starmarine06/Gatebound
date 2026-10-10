package dev.gatebound.echo;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Using an Echo Shard permanently adds one Echo slot. Only Summoners can use it. */
public class EchoShardItem extends Item {
    public EchoShardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        PlayerData data = PlayerDataStore.of(sp);
        if (!EchoManager.isSummoner(data)) {
            Compat.overlay(sp, Component.literal("Only Summoners can bind more echoes.").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        if (data.echo.bonusSlots >= EchoConfig.maxBonusSlots) {
            Compat.overlay(sp, Component.literal("You can hold no more echo shards (" + data.echo.bonusSlots + "/"
                    + EchoConfig.maxBonusSlots + ").").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        data.echo.bonusSlots++;
        PlayerDataStore.markDirty(sp);
        ItemStack stack = player.getItemInHand(hand);
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        level.playSound(null, sp.blockPosition(), GateboundSounds.custom("item.echo_shard"), SoundSource.PLAYERS, 1f, 1f);
        sp.sendSystemMessage(Component.literal("[Gatebound] The shard dissolves into you. You can now hold "
                + EchoManager.slots(data) + " echoes.").withStyle(ChatFormatting.LIGHT_PURPLE));
        EchoManager.sendRoster(sp, false);
        return InteractionResult.SUCCESS;
    }
}
