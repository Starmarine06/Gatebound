package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

/**
 * Geometry and rules of the Spire: floor f occupies the region x = f * FLOOR_SPACING .. + size, z = 0 .. size
 * of the dungeon dimension. Floors are numbered from 1.
 */
public final class Spire {
    public static final int FLOOR_SPACING = 4096;
    /** Y of the floor slab; players stand at FY + 1. */
    public static final int FY = 40;

    /** Gate dungeons live at floor indices from GATE_BASE up: GATE_BASE + slot * 6 + rank. They are not part of the tower. */
    public static final int GATE_BASE = 2000;
    public static final int GATE_SLOTS = 600;

    /** True if the floor index is a gate dungeon floor. */
    public static boolean isGate(int floor) {
        return floor >= GATE_BASE && floor < GATE_BASE + GATE_SLOTS * 6;
    }

    /** Floor index of a gate slot and rank. */
    public static int gateIndex(int slot, GateRank rank) {
        return GATE_BASE + slot * 6 + rank.ordinal();
    }

    /** The tower floor whose difficulty a floor index stands for (gate dungeons use the middle of their rank's band). */
    public static int scaleFloor(int floor, int total) {
        if (!isGate(floor)) {
            return floor;
        }
        int[] r = bandRange(band(floor, total), total);
        return (r[0] + r[1]) / 2;
    }

    public enum Kind {
        NORMAL, REST, BOSS, FINAL
    }

    /** Static holder; not instantiable. */
    private Spire() {
    }

    /** Floor side length in blocks (multiple of 16). */
    public static int size() {
        int s = Math.max(96, Math.min(256, DungeonConfig.floorSize));
        return (s + 15) / 16 * 16;
    }

    /** World x where a floor starts. */
    public static int originX(int floor) {
        return floor * FLOOR_SPACING;
    }

    /** The floor that contains the given dungeon-dimension position, or 0 if none. */
    public static int floorAt(double x, double z, int total) {
        if (total <= 0) {
            return 0;
        }
        int f = Math.floorDiv((int) Math.floor(x), FLOOR_SPACING);
        if (f < 1 || (f > total && !isGate(f))) {
            return 0;
        }
        double lx = x - originX(f);
        int s = size();
        if (lx < -12 || lx > s + 12 || z < -12 || z > s + 12) {
            return 0;
        }
        return f;
    }

    /** Bounding box of a floor. */
    public static AABB bounds(int floor) {
        int ox = originX(floor);
        int s = size();
        return new AABB(ox - 6, FY - 6, -6, ox + s + 6, FY + 40, s + 6);
    }

    /** Kind of a floor (normal, rest, boss, final). */
    public static Kind kind(int floor, int total) {
        if (isGate(floor)) {
            return Kind.BOSS; // rooms leading to a guardian arena
        }
        if (floor == total) {
            return Kind.FINAL;
        }
        if (floor % 10 == 0) {
            return Kind.BOSS;
        }
        if (floor % 5 == 0) {
            return Kind.REST;
        }
        return Kind.NORMAL;
    }

    /** Difficulty band 0..5 (gate ranks E..S) of a floor. */
    public static int band(int floor, int total) {
        if (isGate(floor)) {
            return (floor - GATE_BASE) % 6;
        }
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(5, (int) Math.floor((floor - 1) * 6.0 / total)));
    }

    /** Gate rank of a floor. */
    public static GateRank rank(int floor, int total) {
        return GateRank.byIndex(band(floor, total));
    }

    /** Visual theme of a floor. */
    public static FloorTheme theme(int floor, int total) {
        Kind k = kind(floor, total);
        return k == Kind.REST ? FloorTheme.SANCTUARY : FloorTheme.ofBand(band(floor, total));
    }

    /** Inclusive floor range {lo, hi} of a band; falls back to the nearest floor if the band is empty. */
    public static int[] bandRange(int band, int total) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int f = 1; f <= total; f++) {
            if (band(f, total) == band) {
                lo = Math.min(lo, f);
                hi = Math.max(hi, f);
            }
        }
        if (lo > hi) {
            int f = Math.max(1, Math.min(total, (int) Math.round((band + 0.5) * total / 6.0)));
            return new int[]{f, f};
        }
        return new int[]{lo, hi};
    }

    /** A floor within the rank's band, biased toward the lower floors of the band. */
    public static int pickFloor(GateRank rank, int total, RandomSource rng) {
        int[] range = bandRange(rank.ordinal(), total);
        double u = Math.pow(rng.nextDouble(), Math.max(0.2, DungeonConfig.gateFloorBias));
        return Math.max(range[0], Math.min(range[1], range[0] + (int) Math.floor((range[1] - range[0] + 1) * u)));
    }

    /** Short description of a floor. */
    public static String describe(int floor, int total) {
        if (isGate(floor)) {
            return "Gate Dungeon - " + theme(floor, total).label();
        }
        Kind k = kind(floor, total);
        return switch (k) {
            case REST -> "Rest Floor";
            case BOSS -> "Guardian Floor - " + theme(floor, total).label();
            case FINAL -> "The Summit";
            default -> theme(floor, total).label();
        };
    }

    /** World position from floor-local coordinates. */
    public static BlockPos abs(int floor, int i, int y, int j) {
        return new BlockPos(originX(floor) + i, y, j);
    }

    /** Deterministic 64-bit mix used for seeding and per-block variation. */
    public static long mix(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
