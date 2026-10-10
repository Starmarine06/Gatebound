package dev.gatebound.combat;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.ParryStatePayload;
import dev.gatebound.network.PostureSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.DamageTypeTags;
import dev.gatebound.skill.ManaState;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
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
        // Dungeon mobs never hurt each other (a stray arrow or a swing that misses the player must not start a brawl).
        EntityEvent.LIVING_HURT.register((victim, source, amount) -> {
            if (victim instanceof net.minecraft.world.entity.Mob
                    && source.getEntity() instanceof net.minecraft.world.entity.Mob attacker
                    && victim.level().dimension().equals(dev.gatebound.dungeon.DungeonRegistries.DUNGEON)
                    && !dev.gatebound.echo.EchoManager.isEcho(victim) && !dev.gatebound.echo.EchoManager.isEcho(attacker)) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
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
        if (PlayerCombat.isStaggered(player)) {
            return;
        }
        int now = player.level().getServer().getTickCount();
        if (CombatState.startParry(player.getUUID(), now)) {
            NetworkManager.sendToPlayer(player, new ParryStatePayload(CombatConfig.parryWindowTicks, CombatConfig.parryCooldownTicks));
            // Arm swing, shown to the player and everyone nearby.
            Compat.swing(player, InteractionHand.MAIN_HAND, true);
            player.level().playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.PLAYER_ATTACK_SWEEP), SoundSource.PLAYERS, 0.5f, 1.6f);
        }
    }

    private static EventResult onHurt(LivingEntity victim, DamageSource source, float amount) {
        if (amount <= 0 || applyingFinisher || !(victim.level() instanceof ServerLevel level)) {
            return EventResult.pass();
        }
        EventResult rogue = dev.gatebound.skill.RogueSkills.onHurt(victim, source, amount, level);
        if (rogue.interruptsFurtherEvaluation()) {
            return rogue;
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

    public static boolean isMelee(DamageSource source) {
        return source.getDirectEntity() != null && source.getDirectEntity() == source.getEntity()
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && !source.is(DamageTypes.MAGIC) && !source.is(DamageTypes.INDIRECT_MAGIC);
    }

    private static EventResult onPlayerHit(ServerPlayer player, LivingEntity attacker, DamageSource source, int now, ServerLevel level) {
        if (!isMelee(source)) {
            return EventResult.pass();
        }
        boolean timed = CombatState.isParrying(player.getUUID(), now);
        boolean inFront = isInFront(player, attacker);

        // Heavy attacks cannot be parried; trying to costs posture. They can only be dodged.
        if (TelegraphGoal.isHeavyStrike(attacker)) {
            if (timed) {
                Compat.overlay(player, Component.translatable("combat.gatebound.cant_parry").withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
                PlayerCombat.addPosture(player, CombatConfig.heavyParryPosture);
            }
            return EventResult.pass();
        }

        if (!(timed && inFront)) {
            int sincePress = CombatState.ticksSinceParry(player.getUUID(), now);
            if (sincePress > CombatConfig.parryWindowTicks && sincePress <= CombatConfig.parryCooldownTicks) {
                // Pressed, but too early: the mistimed block costs posture.
                PlayerCombat.addPosture(player, CombatConfig.lateParryPosture);
            }
            if (CombatState.debug(player.getUUID())) {
                int since = CombatState.ticksSinceParry(player.getUUID(), now);
                String why = since < 0 ? "no parry pressed yet"
                        : !inFront ? "attacker was not in front"
                        : "pressed " + since + " ticks before the hit (window is " + CombatConfig.parryWindowTicks + ")";
                Compat.overlay(player, Component.literal("Parry missed: " + why));
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
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.SHIELD_BLOCK.value()), SoundSource.PLAYERS, 1.0f, 1.3f);
        level.playSound(null, player.blockPosition(), GateboundSounds.of(SoundEvents.PLAYER_ATTACK_CRIT), SoundSource.PLAYERS, 0.8f, 1.5f);

        // A second swing with the other arm on a clean parry, so a successful deflect looks different from a miss.
        Compat.swing(player, InteractionHand.OFF_HAND, true);
        ManaState.add(player, CombatConfig.parryMana);
        player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, CombatConfig.counterTicks, 1));
        Compat.overlay(player, Component.literal("PARRY!").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));

        addPostureAndMaybeBreak(attacker, CombatConfig.parryPosture, now, level, player);
        return EventResult.interruptFalse();
    }

    private static void onMobHit(ServerPlayer player, Mob mob, DamageSource source, int now, ServerLevel level) {
        if (isMelee(source) && CombatState.isStaggered(mob.getUUID(), now)) {
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
            Compat.overlay(player, Component.literal("FINISHER!").withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
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
            Compat.overlay(player, Component.literal("Posture " + (int) value + " / " + (int) CombatConfig.postureMax));
        }
        if (value >= CombatConfig.postureMax) {
            CombatState.stagger(target.getUUID(), now);
            CombatState.resetPosture(target.getUUID());
            syncPosture(player, target, CombatConfig.postureMax, true);
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CombatConfig.staggerTicks, 4));
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CombatConfig.staggerTicks, 2));
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.8), target.getZ(), 20, 0.3, 0.3, 0.3, 0.3);
            level.playSound(null, target.blockPosition(), GateboundSounds.of(SoundEvents.ANVIL_LAND), SoundSource.HOSTILE, 0.6f, 1.8f);
            Compat.overlay(player, Component.literal("POSTURE BROKEN - strike now!").withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD));
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
