package dev.gatebound.echo;

import dev.gatebound.data.PlayerData;
import dev.gatebound.data.Stat;
import dev.gatebound.skill.ManaState;
import dev.gatebound.skill.SkillTargeting;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** Summoner skills that act on the caster's echoes: Rally, Command Strike and Empower. */
public final class EchoSkills {
    public static final int RALLY_COST = 10;
    public static final int COMMAND_COST = 14;
    public static final int EMPOWER_COST = 20;

    /** Echoes farther than this from the caster are not affected by Command Strike and Empower. */
    private static final double RANGE = 40;
    private static final int COMMAND_TICKS = 20 * 6;
    private static final int EMPOWER_TICKS = 20 * 20;

    private EchoSkills() {
    }

    private static boolean none(ServerPlayer player, int cost, List<EchoEntity> echoes) {
        if (!echoes.isEmpty()) {
            return false;
        }
        ManaState.add(player, cost);
        player.sendOverlayMessage(Component.literal("You have no echoes out. Press R to summon them.")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    /** Every echo returns to the caster, drops its fight and runs faster for a moment. */
    public static boolean rally(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, RALLY_COST, echoes)) {
            return true;
        }
        for (EchoEntity e : echoes) {
            if (e.isVehicle()) {
                continue;
            }
            e.setTarget(null);
            e.commandUntil = 0;
            e.seekFound = null;
            level.sendParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 0.8, e.getZ(), 12, 0.3, 0.5, 0.3, 0.1);
            EchoManager.teleportTo(e, player);
            level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 0.8, e.getZ(), 8, 0.3, 0.5, 0.3, 0.05);
            e.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
        }
        level.playSound(null, player.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1.0f, 1.3f);
        player.sendOverlayMessage(Component.literal("Rally! " + echoes.size() + " echoes return to your side.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Marks what you are looking at: every echo in range attacks it with bonus damage for a few seconds. */
    public static boolean commandStrike(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, COMMAND_COST, echoes)) {
            return true;
        }
        LivingEntity mark = null;
        for (LivingEntity hit : SkillTargeting.ray(player, level, 40, 8, ParticleTypes.WITCH)) {
            if (EchoBrain.validTarget(echoes.get(0), hit, player)) {
                mark = hit;
                break;
            }
        }
        if (mark == null) {
            ManaState.add(player, COMMAND_COST);
            player.sendOverlayMessage(Component.literal("Look at an enemy to command your echoes to strike it.")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        long until = level.getGameTime() + COMMAND_TICKS;
        int sent = 0;
        for (EchoEntity e : echoes) {
            if (e.isVehicle() || e.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            e.commandTarget = mark.getUUID();
            e.commandUntil = until;
            e.setTarget(mark);
            sent++;
        }
        mark.addEffect(new MobEffectInstance(MobEffects.GLOWING, COMMAND_TICKS, 0, false, false));
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mark.getX(), mark.getY(0.5), mark.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
        level.playSound(null, player.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0f, 1.0f);
        player.sendOverlayMessage(Component.literal("Strike! " + sent + " echoes attack " + mark.getName().getString() + ".")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Echoes in range deal more damage and take less for twenty seconds. The bonus grows with Intellect. */
    public static boolean empower(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, EMPOWER_COST, echoes)) {
            return true;
        }
        double mult = 1 + Math.min(0.8, 0.3 + 0.01 * data.get(Stat.INTELLECT));
        long until = level.getGameTime() + EMPOWER_TICKS;
        int boosted = 0;
        for (EchoEntity e : echoes) {
            if (e.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            e.empowerUntil = until;
            e.empowerMult = mult;
            e.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EMPOWER_TICKS, 0));
            level.sendParticles(ParticleTypes.ENCHANT, e.getX(), e.getY() + 1, e.getZ(), 25, 0.4, 0.7, 0.4, 0.5);
            boosted++;
        }
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
        player.sendOverlayMessage(Component.literal("Empowered " + boosted + " echoes: +" + Math.round((mult - 1) * 100)
                + "% damage, extra toughness for 20 s.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }
}
