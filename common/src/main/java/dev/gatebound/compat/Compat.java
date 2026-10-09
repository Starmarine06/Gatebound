package dev.gatebound.compat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Common-side Minecraft API differences between versions. Call sites stay version-free;
 * the Stonecutter conditionals live here. Client-only differences are in {@link ClientCompat}.
 */
public final class Compat {
    private Compat() {
    }

    /** The "cannot be pushed or pulled" reaction, renamed after 26.2. */
    //? if >=26.3 {
    public static final PushReaction IMMOVABLE = PushReaction.IMMOVEABLE;
    //?} else {
    /*public static final PushReaction IMMOVABLE = PushReaction.BLOCK;
    *///?}

    /** Action-bar message. {@code Player.sendOverlayMessage} is new in 26.1; before it this was displayClientMessage. */
    public static void overlay(net.minecraft.world.entity.player.Player player, net.minecraft.network.chat.Component message) {
        //? if >=26.1 {
        player.sendOverlayMessage(message);
        //?} else {
        /*player.displayClientMessage(message, true);
        *///?}
    }

    /** The keepInventory game rule (rules became typed GameRule objects in 26.x). */
    public static boolean keepInventory(net.minecraft.server.level.ServerLevel level) {
        //? if >=26.1 {
        return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
        //?} else {
        /*return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        *///?}
    }

    /** Block entity types: the constructor is public from 26.2, private in 26.1 and built with a Builder before that. */
    public static <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityType<T> blockEntityType(
            net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<? extends T> factory, Block block) {
        //? if >=26.2 {
        return new net.minecraft.world.level.block.entity.BlockEntityType<>(factory, java.util.Set.of(block));
        //?} else if >=26.1 {
        /*try {
            java.lang.reflect.Constructor<?> constructor = net.minecraft.world.level.block.entity.BlockEntityType.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraft.world.level.block.entity.BlockEntityType<T> type =
                    (net.minecraft.world.level.block.entity.BlockEntityType<T>) constructor.newInstance(factory, java.util.Set.of(block));
            return type;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create block entity type", e);
        }
        *///?} else {
        /*return net.minecraft.world.level.block.entity.BlockEntityType.Builder.<T>of(factory::create, block).build(null);
        *///?}
    }

    /** Wraps a use() result for the pre-1.21.2 {@code Item.use} signature. */
    //? if <26.1 {
    /*public static net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> holder(
            net.minecraft.world.InteractionResult result, net.minecraft.world.item.ItemStack stack) {
        return new net.minecraft.world.InteractionResultHolder<>(result, stack);
    }
    *///?}

    /** Coloured dust particle; the colour is an int from 1.21.2 and a vector before that. */
    public static net.minecraft.core.particles.ParticleOptions dust(int rgb, float size) {
        //? if >=26.1 {
        return new net.minecraft.core.particles.DustParticleOptions(rgb, size);
        //?} else {
        /*return new net.minecraft.core.particles.DustParticleOptions(net.minecraft.world.phys.Vec3.fromRGB24(rgb).toVector3f(), size);
        *///?}
    }

    /** Dust that fades from one colour to another. */
    public static net.minecraft.core.particles.ParticleOptions fade(int from, int to, float size) {
        //? if >=26.1 {
        return new net.minecraft.core.particles.DustColorTransitionOptions(from, to, size);
        //?} else {
        /*return new net.minecraft.core.particles.DustColorTransitionOptions(net.minecraft.world.phys.Vec3.fromRGB24(from).toVector3f(),
                net.minecraft.world.phys.Vec3.fromRGB24(to).toVector3f(), size);
        *///?}
    }

    /** What the player is holding down. The server gets it from the client input packet (26.1+) or the movement fields. */
    public record Input(boolean forward, boolean backward, boolean jump, boolean shift) {
    }

    public static Input input(ServerPlayer player) {
        //? if >=26.1 {
        net.minecraft.world.entity.player.Input in = player.getLastClientInput();
        return in == null ? new Input(false, false, false, false) : new Input(in.forward(), in.backward(), in.jump(), in.shift());
        //?} else {
        /*return new Input(player.zza > 0, player.zza < 0, isJumping(player), player.isShiftKeyDown());
        *///?}
    }

    //? if <26.1 {
    /*private static boolean isJumping(LivingEntity entity) {
        try {
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("jumping");
            f.setAccessible(true);
            return f.getBoolean(entity);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
    *///?}

    /** Moves an entity to a spot in a level, keeping the way it is facing. */
    public static void moveKeepingRotation(Entity entity, net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 pos) {
        //? if >=26.1 {
        entity.teleportTo(level, pos.x, pos.y, pos.z, net.minecraft.world.entity.Relative.ROTATION, 0f, 0f, false);
        //?} else {
        /*entity.teleportTo(level, pos.x, pos.y, pos.z, net.minecraft.world.entity.RelativeMovement.ROTATION, 0f, 0f);
        *///?}
    }

    /** Sends a player to the world spawn (or their respawn point where the version supports that). */
    public static void teleportToDefaultSpawn(ServerPlayer player) {
        //? if >=26.1 {
        player.teleport(net.minecraft.world.level.portal.TeleportTransition.createDefault(player,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
        //?} else {
        /*net.minecraft.server.level.ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
        *///?}
    }

    /** Forces a write of every dirty saved-data file. */
    public static void flushSavedData(net.minecraft.server.MinecraftServer server) {
        //? if >=26.1 {
        server.overworld().getDataStorage().scheduleSave();
        //?} else {
        /*server.overworld().getDataStorage().save();
        *///?}
    }

    public static void swing(LivingEntity entity, InteractionHand hand) {
        swing(entity, hand, false);
    }

    public static void swing(LivingEntity entity, InteractionHand hand, boolean broadcast) {
        //? if >=26.3 {
        entity.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, broadcast);
        //?} else {
        /*entity.swing(hand, broadcast);
        *///?}
    }

    /** Clears the post-hit invulnerability frames so the next hit lands. */
    public static void setInvulnerableTime(Entity entity, int ticks) {
        //? if >=26.3 {
        entity.setInvulnerableTime(ticks);
        //?} else {
        /*entity.invulnerableTime = ticks;
        *///?}
    }

    public static void setPermanentlyInvulnerable(Entity entity, boolean value) {
        //? if >=26.3 {
        entity.setPermanentlyInvulnerable(value);
        //?} else {
        /*entity.setInvulnerable(value);
        *///?}
    }

    public static boolean isPermanentlyInvulnerable(Entity entity) {
        //? if >=26.3 {
        return entity.isPermanentlyInvulnerable();
        //?} else {
        /*return entity.isInvulnerable();
        *///?}
    }

    /** The mob's goal selector. Public getter from 26.2; a protected field before that. */
    public static GoalSelector goals(Mob mob) {
        //? if >=26.2 {
        return mob.getGoalSelector();
        //?} else {
        /*try {
            java.lang.reflect.Field f = Mob.class.getDeclaredField("goalSelector");
            f.setAccessible(true);
            return (GoalSelector) f.get(mob);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mob.goalSelector is gone", e);
        }
        *///?}
    }

    //? if >=26.2 {
    public static final Block RED_CARPET = Blocks.CARPET.red();
    public static final Block WHITE_CARPET = Blocks.CARPET.white();
    public static final Block PURPLE_CARPET = Blocks.CARPET.purple();
    public static final Block PURPLE_STAINED_GLASS = Blocks.STAINED_GLASS.purple();
    //?} else {
    /*public static final Block RED_CARPET = Blocks.RED_CARPET;
    public static final Block WHITE_CARPET = Blocks.WHITE_CARPET;
    public static final Block PURPLE_CARPET = Blocks.PURPLE_CARPET;
    public static final Block PURPLE_STAINED_GLASS = Blocks.PURPLE_STAINED_GLASS;
    *///?}

    public static void setTeamColor(PlayerTeam team, boolean gold) {
        //? if >=26.2 {
        team.setColor(java.util.Optional.of(gold ? net.minecraft.world.scores.TeamColor.GOLD : net.minecraft.world.scores.TeamColor.DARK_PURPLE));
        //?} else {
        /*team.setColor(gold ? ChatFormatting.GOLD : ChatFormatting.DARK_PURPLE);
        *///?}
    }

    /** Block-break handler without the experience argument Architectury passes before 26.2. */
    @FunctionalInterface
    public interface BreakHandler {
        EventResult breakBlock(Level level, BlockPos pos, BlockState state, ServerPlayer player);
    }

    public static void onBlockBreak(BreakHandler handler) {
        //? if >=26.2 {
        BlockEvent.BREAK.register(handler::breakBlock);
        //?} else {
        /*BlockEvent.BREAK.register((level, pos, state, player, xp) -> handler.breakBlock(level, pos, state, player));
        *///?}
    }

    public static <T extends CustomPacketPayload> void registerC2S(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, NetworkManager.NetworkReceiver<T> receiver) {
        //? if >=26.2 {
        NetworkManager.registerC2S(type, codec, receiver);
        //?} else {
        /*NetworkManager.registerReceiver(NetworkManager.Side.C2S, type, codec, receiver);
        *///?}
    }

    public static <T extends CustomPacketPayload> void registerS2C(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, NetworkManager.NetworkReceiver<T> receiver) {
        //? if >=26.2 {
        NetworkManager.registerS2C(type, codec, receiver);
        //?} else {
        /*NetworkManager.registerS2CPayloadType(type, codec);
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, type, codec, receiver);
        }
        *///?}
    }
}
