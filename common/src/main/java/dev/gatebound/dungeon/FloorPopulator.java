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

    /** Static holder; not instantiable. */
    private FloorPopulator() {
    }

    /** Number of mobs for a floor. */
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


    /**
     * Monsters in a gate dungeon: an E gate already holds what an A gate used to, and every rank above is a good deal
     * bigger. Each gate rolls its own count (about 75 to 125 percent of the table value), so no two are alike.
     */
    public static int gateMobCount(GateRank rank, RandomSource rng) {
        int base = DungeonConfig.gateMobCounts[Math.min(rank.ordinal(), DungeonConfig.gateMobCounts.length - 1)];
        return Math.max(8, (int) Math.round(base * (0.75 + 0.5 * rng.nextDouble())));
    }

    /** Level the gate is meant for when taken alone. Below it the monsters grow tougher the bigger the gap. */
    public static int recommendedLevel(GateRank rank) {
        return DungeonConfig.gateRecommendedLevels[Math.min(rank.ordinal(), DungeonConfig.gateRecommendedLevels.length - 1)];
    }

    /** Health multiplier for gate monsters when the strongest player is under the gate's recommended level. */
    public static double gateHealthMult(GateRank rank, int popLevel) {
        return 1.0 + Math.max(0, recommendedLevel(rank) - popLevel) * DungeonConfig.gateUnderlevelHealth;
    }

    /** Damage multiplier for gates above the party's level. */
    public static double gateDamageMult(GateRank rank, int popLevel) {
        return 1.0 + Math.max(0, recommendedLevel(rank) - popLevel) * DungeonConfig.gateUnderlevelDamage;
    }

    /** Spawns a regular mob at a spot. */
    public static Mob spawnRegular(ServerLevel level, BlockPos spot, FloorPlan plan, int popLevel, RandomSource rng, double mult) {
        GateRank rank = Spire.rank(plan.floor, plan.total);
        List<EntityType<? extends Mob>> pool = Spire.scaleFloor(plan.floor, plan.total) > DungeonConfig.customMobFloor
                ? dev.gatebound.mob.SpireMobs.regulars(rank.ordinal())
                : plan.theme.mobPool(rank);
        EntityType<? extends Mob> type = pool.get(rng.nextInt(pool.size()));
        Mob mob = type.spawn(level, spot, EntitySpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        double hp = (1.0 + DungeonConfig.hpPerFloor * Spire.scaleFloor(plan.floor, plan.total)) * (1.0 + DungeonConfig.hpPerLevel * popLevel) * mult;
        double dmg = (1.0 + DungeonConfig.dmgPerFloor * Spire.scaleFloor(plan.floor, plan.total)) * (mult > 1.0 ? 1.15 : 1.0);
        if (Spire.isGate(plan.floor)) {
            hp *= gateHealthMult(rank, popLevel);
            dmg *= gateDamageMult(rank, popLevel);
        }
        setMaxHealth(mob, mob.getMaxHealth() * hp);
        scaleDamage(mob, dmg);
        mob.addTag("gb_pow:" + Math.round(dmg * 100));
        mob.addTag(TAG_MOB);
        mob.setPersistenceRequired();
        return mob;
    }

    /** Spawns the floor's boss. */
    public static Mob spawnBoss(ServerLevel level, FloorPlan plan, int popLevel) {
        boolean fin = plan.kind == Spire.Kind.FINAL;
        GateRank rank = Spire.rank(plan.floor, plan.total);
        EntityType<? extends Mob> type = dev.gatebound.mob.SpireMobs.boss(rank.ordinal(), fin);
        Mob boss = type.spawn(level, plan.boss, EntitySpawnReason.EVENT);
        if (boss == null) {
            return null;
        }
        double hp = 100.0 * (1.0 + 0.1 * Spire.scaleFloor(plan.floor, plan.total)) * (1.0 + 0.03 * popLevel) * (fin ? 2.5 : 1.0);
        double dmgMult = 1.0;
        if (Spire.isGate(plan.floor)) {
            hp *= gateHealthMult(rank, popLevel);
            dmgMult = gateDamageMult(rank, popLevel);
        }
        setMaxHealth(boss, hp);
        double bossDmg = (1.0 + 0.02 * Spire.scaleFloor(plan.floor, plan.total)) * (fin ? 1.4 : 1.0) * dmgMult;
        scaleDamage(boss, bossDmg);
        boss.addTag("gb_pow:" + Math.round(bossDmg * 100));
        AttributeInstance size = boss.getAttribute(Attributes.SCALE);
        if (size != null) {
            size.setBaseValue(fin ? 2.4 : rank.bossScale());
        }
        boss.addTag(TAG_BOSS);
        level.playSound(null, boss.blockPosition(), dev.gatebound.sound.GateboundSounds.custom("boss.spawn"), net.minecraft.sounds.SoundSource.HOSTILE, 2f, 1f);
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

    /** Sets a mob's max health and fills it. */
    private static void setMaxHealth(Mob mob, double value) {
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(value);
            mob.setHealth((float) value);
        }
    }

    /** Multiplies a mob's attack damage. */
    private static void scaleDamage(Mob mob, double mult) {
        AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * mult);
        }
    }

    // ------------------------------------------------------------------ loot

    /** Places and fills a chest. */
    public static void placeChest(ServerLevel level, FloorPlan.Chest chest, int floor, int total, RandomSource rng, boolean supplies) {
        level.setBlock(chest.pos(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, chest.facing()), 3);
        fillChest(level, chest.pos(), floor, total, rng, supplies);
    }

    /** Fills a chest with loot. */
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

    /** Rolls loot for a floor. */
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
        if (rng.nextInt(3) == 0) {
            out.add(new ItemStack(dev.gatebound.mob.MobMaterials.ofBand(r), 2 + rng.nextInt(3)));
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
