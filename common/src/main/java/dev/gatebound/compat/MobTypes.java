package dev.gatebound.compat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * The vanilla entity types we use. The holder class is {@code EntityType} up to 26.1 and {@code EntityTypes} from 26.2,
 * so everything goes through here.
 */
public final class MobTypes {
    private MobTypes() {
    }

    //? if >=26.2 {
    public static final EntityType<? extends Mob> ZOMBIE = net.minecraft.world.entity.EntityTypes.ZOMBIE;
    public static final EntityType<? extends Mob> SKELETON = net.minecraft.world.entity.EntityTypes.SKELETON;
    public static final EntityType<? extends Mob> SPIDER = net.minecraft.world.entity.EntityTypes.SPIDER;
    public static final EntityType<? extends Mob> CAVE_SPIDER = net.minecraft.world.entity.EntityTypes.CAVE_SPIDER;
    public static final EntityType<? extends Mob> HUSK = net.minecraft.world.entity.EntityTypes.HUSK;
    public static final EntityType<? extends Mob> STRAY = net.minecraft.world.entity.EntityTypes.STRAY;
    public static final EntityType<? extends Mob> DROWNED = net.minecraft.world.entity.EntityTypes.DROWNED;
    public static final EntityType<? extends Mob> PILLAGER = net.minecraft.world.entity.EntityTypes.PILLAGER;
    public static final EntityType<? extends Mob> VINDICATOR = net.minecraft.world.entity.EntityTypes.VINDICATOR;
    public static final EntityType<? extends Mob> EVOKER = net.minecraft.world.entity.EntityTypes.EVOKER;
    public static final EntityType<? extends Mob> RAVAGER = net.minecraft.world.entity.EntityTypes.RAVAGER;
    public static final EntityType<? extends Mob> WITCH = net.minecraft.world.entity.EntityTypes.WITCH;
    public static final EntityType<? extends Mob> WITHER_SKELETON = net.minecraft.world.entity.EntityTypes.WITHER_SKELETON;
    public static final EntityType<? extends Mob> BLAZE = net.minecraft.world.entity.EntityTypes.BLAZE;
    public static final EntityType<? extends Mob> MAGMA_CUBE = net.minecraft.world.entity.EntityTypes.MAGMA_CUBE;
    public static final EntityType<ArmorStand> ARMOR_STAND = net.minecraft.world.entity.EntityTypes.ARMOR_STAND;
    public static final EntityType<LightningBolt> LIGHTNING_BOLT = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT;
    //?} else {
    /*public static final EntityType<? extends Mob> ZOMBIE = EntityType.ZOMBIE;
    public static final EntityType<? extends Mob> SKELETON = EntityType.SKELETON;
    public static final EntityType<? extends Mob> SPIDER = EntityType.SPIDER;
    public static final EntityType<? extends Mob> CAVE_SPIDER = EntityType.CAVE_SPIDER;
    public static final EntityType<? extends Mob> HUSK = EntityType.HUSK;
    public static final EntityType<? extends Mob> STRAY = EntityType.STRAY;
    public static final EntityType<? extends Mob> DROWNED = EntityType.DROWNED;
    public static final EntityType<? extends Mob> PILLAGER = EntityType.PILLAGER;
    public static final EntityType<? extends Mob> VINDICATOR = EntityType.VINDICATOR;
    public static final EntityType<? extends Mob> EVOKER = EntityType.EVOKER;
    public static final EntityType<? extends Mob> RAVAGER = EntityType.RAVAGER;
    public static final EntityType<? extends Mob> WITCH = EntityType.WITCH;
    public static final EntityType<? extends Mob> WITHER_SKELETON = EntityType.WITHER_SKELETON;
    public static final EntityType<? extends Mob> BLAZE = EntityType.BLAZE;
    public static final EntityType<? extends Mob> MAGMA_CUBE = EntityType.MAGMA_CUBE;
    public static final EntityType<ArmorStand> ARMOR_STAND = EntityType.ARMOR_STAND;
    public static final EntityType<LightningBolt> LIGHTNING_BOLT = EntityType.LIGHTNING_BOLT;
    *///?}
}
