package dev.gatebound.dungeon;

import dev.gatebound.mob.MobMaterials;
import dev.gatebound.mob.SpireMobs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What the Spire's monsters leave behind: materials of their rank, which crafting turns into vanilla progression.
 * Custom monsters pay far better than the vanilla ones that fill the lower floors; guardians always drop a bundle.
 */
public final class MobLoot {
    private MobLoot() {
    }

    public static void drop(ServerLevel level, Entity mob, int floor, int total, List<ServerPlayer> here) {
        RandomSource rng = level.getRandom();
        int band = Spire.band(floor, total);
        boolean custom = SpireMobs.isCustom(mob.getType());
        double chance = custom ? DungeonConfig.customMaterialChance : DungeonConfig.vanillaMaterialChance;
        if (rng.nextDouble() >= chance) {
            return;
        }
        int count = 1 + (custom ? rng.nextInt(2) : 0) + (here.size() > 1 ? here.size() / 2 : 0);
        mob.spawnAtLocation(level, new ItemStack(MobMaterials.ofBand(band), count));
    }

    public static void dropBoss(ServerLevel level, Entity boss, int floor, int total, List<ServerPlayer> here) {
        RandomSource rng = level.getRandom();
        int band = Spire.band(floor, total);
        boolean fin = Spire.kind(floor, total) == Spire.Kind.FINAL;
        int extra = Math.max(0, here.size() - 1);
        boss.spawnAtLocation(level, new ItemStack(MobMaterials.ofBand(band), 4 + rng.nextInt(3) + band + extra * 2));
        if (band > 0 && rng.nextBoolean()) {
            boss.spawnAtLocation(level, new ItemStack(MobMaterials.ofBand(band - 1), 3 + rng.nextInt(3)));
        }
        if (fin || rng.nextDouble() < 0.35 + 0.1 * band) {
            boss.spawnAtLocation(level, new ItemStack(MobMaterials.SOVEREIGN_SIGIL.get(), fin ? 2 + extra : 1));
        }
    }
}
