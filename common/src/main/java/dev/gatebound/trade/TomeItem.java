package dev.gatebound.trade;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Progression;
import dev.gatebound.data.Stat;
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

/** Reading a tome permanently raises one base stat by 1, up to a per-stat limit. */
public class TomeItem extends Item {
    private final Stat stat;

    public TomeItem(Stat stat, Properties properties) {
        super(properties);
        this.stat = stat;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        int read = TradeStore.tomesRead(sp, stat);
        if (read >= TradeConfig.maxTomesPerStat) {
            Compat.overlay(sp, Component.literal("You can learn no more from this tome (" + read + "/"
                    + TradeConfig.maxTomesPerStat + ").").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        PlayerData data = PlayerDataStore.of(sp);
        data.stats[stat.ordinal()]++;
        TradeStore.addTome(sp, stat);
        Progression.save(sp, data);
        ItemStack stack = player.getItemInHand(hand);
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        level.playSound(null, sp.blockPosition(), GateboundSounds.custom("item.tome"), SoundSource.PLAYERS, 1f, 1f);
        sp.sendSystemMessage(Component.literal("[Gatebound] The tome crumbles. Your " + stat.id() + " is now "
                + data.get(stat) + " (" + (read + 1) + "/" + TradeConfig.maxTomesPerStat + " tomes read).")
                .withStyle(ChatFormatting.AQUA));
        return InteractionResult.SUCCESS;
    }
}
