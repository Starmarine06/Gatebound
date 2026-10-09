package dev.gatebound.dungeon;

import dev.gatebound.compat.MobTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;

/** Gate difficulty, E (easiest) to S (hardest). Also holds all per-rank tuning. */
public enum GateRank implements StringRepresentable {
    E("e", ChatFormatting.GRAY, 200, 2),
    D("d", ChatFormatting.GREEN, 450, 3),
    C("c", ChatFormatting.YELLOW, 900, 3),
    B("b", ChatFormatting.GOLD, 1600, 4),
    A("a", ChatFormatting.RED, 2600, 4),
    S("s", ChatFormatting.LIGHT_PURPLE, 4000, 5);

    private final String id;
    private final ChatFormatting color;
    private final int xpReward;
    private final int roomCount;

    GateRank(String id, ChatFormatting color, int xpReward, int roomCount) {
        this.id = id;
        this.color = color;
        this.xpReward = xpReward;
        this.roomCount = roomCount;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() {
        return id;
    }

    public ChatFormatting color() {
        return color;
    }

    public int xpReward() {
        return xpReward;
    }

    /** Number of regular combat rooms before the boss room. */
    public int roomCount() {
        return roomCount;
    }

    public String label() {
        return name() + "-Rank";
    }

    public static GateRank byId(String id) {
        for (GateRank r : values()) {
            if (r.id.equalsIgnoreCase(id)) {
                return r;
            }
        }
        return E;
    }

    public static GateRank byIndex(int i) {
        GateRank[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, i))];
    }

    /** Regular mob types for this rank; harder ranks add tougher types on top of the easier ones. */
    public List<EntityType<? extends Mob>> mobPool() {
        return switch (this) {
            case E -> List.of(MobTypes.ZOMBIE, MobTypes.SKELETON, MobTypes.SPIDER);
            case D -> List.of(MobTypes.ZOMBIE, MobTypes.SKELETON, MobTypes.SPIDER, MobTypes.HUSK, MobTypes.STRAY);
            case C -> List.of(MobTypes.ZOMBIE, MobTypes.SKELETON, MobTypes.HUSK, MobTypes.STRAY,
                    MobTypes.CAVE_SPIDER, MobTypes.PILLAGER);
            case B -> List.of(MobTypes.HUSK, MobTypes.STRAY, MobTypes.PILLAGER, MobTypes.VINDICATOR,
                    MobTypes.CAVE_SPIDER, MobTypes.WITCH);
            case A -> List.of(MobTypes.VINDICATOR, MobTypes.PILLAGER, MobTypes.WITCH, MobTypes.WITHER_SKELETON,
                    MobTypes.STRAY, MobTypes.HUSK);
            case S -> List.of(MobTypes.VINDICATOR, MobTypes.WITHER_SKELETON, MobTypes.WITCH, MobTypes.PILLAGER,
                    MobTypes.EVOKER);
        };
    }

    public EntityType<? extends Mob> bossType() {
        return switch (this) {
            case E -> MobTypes.ZOMBIE;
            case D -> MobTypes.VINDICATOR;
            case C -> MobTypes.WITHER_SKELETON;
            case B -> MobTypes.EVOKER;
            case A -> MobTypes.RAVAGER;
            case S -> MobTypes.WITHER_SKELETON;
        };
    }

    public double bossScale() {
        return switch (this) {
            case E -> 1.7;
            case S -> 2.0;
            case A -> 1.2;
            default -> 1.4;
        };
    }
}
