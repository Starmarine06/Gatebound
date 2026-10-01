package dev.gatebound.combat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.ParryStatePayload;
import dev.gatebound.network.PostureSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Timed parry, posture and finisher logic. All judging happens on the server. */
public final class CombatHandler {
    /** Guards against the finisher's own damage re-entering the hurt event. */
    private static boolean applyingFinisher;

    private CombatHandler() {
    }

    public static void init() {
        EntityEvent.LIVING_HURT.register(CombatHandler::onHurt);
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            CombatState.forget(entity.getUUID());
            return EventResult.pass();
        });
        PlayerEvent.PLAYER_QUIT.register(player -> CombatState.forget(player.getUUID()));
    }

    /** Called when the parry key is pressed. */
    public static void pressParry(ServerPlayer player) {
        if (!player.isAlive()) {
            return;
        }
        int now = player.level().getServer().getTickCount();
        if (CombatState.startParry(player.getUUID(), now)) {
            NetworkManager.sendToPlayer(player, new ParryStatePayload(CombatConfig.parryWindowTicks, CombatConfig.parryCooldownTicks));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5f, 1.6f);
        }
    }

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0 || applyingFinisher || !(victim.level() instanceof ServerLevel level)) {
            return EventResult.pass();
        }
        int now = level.getServer().getTickCount();

        if (victim instanceof ServerPlayer player && source.getEntity() instanceof LivingEntity attacker) {
            return onPlayerHit(player, attacker, source, now, level);
        }
        if (source.getEntity() instanceof ServerPlayer player && victim instanceof Mob mob) {
            onMobHit(player, mob, source, now, level);
        }
        return EventResult.pass();
    }

    private static boolean isMelee(DamageSource source) {
        return source.getDirectEntity() != null && source.getDirectEntity() == source.getEntity()
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    private static EventResult onPlayerHit(ServerPlayer player, LivingEntity attacker, DamageSource source, int now, ServerLevel level) {
        if (!isMelee(source)) {
            return EventResult.pass();
        }
        boolean timed = CombatState.isParrying(player.getUUID(), now);
        boolean inFront = isInFront(player, attacker);

        if (!(timed && inFront)) {
            if (CombatState.debug(player.getUUID())) {
                int since = CombatState.ticksSinceParry(player.getUUID(), now);
                String why = since < 0 ? "no parry pressed yet"
                        : !inFront ? "attacker was not in front"
                        : "pressed " + since + " ticks before the hit (window is " + CombatConfig.parryWindowTicks + ")";
                player.sendOverlayMessage(Component.literal("Parry missed: " + why));
            }
            return EventResult.pass();
        }

        // Deflect: no damage, push the attacker back, build its posture, reward a counter.
        Vec3 away = attacker.position().subtract(player.position());
        Vec3 dir = new Vec3(away.x, 0, away.z).normalize();
        attacker.push(dir.x * 0.9, 0.2, dir.z * 0.9);
        attacker.needsSync = true;

        Vec3 mid = player.getEyePosition().add(attacker.getEyePosition()).scale(0.5);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mid.x, mid.y, mid.z, 14, 0.2, 0.2, 0.2, 0.15);
        level.sendParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 8, 0.2, 0.2, 0.2, 0.2);
        level.playSound(null, player.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.0f, 1.3f);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8f, 1.5f);

        player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, CombatConfig.counterTicks, 1));
        player.sendOverlayMessage(Component.literal("PARRY!").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));

        addPostureAndMaybeBreak(attacker, CombatConfig.parryPosture, now, level, player);
        return EventResult.interruptFalse();
    }

    private static void onMobHit(ServerPlayer player, Mob mob, DamageSource source, int now, ServerLevel level) {
        if (CombatState.isStaggered(mob.getUUID(), now)) {
            // Finisher: a heavy extra hit on a staggered mob, once.
            CombatState.clearStagger(mob.getUUID());
            CombatState.resetPosture(mob.getUUID());
            syncPosture(player, mob, 0, false);
            applyingFinisher = true;
            try {
                mob.hurtServer(level, level.damageSources().playerAttack(player), (float) (mob.getMaxHealth() * CombatConfig.finisherFraction));
            } finally {
                applyingFinisher = false;
            }
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, mob.getX(), mob.getY(0.5), mob.getZ(), 1, 0, 0, 0, 0);
            player.sendOverlayMessage(Component.literal("FINISHER!").withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
            return;
        }
        if (isMelee(source)) {
            addPostureAndMaybeBreak(mob, CombatConfig.hitPosture, now, level, player);
        }
    }

    private static void addPostureAndMaybeBreak(LivingEntity target, double amount, int now, ServerLevel level, ServerPlayer player) {
        double value = CombatState.addPosture(target.getUUID(), amount, now);
        syncPosture(player, target, value, false);
        if (CombatState.debug(player.getUUID())) {
            player.sendOverlayMessage(Component.literal("Posture " + (int) value + " / " + (int) CombatConfig.postureMax));
        }
        if (value >= CombatConfig.postureMax) {
            CombatState.stagger(target.getUUID(), now);
            CombatState.resetPosture(target.getUUID());
            syncPosture(player, target, CombatConfig.postureMax, true);
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CombatConfig.staggerTicks, 4));
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CombatConfig.staggerTicks, 2));
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.8), target.getZ(), 20, 0.3, 0.3, 0.3, 0.3);
            level.playSound(null, target.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.6f, 1.8f);
            player.sendOverlayMessage(Component.literal("POSTURE BROKEN - strike now!").withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD));
        }
    }

    private static void syncPosture(ServerPlayer player, LivingEntity target, double value, boolean staggered) {
        NetworkManager.sendToPlayer(player, new PostureSyncPayload(target.getId(), (int) value, (int) CombatConfig.postureMax, staggered));
    }

    /** True when the attacker is within about 60 degrees of where the player is looking. */
    private static boolean isInFront(ServerPlayer player, LivingEntity attacker) {
        Vec3 look = player.getLookAngle();
        Vec3 to = attacker.position().subtract(player.position());
        Vec3 flatLook = new Vec3(look.x, 0, look.z).normalize();
        Vec3 flatTo = new Vec3(to.x, 0, to.z).normalize();
        return flatLook.dot(flatTo) > 0.5;
    }
}
