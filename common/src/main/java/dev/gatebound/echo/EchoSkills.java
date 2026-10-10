package dev.gatebound.echo;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
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

import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/** Summoner skills that act on the caster's echoes: Rally, Command Strike and Empower. */
public final class EchoSkills {
    public static final int RALLY_COST = 10;
    public static final int COMMAND_COST = 14;
    public static final int EMPOWER_COST = 20;
    public static final int LINK_COST = 18;
    public static final int SACRIFICE_COST = 10;
    public static final int SWAP_COST = 10;
    public static final int CHAINS_COST = 16;
    public static final int LEGION_COST = 40;
    public static final int STORM_COST = 36;
    public static final int ASCENSION_COST = 55;
    public static final int DOMAIN_COST = 80;

    /** Echoes farther than this from the caster are not affected by Command Strike and Empower. */
    private static final double RANGE = 40;
    private static final int COMMAND_TICKS = 20 * 6;
    private static final int EMPOWER_TICKS = 20 * 20;

    /** Static holder; not instantiable. */
    private EchoSkills() {
    }

    /** Refunds the cost and tells the player when they have no echoes out. */
    private static boolean none(ServerPlayer player, int cost, List<EchoEntity> echoes) {
        if (!echoes.isEmpty()) {
            return false;
        }
        ManaState.add(player, cost);
        Compat.overlay(player, Component.literal("You have no echoes out. Press R to summon them.")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    /** Every echo returns to the caster, drops its fight and runs faster for a moment. */
    public static boolean rally(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, RALLY_COST, echoes)) {
            return true;
        }
        dev.gatebound.skill.fx.SummonerFx.rallyWave(player);
        int shown = 0;
        for (EchoEntity e : echoes) {
            if (e.isVehicle()) {
                continue;
            }
            e.setTarget(null);
            e.commandUntil = 0;
            e.commandTarget = null;
            e.seekFound = null;
            if (shown++ < 8) {
                dev.gatebound.skill.fx.SummonerFx.rallyFrom(level, e);
            }
            EchoManager.teleportTo(e, player);
            if (shown <= 8) {
                dev.gatebound.skill.fx.SummonerFx.rallyTo(player, e);
            }
            e.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
        }
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.EVOKER_PREPARE_SUMMON), SoundSource.PLAYERS, 1.0f, 1.3f);
        Compat.overlay(player, Component.literal("Rally! " + echoes.size() + " echoes return to your side.")
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
            Compat.overlay(player, Component.literal("Look at an enemy to command your echoes to strike it.")
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
        dev.gatebound.skill.fx.SummonerFx.command(player, mark, echoes, COMMAND_TICKS);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.EVOKER_CAST_SPELL), SoundSource.PLAYERS, 1.0f, 1.0f);
        Compat.overlay(player, Component.literal("Strike! " + sent + " echoes attack " + mark.getName().getString() + ".")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Echoes in range deal more damage and take less for twenty seconds. The bonus grows with Intellect. */
    public static boolean empower(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, EMPOWER_COST, echoes)) {
            return true;
        }
        double mult = 1 + Math.min(1.0, 0.3 + 0.004 * data.level + 0.006 * data.get(Stat.INTELLECT));
        long until = level.getGameTime() + EMPOWER_TICKS;
        int boosted = 0;
        for (EchoEntity e : echoes) {
            if (e.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            e.empowerUntil = until;
            e.empowerMult = mult;
            e.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EMPOWER_TICKS, 0));
            if (boosted < 8) {
                dev.gatebound.skill.fx.SummonerFx.empower(e, EMPOWER_TICKS);
            }
            boosted++;
        }
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.BEACON_POWER_SELECT), SoundSource.PLAYERS, 1.0f, 1.2f);
        Compat.overlay(player, Component.literal("Empowered " + boosted + " echoes: +" + Math.round((mult - 1) * 100)
                + "% damage, extra toughness for 20 s.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    // ---------------------------------------------------------------- higher skills

    /** The caster's own echo under the crosshair, or null. */
    private static EchoEntity lookedAtEcho(ServerPlayer player, ServerLevel level) {
        for (LivingEntity hit : SkillTargeting.ray(player, level, 40, 6, ParticleTypes.WITCH)) {
            if (hit instanceof EchoEntity echo && EchoManager.isEcho(echo)) {
                EchoManager.Info info = EchoManager.info(echo);
                if (info != null && info.owner.equals(player.getUUID())) {
                    return echo;
                }
            }
        }
        return null;
    }

    /** Refunds the cost and shows a hint when a needed echo is missing. */
    private static boolean needEcho(ServerPlayer player, int cost, String hint) {
        ManaState.add(player, cost);
        Compat.overlay(player, Component.literal(hint).withStyle(ChatFormatting.GRAY));
        return true; // the hint replaces the generic no-target message
    }

    /** Echoes in range heal themselves (and slightly you) for part of the damage they deal for 15 s. */
    public static boolean soulLink(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, LINK_COST, echoes)) {
            return true;
        }
        double fraction = Math.min(0.6, 0.2 + 0.003 * data.level + 0.002 * data.get(Stat.INTELLECT));
        long until = level.getGameTime() + 20 * 15;
        int linked = 0;
        for (EchoEntity e : echoes) {
            if (e.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            e.linkUntil = until;
            e.linkFraction = fraction;
            linked++;
        }
        dev.gatebound.skill.fx.SummonerFx.link(player, echoes, 20 * 15);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.SOUL_ESCAPE.value()), SoundSource.PLAYERS, 1.0f, 0.8f);
        Compat.overlay(player, Component.literal("Soul Link: " + linked + " echoes drain " + Math.round(fraction * 100)
                + "% of the damage they deal as health for 15 s.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Consumes the echo you look at for a burst of health and mana. It returns the next time you summon. */
    public static boolean sacrifice(ServerPlayer player, ServerLevel level, PlayerData data) {
        EchoEntity echo = lookedAtEcho(player, level);
        if (echo == null || echo.isVehicle()) {
            return needEcho(player, SACRIFICE_COST, "Look at one of your echoes to sacrifice it.");
        }
        float heal = Math.min(player.getMaxHealth() * 0.6f, echo.getMaxHealth() * 0.5f + 4);
        double mana = 20 + 0.5 * data.level + 0.3 * data.get(Stat.INTELLECT);
        player.heal(heal);
        ManaState.add(player, mana);
        dev.gatebound.skill.fx.SummonerFx.sacrifice(player, echo);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.SOUL_ESCAPE.value()), SoundSource.PLAYERS, 1.2f, 0.6f);
        Compat.overlay(player, Component.literal("You consume " + echo.mobName() + ": +" + Math.round(heal) + " health, +"
                + Math.round(mana) + " mana. It returns when you summon again.").withStyle(ChatFormatting.LIGHT_PURPLE));
        EchoManager.consume(echo);
        return true;
    }

    /** Swap places with the echo you look at. */
    public static boolean soulSwap(ServerPlayer player, ServerLevel level, PlayerData data) {
        EchoEntity echo = lookedAtEcho(player, level);
        if (echo == null) {
            echo = EchoManager.selectedEcho(player); // no echo in sight: swap with the selected one, however far away it is
        }
        if (echo == null || echo.isVehicle() || echo.level() != level) {
            return needEcho(player, SWAP_COST, "Look at one of your echoes, or select one, to swap places with it.");
        }
        Vec3 mine = player.position();
        Vec3 theirs = echo.position();
        dev.gatebound.skill.fx.SummonerFx.swap(level, mine, theirs);
        echo.getNavigation().stop();
        echo.teleportTo(level, mine.x, mine.y, mine.z, Set.of(), echo.getYRot(), 0, false);
        player.teleportTo(level, theirs.x, theirs.y, theirs.z, Set.of(), player.getYRot(), player.getXRot(), false);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.ENDERMAN_TELEPORT), SoundSource.PLAYERS, 1.0f, 1.2f);
        return true;
    }

    /** Roots the enemy you look at, and the enemies around it, with spectral chains. Lasts longer with level. */
    public static boolean bindChains(ServerPlayer player, ServerLevel level, PlayerData data) {
        LivingEntity mark = null;
        for (LivingEntity h : SkillTargeting.ray(player, level, 30, 4, ParticleTypes.SOUL_FIRE_FLAME)) {
            if (!EchoManager.isEcho(h) && !(h instanceof dev.gatebound.trade.GateboundNpc)) {
                mark = h;
                break;
            }
        }
        if (mark == null) {
            ManaState.add(player, CHAINS_COST);
            Compat.overlay(player, Component.literal("Look at an enemy to bind it.").withStyle(ChatFormatting.GRAY));
            return true;
        }
        final LivingEntity center = mark;
        int ticks = (int) ((3 + data.level / 20.0) * 20);
        int bound = 0;
        List<LivingEntity> boundList = new java.util.ArrayList<>();
        AABB area = mark.getBoundingBox().inflate(4);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area,
                x -> x.isAlive() && (x == center || x instanceof Enemy) && !EchoManager.isEcho(x))) {
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 6));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1));
            boundList.add(e);
            bound++;
        }
        dev.gatebound.skill.fx.SummonerFx.chains(player, mark, boundList, ticks);
        level.playSound(null, mark.blockPosition(), GateboundSounds.of(SoundEvents.CHAIN_PLACE), SoundSource.PLAYERS, 1.2f, 0.7f);
        Compat.overlay(player, Component.literal("Bound " + bound + " enemies for " + ticks / 20 + " s.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Spectral copies of every echo on your roster fight beside you for a while. They use no slots. */
    public static boolean legionCall(ServerPlayer player, ServerLevel level, PlayerData data) {
        if (data.echo.entries.isEmpty()) {
            ManaState.add(player, LEGION_COST);
            Compat.overlay(player, Component.literal("Your roster is empty: bind some echoes first.").withStyle(ChatFormatting.GRAY));
            return true;
        }
        int seconds = 15 + data.level / 5;
        dev.gatebound.skill.fx.SummonerFx.legion(player);
        int n = EchoManager.spawnLegion(player, data, seconds * 20);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.EVOKER_PREPARE_SUMMON), SoundSource.PLAYERS, 1.2f, 0.7f);
        Compat.overlay(player, Component.literal("Legion Call: " + n + " spectral echoes fight for " + seconds + " s.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Every echo unleashes a heavy burst on its nearest enemy. Scales with each echo's own strength and your level. */
    public static boolean soulStorm(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, STORM_COST, echoes)) {
            return true;
        }
        double power = 2 + data.level * 0.04 + data.get(Stat.INTELLECT) * 0.02;
        int hitsDone = 0;
        dev.gatebound.skill.fx.SummonerFx.stormStart(player);
        for (EchoEntity e : echoes) {
            LivingEntity best = null;
            double bestDist = 18 * 18;
            for (LivingEntity m : level.getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(18),
                    m -> EchoBrain.validTarget(e, m, player) && m instanceof Enemy)) {
                double d = e.distanceToSqr(m);
                if (d < bestDist) {
                    bestDist = d;
                    best = m;
                }
            }
            if (best == null) {
                continue;
            }
            if (hitsDone < 8) {
                dev.gatebound.skill.fx.SummonerFx.stormBolt(level, e, best);
            }
            float dealt = (float) (e.attackDamage() * power);
            best.hurtServer(level, level.damageSources().mobAttack(e), dealt);
            EchoBrain.soulLink(e, level, dealt);
            hitsDone++;
        }
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.WITHER_SHOOT), SoundSource.PLAYERS, 0.9f, 1.4f);
        Compat.overlay(player, Component.literal("Soul Storm: " + hitsDone + " echoes strike.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** A strong, short transformation: echoes in range hit much harder, regenerate and gain an absorption shield. */
    public static boolean ascension(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        if (none(player, ASCENSION_COST, echoes)) {
            return true;
        }
        double mult = Math.min(2.8, 1.8 + 0.01 * data.level);
        int ticks = 20 * 30;
        long until = level.getGameTime() + ticks;
        int n = 0;
        for (EchoEntity e : echoes) {
            if (e.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            e.empowerUntil = until;
            e.empowerMult = Math.max(e.empowerMult, mult);
            e.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
            e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 1));
            e.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 0));
            e.setAbsorptionAmount(e.getMaxHealth() * 0.5f);
            if (n < 8) {
                dev.gatebound.skill.fx.SummonerFx.ascension(e, ticks);
            }
            n++;
        }
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.BEACON_ACTIVATE), SoundSource.PLAYERS, 1.2f, 1.0f);
        Compat.overlay(player, Component.literal("Ascension: " + n + " echoes surge with power (x" + String.format("%.1f", mult)
                + " damage) for 30 s.").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** The ultimate. For 15 s your echoes cannot be hurt and strike with triple force; enemies near you are slowed and weakened. */
    public static boolean sovereignsDomain(ServerPlayer player, ServerLevel level, PlayerData data) {
        List<EchoEntity> echoes = EchoManager.echoesOf(player);
        int ticks = 20 * 15;
        long until = level.getGameTime() + ticks;
        int shown = 0;
        for (EchoEntity e : echoes) {
            e.invulnUntil = until;
            e.empowerUntil = until;
            e.empowerMult = Math.max(e.empowerMult, 3.0);
            e.addEffect(new MobEffectInstance(MobEffects.SPEED, ticks, 1));
            if (shown++ < 8) {
                dev.gatebound.skill.fx.SummonerFx.domainEcho(e, ticks);
            }
        }
        for (LivingEntity m : SkillTargeting.enemiesAround(player, level, 20)) {
            if (!EchoManager.isEcho(m)) {
                m.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 2));
                m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1));
            }
        }
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 1));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 1));
        dev.gatebound.skill.fx.SummonerFx.domain(player, ticks);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.ENDER_DRAGON_GROWL), SoundSource.PLAYERS, 0.8f, 1.5f);
        Compat.overlay(player, Component.literal("SOVEREIGN'S DOMAIN: your echoes are untouchable for 15 s.")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        return true;
    }
}
