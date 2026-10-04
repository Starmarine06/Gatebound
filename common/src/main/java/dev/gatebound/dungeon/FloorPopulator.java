package dev.gatebound.dungeon;

import dev.gatebound.registry.GateboundRegistries;
import dev.gatebound.trade.TradeItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Spawning of floor mobs and bosses, and chest loot. Stateless; scales everything by floor and player level. */
public final class FloorPopulator {
    public static final String TAG_MOB = "gatebound_mob";
    public static final String TAG_BOSS = "gatebound_boss";

    private FloorPopulator() {
    }

    public static int mobCount(FloorPlan plan) {
        if (plan.kind == Spire.Kind.REST) {
            return 0;
        }
        double n = DungeonConfig.mobBase + Spire.scaleFloor(plan.floor, plan.total) * DungeonConfig.mobPerFloor;
        if (plan.kind != Spire.Kind.NORMAL) {
            n *= 0.6;
        }
        return Math.min(plan.mobSpots.size(), (int) Math.min(DungeonConfig.mobMax, n));
    }

    public static Mob spawnRegular(ServerLevel level, BlockPos spot, FloorPlan plan, int popLevel, RandomSource rng, double mult) {
        GateRank rank = Spire.rank(plan.floor, plan.total);
        List<EntityType<? extends Mob>> pool = plan.theme.mobPool(rank);
        EntityType<? extends Mob> type = pool.get(rng.nextInt(pool.size()));
        Mob mob = type.spawn(level, spot, EntitySpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        double hp = (1.0 + DungeonConfig.hpPerFloor * Spire.scaleFloor(plan.floor, plan.total)) * (1.0 + DungeonConfig.hpPerLevel * popLevel) * mult;
        double dmg = (1.0 + DungeonConfig.dmgPerFloor * Spire.scaleFloor(plan.floor, plan.total)) * (mult > 1.0 ? 1.15 : 1.0);
        setMaxHealth(mob, mob.getMaxHealth() * hp);
        scaleDamage(mob, dmg);
        mob.addTag(TAG_MOB);
        mob.setPersistenceRequired();
        return mob;
    }

    public static Mob spawnBoss(ServerLevel level, FloorPlan plan, int popLevel) {
        boolean fin = plan.kind == Spire.Kind.FINAL;
        GateRank rank = Spire.rank(plan.floor, plan.total);
        EntityType<? extends Mob> type = fin ? net.minecraft.world.entity.EntityTypes.WITHER_SKELETON : rank.bossType();
        Mob boss = type.spawn(level, plan.boss, EntitySpawnReason.EVENT);
        if (boss == null) {
            return null;
        }
        double hp = 100.0 * (1.0 + 0.1 * Spire.scaleFloor(plan.floor, plan.total)) * (1.0 + 0.03 * popLevel) * (fin ? 2.5 : 1.0);
        setMaxHealth(boss, hp);
        scaleDamage(boss, (1.0 + 0.02 * Spire.scaleFloor(plan.floor, plan.total)) * (fin ? 1.4 : 1.0));
        AttributeInstance size = boss.getAttribute(Attributes.SCALE);
        if (size != null) {
            size.setBaseValue(fin ? 2.4 : rank.bossScale());
        }
        boss.addTag(TAG_BOSS);
        boss.addTag(TAG_MOB);
        boss.setPersistenceRequired();
        Component name = fin
                ? Component.literal("Sovereign of the Spire").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                : Component.literal(Spire.isGate(plan.floor) ? rank.label() + " Gate Guardian" : "Guardian of Floor " + plan.floor).withStyle(rank.color());
        boss.setCustomName(name);
        boss.setCustomNameVisible(true);
        boss.setGlowingTag(true);
        return boss;
    }

    private static void setMaxHealth(Mob mob, double value) {
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(value);
            mob.setHealth((float) value);
        }
    }

    private static void scaleDamage(Mob mob, double mult) {
        AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * mult);
        }
    }

    // ------------------------------------------------------------------ loot

    public static void placeChest(ServerLevel level, FloorPlan.Chest chest, int floor, int total, RandomSource rng, boolean supplies) {
        level.setBlock(chest.pos(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, chest.facing()), 3);
        fillChest(level, chest.pos(), floor, total, rng, supplies);
    }

    public static void fillChest(ServerLevel level, BlockPos pos, int floor, int total, RandomSource rng, boolean supplies) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity c)) {
            return;
        }
        c.clearContent();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, new java.util.Random(rng.nextLong()));
        for (ItemStack stack : loot(floor, total, rng, supplies)) {
            if (slots.isEmpty()) {
                break;
            }
            c.setItem(slots.remove(slots.size() - 1), stack);
        }
        c.setChanged();
    }

    public static List<ItemStack> loot(int floor, int total, RandomSource rng, boolean supplies) {
        int r = Spire.band(floor, total);
        List<ItemStack> out = new ArrayList<>();
        if (supplies) {
            out.add(new ItemStack(Items.COOKED_BEEF, 8 + 2 * r));
            out.add(new ItemStack(Items.BREAD, 6));
            out.add(new ItemStack(Items.GOLDEN_CARROT, 4 + r));
            out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + r));
            out.add(new ItemStack(Items.TORCH, 16));
            return out;
        }
        int picks = 4 + r / 2 + rng.nextInt(3);
        for (int k = 0; k < picks; k++) {
            int roll = rng.nextInt(100);
            if (roll < 18) {
                out.add(new ItemStack(Items.IRON_INGOT, 4 + 2 * r + rng.nextInt(4)));
            } else if (roll < 32) {
                out.add(new ItemStack(Items.COOKED_BEEF, 6 + 2 * r));
            } else if (roll < 44) {
                out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + r + rng.nextInt(3)));
            } else if (roll < 56) {
                out.add(new ItemStack(Items.EMERALD, 2 + r + rng.nextInt(3)));
            } else if (roll < 66) {
                out.add(r <= 2 ? new ItemStack(Items.ARROW, 12 + 4 * r) : new ItemStack(Items.GOLDEN_CARROT, 6 + r));
            } else if (roll < 76) {
                out.add(r >= 1 ? new ItemStack(Items.GOLD_INGOT, 3 + 2 * r) : new ItemStack(Items.IRON_INGOT, 5));
            } else if (roll < 84) {
                out.add(r >= 1 ? new ItemStack(Items.DIAMOND, 1 + r / 2 + rng.nextInt(2)) : new ItemStack(Items.EMERALD, 3));
            } else if (roll < 90) {
                out.add(r >= 1 ? new ItemStack(Items.GOLDEN_APPLE, 1 + r / 3) : new ItemStack(Items.COOKED_BEEF, 8));
            } else if (roll < 94) {
                out.add(r >= 3 ? new ItemStack(Items.NETHERITE_SCRAP, 1 + (r - 3)) : new ItemStack(Items.GOLD_INGOT, 4));
            } else {
                out.add(new ItemStack(TradeItems.GATE_SHARD.get(), 1 + r / 2));
            }
        }
        if (r >= 5 && rng.nextInt(3) == 0) {
            out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        }
        if (rng.nextDouble() < 0.01 + 0.03 * r) {
            out.add(new ItemStack(GateboundRegistries.AWAKENING_STONE.get(), 1));
        }
        return out;
    }
}
