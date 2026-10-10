package dev.gatebound.dungeon;

import dev.gatebound.compat.MobTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Visual theme and layout style of a floor band (one per gate rank, plus the rest-floor sanctuary). */
public enum FloorTheme {
    CATACOMBS("Catacombs", ChatFormatting.GRAY, false, null,
            blocks(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
            blocks(Blocks.COBBLESTONE, Blocks.STONE, Blocks.MOSSY_COBBLESTONE),
            blocks(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.STONE_BRICKS),
            lantern(false), Blocks.COBBLESTONE_SLAB, Blocks.STONE_BRICK_WALL, 6, 11, 28, 3, 5, 6),
    CAVERNS("Mossy Caverns", ChatFormatting.GREEN, true, null,
            blocks(Blocks.STONE, Blocks.ANDESITE, Blocks.TUFF),
            blocks(Blocks.STONE, Blocks.MOSS_BLOCK, Blocks.COBBLESTONE),
            blocks(Blocks.STONE, Blocks.TUFF, Blocks.DRIPSTONE_BLOCK),
            Blocks.SHROOMLIGHT, Blocks.MOSS_CARPET, Blocks.COBBLESTONE_WALL, 7, 13, 20, 3, 5, 6),
    FLOODED("Flooded Halls", ChatFormatting.AQUA, false, Blocks.WATER,
            blocks(Blocks.DEEPSLATE_BRICKS, Blocks.PRISMARINE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS),
            blocks(Blocks.DEEPSLATE_TILES, Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES),
            blocks(Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS),
            Blocks.SEA_LANTERN, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL, 9, 17, 16, 5, 6, 8),
    LAVA_HALLS("Lava Halls", ChatFormatting.GOLD, false, Blocks.LAVA,
            blocks(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS),
            blocks(Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.BASALT),
            blocks(Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE),
            Blocks.SHROOMLIGHT, Blocks.BLACKSTONE_SLAB, Blocks.POLISHED_BLACKSTONE_BRICK_WALL, 9, 19, 15, 5, 7, 10),
    ASHEN("Ashen Caverns", ChatFormatting.RED, true, Blocks.LAVA,
            blocks(Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.NETHERRACK),
            blocks(Blocks.NETHERRACK, Blocks.SOUL_SOIL, Blocks.BLACKSTONE),
            blocks(Blocks.NETHERRACK, Blocks.NETHER_BRICKS, Blocks.NETHERRACK),
            Blocks.GLOWSTONE, Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_WALL, 8, 14, 16, 5, 6, 8),
    VOID_SANCTUM("Void Sanctum", ChatFormatting.LIGHT_PURPLE, false, null,
            blocks(Blocks.OBSIDIAN, Blocks.BLACKSTONE, Blocks.CRYING_OBSIDIAN),
            blocks(Blocks.POLISHED_BLACKSTONE, Blocks.OBSIDIAN, Blocks.POLISHED_BLACKSTONE),
            blocks(Blocks.BLACKSTONE, Blocks.OBSIDIAN, Blocks.BLACKSTONE),
            Blocks.PEARLESCENT_FROGLIGHT, Blocks.PURPUR_SLAB, Blocks.PURPUR_PILLAR, 8, 18, 16, 5, 7, 11),
    SANCTUARY("Rest Sanctuary", ChatFormatting.WHITE, false, Blocks.WATER,
            blocks(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS),
            blocks(Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE),
            blocks(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS),
            Blocks.SHROOMLIGHT, Blocks.MOSS_CARPET, Blocks.STONE_BRICK_WALL, 8, 12, 0, 3, 6, 8);

    private final String label;
    private final ChatFormatting color;
    private final boolean cave;
    private final Block fluid;
    private final BlockState[] wall;
    private final BlockState[] floor;
    private final BlockState[] ceiling;
    private final BlockState light;
    private final boolean hangLight;
    private final BlockState rubble;
    private final BlockState pillar;
    private final int roomMin;
    private final int roomMax;
    private final int roomCount;
    private final int corridorWidth;
    private final int heightMin;
    private final int heightMax;

    FloorTheme(String label, ChatFormatting color, boolean cave, Block fluid, BlockState[] wall, BlockState[] floor,
               BlockState[] ceiling, Object lightSpec, Block rubble, Block pillar, int roomMin, int roomMax,
               int roomCount, int corridorWidth, int heightMin, int heightMax) {
        this.label = label;
        this.color = color;
        this.cave = cave;
        this.fluid = fluid;
        this.wall = wall;
        this.floor = floor;
        this.ceiling = ceiling;
        if (lightSpec instanceof BlockState s) {
            this.light = s;
            this.hangLight = true;
        } else {
            this.light = ((Block) lightSpec).defaultBlockState();
            this.hangLight = false;
        }
        this.rubble = rubble.defaultBlockState();
        this.pillar = pillar.defaultBlockState();
        this.roomMin = roomMin;
        this.roomMax = roomMax;
        this.roomCount = roomCount;
        this.corridorWidth = corridorWidth;
        this.heightMin = heightMin;
        this.heightMax = heightMax;
    }

    /** Three block states from three blocks. */
    private static BlockState[] blocks(Block a, Block b, Block c) {
        return new BlockState[]{a.defaultBlockState(), b.defaultBlockState(), c.defaultBlockState()};
    }

    /** A hanging lantern (soul or normal). */
    private static BlockState lantern(boolean soul) {
        return (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState().setValue(LanternBlock.HANGING, true);
    }

    /** Theme name. */
    public String label() {
        return label;
    }

    /** Theme colour. */
    public ChatFormatting color() {
        return color;
    }

    /** True if floors of this theme are caves. */
    public boolean cave() {
        return cave;
    }

    /** Pool fluid for this theme, or null. */
    public Block fluid() {
        return fluid;
    }

    /** Wall blocks (weighted). */
    public BlockState[] wall() {
        return wall;
    }

    /** Floor blocks (weighted). */
    public BlockState[] floor() {
        return floor;
    }

    /** Ceiling blocks (weighted). */
    public BlockState[] ceiling() {
        return ceiling;
    }

    /** Light block. */
    public BlockState light() {
        return light;
    }

    /** True if the light is a lantern hanging below the ceiling, false if it replaces a ceiling block. */
    public boolean hangLight() {
        return hangLight;
    }

    /** Rubble block. */
    public BlockState rubble() {
        return rubble;
    }

    /** Pillar block. */
    public BlockState pillar() {
        return pillar;
    }

    /** Smallest room size. */
    public int roomMin() {
        return roomMin;
    }

    /** Largest room size. */
    public int roomMax() {
        return roomMax;
    }

    /** Number of rooms. */
    public int roomCount() {
        return roomCount;
    }

    /** Corridor width. */
    public int corridorWidth() {
        return corridorWidth;
    }

    /** Lowest ceiling height. */
    public int heightMin() {
        return heightMin;
    }

    /** Highest ceiling height. */
    public int heightMax() {
        return heightMax;
    }

    /** Theme of a difficulty band. */
    public static FloorTheme ofBand(int band) {
        return switch (Math.max(0, Math.min(5, band))) {
            case 0 -> CATACOMBS;
            case 1 -> CAVERNS;
            case 2 -> FLOODED;
            case 3 -> LAVA_HALLS;
            case 4 -> ASHEN;
            default -> VOID_SANCTUM;
        };
    }

    /** Regular mob types for the theme: the band's rank pool plus a few thematic extras. */
    public List<EntityType<? extends Mob>> mobPool(GateRank rank) {
        List<EntityType<? extends Mob>> pool = new ArrayList<>(rank.mobPool());
        switch (this) {
            case FLOODED -> {
                pool.add(MobTypes.DROWNED);
                pool.add(MobTypes.DROWNED);
            }
            case ASHEN -> pool.add(MobTypes.BLAZE);
            case LAVA_HALLS -> pool.add(MobTypes.MAGMA_CUBE);
            default -> {
            }
        }
        return pool;
    }
}
