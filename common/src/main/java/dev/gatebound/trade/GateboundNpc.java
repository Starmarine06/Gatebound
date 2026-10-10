package dev.gatebound.trade;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A Gatebound NPC. It is a Villager subclass purely so the vanilla villager model and renderer work unchanged;
 * it never uses vanilla trading. It is invulnerable, never moves (no AI), turns to face nearby players and opens the
 * custom Gatebound trade screen on right click. The role is stored as an entity tag.
 */
public class GateboundNpc extends Villager {
    /** Creates an NPC (no AI, never despawns). */
    public GateboundNpc(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setPersistenceRequired();
    }

    /** True if this NPC is a Skill Broker. */
    public boolean isBroker() {
        return this.entityTags().contains(NpcHandler.BROKER_TAG);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer sp) {
            NpcTrades.open(sp, this);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.tickCount % 3 == 0) {
            Player near = this.level().getNearestPlayer(this, 6.0);
            if (near != null) {
                this.lookAt(near, 60.0f, 40.0f);
                this.setYHeadRot(this.getYRot());
            }
        }
    }
}
