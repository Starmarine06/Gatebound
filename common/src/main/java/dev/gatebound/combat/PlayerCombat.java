package dev.gatebound.combat;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import dev.gatebound.network.PlayerStatusPayload;
import dev.gatebound.skill.SkillHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Player stamina, dodge with i-frames, and player posture. In-memory only; resets on restart. */
public final class PlayerCombat {
    private static final class Info {
        double stamina = -1;
        int lastSpendTick;
        double posture;
        int lastPostureTick;
        int staggeredUntil;
        int iframeUntil;
        int dodgeReadyAt;
        boolean perfectDone;
        int dodgeCooldownToSend;
        int[] lastSent;
    }

    private static final Map<UUID, Info> INFO = new HashMap<>();

    /** Static holder; not instantiable. */
    private PlayerCombat() {
    }

    /** Registers the stamina/parry/dodge hooks. */
    public static void init() {
        // Must register before CombatHandler so i-frames and unauthorised mob hits are resolved before parry logic.
        EntityEvent.LIVING_HURT.register(PlayerCombat::onHurt);
        EntityEvent.ADD.register((entity, level) -> {
            TelegraphGoal.install(entity, level);
            return EventResult.pass();
        });
        TickEvent.SERVER_POST.register(server -> {
            int now = server.getTickCount();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isAlive()) {
                    tick(player, now);
                }
            }
        });
        PlayerEvent.PLAYER_JOIN.register(player -> send(player, info(player), true));
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> {
            INFO.remove(player.getUUID());
            send(player, info(player), true);
        });
        PlayerEvent.PLAYER_QUIT.register(player -> INFO.remove(player.getUUID()));
    }

    /** Per-player combat info (created on demand). */
    private static Info info(ServerPlayer player) {
        Info i = INFO.computeIfAbsent(player.getUUID(), id -> new Info());
        if (i.stamina < 0) {
            i.stamina = maxStamina(player);
        }
        return i;
    }

    /** Player's maximum stamina. */
    public static int maxStamina(ServerPlayer player) {
        var data = PlayerDataStore.of(player);
        return 40 + 3 * data.get(Stat.AGILITY) + 2 * data.get(Stat.VITALITY);
    }

    /** Player's current stamina. */
    public static double stamina(ServerPlayer player) {
        return info(player).stamina;
    }

    /** Sets the player's stamina (clamped). */
    public static void setStamina(ServerPlayer player, double value) {
        Info i = info(player);
        i.stamina = Math.max(0, Math.min(maxStamina(player), value));
        send(player, i, true);
    }

    /** Spends stamina (never below zero) and restarts the regeneration delay. */
    public static void spendStamina(ServerPlayer player, double amount) {
        Info i = info(player);
        i.stamina = Math.max(0, i.stamina - amount);
        i.lastSpendTick = player.level().getServer().getTickCount();
        send(player, i, true);
    }

    /** True if the player is staggered. */
    public static boolean isStaggered(ServerPlayer player) {
        return info(player).staggeredUntil > player.level().getServer().getTickCount();
    }

    /** True if the player has invulnerability frames. */
    public static boolean hasIFrames(ServerPlayer player) {
        return info(player).iframeUntil > player.level().getServer().getTickCount();
    }

    /** Grants invulnerability to attacks for a few ticks without a stamina cost or perfect-dodge reward (Shadow Step). */
    public static void grantIFrames(ServerPlayer player, int ticks) {
        Info i = info(player);
        i.iframeUntil = Math.max(i.iframeUntil, player.level().getServer().getTickCount() + ticks);
        i.perfectDone = true;
    }

    /** Per-tick stamina regeneration and state upkeep. */
    private static void tick(ServerPlayer player, int now) {
        Info i = info(player);
        double max = maxStamina(player);
        if (now - i.lastSpendTick > CombatConfig.staminaRegenDelay && i.stamina < max) {
            i.stamina = Math.min(max, i.stamina + CombatConfig.staminaRegenPerTick);
        }
        if (i.stamina > max) {
            i.stamina = max;
        }
        int idle = now - i.lastPostureTick - CombatConfig.playerPostureRecoveryDelay;
        if (idle > 0 && i.posture > 0) {
            i.posture = Math.max(0, i.posture - CombatConfig.playerPostureRecoveryPerTick);
        }
        if (now % 4 == 0) {
            send(player, i, false);
        }
    }

    /** Syncs stamina/state to the client when changed or forced. */
    private static void send(ServerPlayer player, Info i, boolean force) {
        int now = player.level().getServer().getTickCount();
        int[] snap = {(int) i.stamina, maxStamina(player), (int) i.posture, (int) CombatConfig.playerPostureMax,
                i.staggeredUntil > now ? 1 : 0};
        if (!force && i.dodgeCooldownToSend == 0 && i.lastSent != null && java.util.Arrays.equals(snap, i.lastSent)) {
            return;
        }
        i.lastSent = snap;
        NetworkManager.sendToPlayer(player, new PlayerStatusPayload(snap[0], snap[1], snap[2], snap[3], snap[4] == 1, i.dodgeCooldownToSend));
        i.dodgeCooldownToSend = 0;
    }

    /** Starts a dodge. forward / strafe are the movement keys held (strafe positive = left). */
    public static void dodge(ServerPlayer player, int forward, int strafe) {
        if (!player.isAlive() || player.isSpectator()) {
            return;
        }
        Info i = info(player);
        int now = player.level().getServer().getTickCount();
        if (now < i.dodgeReadyAt) {
            return;
        }
        if (i.staggeredUntil > now) {
            Compat.overlay(player, Component.translatable("combat.gatebound.staggered").withStyle(ChatFormatting.RED));
            return;
        }
        if (i.stamina < CombatConfig.dodgeCost) {
            Compat.overlay(player, Component.translatable("combat.gatebound.no_stamina").withStyle(ChatFormatting.GRAY));
            return;
        }
        i.stamina -= CombatConfig.dodgeCost;
        i.lastSpendTick = now;
        i.iframeUntil = now + CombatConfig.iframeTicks;
        i.perfectDone = false;
        i.dodgeReadyAt = now + CombatConfig.dodgeCooldownTicks;
        i.dodgeCooldownToSend = CombatConfig.dodgeCooldownTicks;

        if (forward == 0 && strafe == 0) {
            forward = -1;
        }
        double yaw = Math.toRadians(player.getYRot());
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 dir = fwd.scale(forward).add(left.scale(strafe));
        if (dir.lengthSqr() < 1.0E-4) {
            dir = fwd.scale(-1);
        }
        dir = dir.normalize();
        player.setDeltaMovement(dir.x * CombatConfig.dodgeSpeed, player.onGround() ? 0.12 : player.getDeltaMovement().y, dir.z * CombatConfig.dodgeSpeed);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(), 8, 0.25, 0.05, 0.25, 0.02);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), SoundSource.PLAYERS, 0.5f, 1.9f);
        send(player, i, true);
    }

    /** Adds posture to the player; a full bar staggers them briefly. */
    public static void addPosture(ServerPlayer player, double amount) {
        Info i = info(player);
        int now = player.level().getServer().getTickCount();
        if (i.staggeredUntil > now) {
            return;
        }
        i.posture += amount;
        i.lastPostureTick = now;
        if (i.posture >= CombatConfig.playerPostureMax) {
            i.posture = 0;
            i.staggeredUntil = now + CombatConfig.playerStaggerTicks;
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CombatConfig.playerStaggerTicks, 3));
            ServerLevel level = (ServerLevel) player.level();
            level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY(0.8), player.getZ(), 16, 0.3, 0.3, 0.3, 0.2);
            level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.ANVIL_LAND), SoundSource.PLAYERS, 0.5f, 1.4f);
            Compat.overlay(player, Component.translatable("combat.gatebound.posture_broken").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        send(player, i, true);
    }

    /** Hurt hook: applies parry, dodge and stamina rules. */
    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0 || !(victim instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return EventResult.pass();
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return EventResult.pass();
        }
        // Mobs with a wind-up may only land hits through their telegraphed strike.
        if (CombatConfig.telegraphEnabled && source.getEntity() instanceof Mob mob
                && source.getDirectEntity() == mob && TelegraphGoal.hasGoal(mob) && !TelegraphGoal.isStriking(mob)) {
            return EventResult.interruptFalse();
        }
        Info i = info(player);
        int now = level.getServer().getTickCount();
        boolean attack = source.getEntity() != null || source.getDirectEntity() != null;
        if (attack && i.iframeUntil > now) {
            if (!i.perfectDone) {
                i.perfectDone = true;
                perfectDodge(player, i, level);
            }
            return EventResult.interruptFalse();
        }
        return EventResult.pass();
    }

    /** Perfect dodge reward: refunds stamina and cuts skill cooldowns. */
    private static void perfectDodge(ServerPlayer player, Info i, ServerLevel level) {
        i.stamina = Math.min(maxStamina(player), i.stamina + CombatConfig.perfectDodgeRefund);
        i.dodgeReadyAt = 0;
        SkillHandler.reduceCooldowns(player, CombatConfig.perfectDodgeCooldownCut);
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY(0.6), player.getZ(), 14, 0.3, 0.4, 0.3, 0.05);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.AMETHYST_BLOCK_CHIME), SoundSource.PLAYERS, 1.0f, 1.6f);
        Compat.overlay(player, Component.translatable("combat.gatebound.perfect_dodge").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        send(player, i, true);
    }
}
