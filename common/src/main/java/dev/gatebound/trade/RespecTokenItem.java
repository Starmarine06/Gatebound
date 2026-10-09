package dev.gatebound.trade;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Progression;
import dev.gatebound.data.Stat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Refunds spent stat points. Class, elements, class bonuses and tome bonuses are kept. */
public class RespecTokenItem extends Item {
    public RespecTokenItem(Properties properties) {
        super(properties);
    }

    /** Returns the number of points refunded. */
    public static int respec(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        GateboundClass cls = GateboundClass.byId(data.classId);
        int refunded = 0;
        for (Stat s : Stat.values()) {
            int floor = Stat.BASE_VALUE + (cls == null ? 0 : cls.bonus(s)) + TradeStore.tomesRead(player, s);
            int spent = Math.max(0, data.get(s) - floor);
            refunded += spent;
            data.stats[s.ordinal()] = Math.min(data.get(s), floor);
        }
        data.statPoints += refunded;
        Progression.save(player, data);
        return refunded;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        int refunded = respec(sp);
        if (refunded <= 0) {
            Compat.overlay(sp, Component.literal("You have no spent stat points to refund.").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        if (!player.hasInfiniteMaterials()) {
            player.getItemInHand(hand).shrink(1);
        }
        level.playSound(null, sp.blockPosition(), GateboundSounds.custom("item.respec_token"), SoundSource.PLAYERS, 1f, 1f);
        sp.sendSystemMessage(Component.literal("[Gatebound] Your stats unravel. " + refunded
                + " points refunded; spend them wisely.").withStyle(ChatFormatting.GOLD));
        return InteractionResult.SUCCESS;
    }
}
