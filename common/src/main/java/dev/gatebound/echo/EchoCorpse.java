package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The body a Summoner's kill leaves behind. It is drawn as the fallen mob, cannot be hurt, and has no AI. Its owner
 * right-clicks it to open the bind menu, where it can be made an Echo and given a group (role). It fades after a while.
 */
public class EchoCorpse extends EchoEntity {
    private UUID owner;
    private String bodyName = "";
    private int age;

    public EchoCorpse(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPermanentlyInvulnerable(true);
    }

    public void setOwner(UUID id, String name) {
        this.owner = id;
        this.bodyName = name;
    }

    public UUID owner() {
        return owner;
    }

    public String bodyName() {
        return bodyName;
    }

    @Override
    public boolean isCorpse() {
        return true;
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server) {
            age++;
            if (age % 12 == 0) {
                server.sendParticles(ParticleTypes.SOUL, getX(), getY() + 0.3, getZ(), 1, 0.3, 0.2, 0.3, 0.01);
            }
            if (age % 4 == 0) {
                // A thin column of light so bodies can be spotted from afar, in caves and dungeons too.
                server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 1.2 + (age % 40) * 0.1, getZ(), 1, 0.05, 0.0, 0.05, 0.0);
            }
            if (age % 20 == 0) {
                int left = Math.max(0, (EchoConfig.corpseTicks - age) / 20);
                setCustomName(Component.literal("Soul of " + bodyName + " (" + left / 60 + ":" + String.format("%02d", left % 60) + ")"));
            }
            if (age > EchoConfig.corpseTicks) {
                server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 12, 0.3, 0.2, 0.3, 0.02);
                discard();
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp && sp.getUUID().equals(owner)) {
            if (sp.isShiftKeyDown()) {
                // Quick bind: straight into group 1, no menu.
                EchoManager.bindCorpse(sp, getId(), 0);
                return InteractionResult.SUCCESS_SERVER;
            }
            openMenu(sp);
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    void openMenu(ServerPlayer player) {
        PlayerData data = PlayerDataStore.of(player);
        List<Integer> modes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            modes.add(data.echo.groupModes[g]);
            int n = 0;
            for (EchoRoster.Entry e : data.echo.entries) {
                if (e.group() == g) {
                    n++;
                }
            }
            counts.add(n);
        }
        int free = Math.max(0, EchoManager.slots(data) - data.echo.entries.size());
        NetworkManager.sendToPlayer(player, new EchoBindMenuPayload(getId(), bodyName, free, modes, counts));
    }
}
