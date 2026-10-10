package dev.gatebound.skill.fx;

import dev.gatebound.data.Element;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;

/**
 * The look and sound of one element: three particle types (main body, secondary wisps, accent sparkle), two dust colours
 * for exact-colour shapes, the block the element's props are made of, and its cast and impact sounds.
 */
public record Palette(String key, ParticleOptions main, ParticleOptions alt, ParticleOptions accent, int rgb1, int rgb2, String prop,
                      SoundEvent cast, SoundEvent hit) {

    /** Palette for a class (warrior, rogue, ranger, summoner) or, failing that, an element id. */
    public static Palette of(String key) {
        return switch (key) {
            case "warrior" -> new Palette("warrior", ParticleTypes.CRIT, ParticleTypes.SMOKE, ParticleTypes.FLAME, 0xD23A22, 0xC9CED6,
                    "minecraft:iron_block", SoundEvents.IRON_GOLEM_ATTACK, SoundEvents.ANVIL_LAND);
            case "rogue" -> new Palette("rogue", ParticleTypes.SQUID_INK, ParticleTypes.SMOKE,
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SLIME_BLOCK.defaultBlockState()), 0x6A2FA8, 0x58D63A,
                    "minecraft:black_concrete", SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.PLAYER_ATTACK_CRIT);
            case "ranger" -> new Palette("ranger", ParticleTypes.HAPPY_VILLAGER, ParticleTypes.COMPOSTER, ParticleTypes.CRIT, 0x4CB04A, 0xE8C34A,
                    "minecraft:moss_block", SoundEvents.ARROW_SHOOT, SoundEvents.ARROW_HIT);
            case "summoner" -> new Palette("summoner", ParticleTypes.SOUL_FIRE_FLAME, ParticleTypes.SOUL, ParticleTypes.END_ROD, 0x3FD8C8, 0xE8FFFB,
                    "minecraft:soul_sand", SoundEvents.AMETHYST_BLOCK_CHIME, SoundEvents.SOUL_ESCAPE.value());
            default -> of(Element.valueOf(key.toUpperCase(java.util.Locale.ROOT)));
        };
    }

    /** Palette of an element. */
    public static Palette of(Element element) {
        return switch (element) {
            case FIRE -> new Palette("fire", ParticleTypes.FLAME, ParticleTypes.SMALL_FLAME, ParticleTypes.LAVA, 0xFF6A1A, 0xFFC040,
                    "minecraft:magma_block", SoundEvents.FIRECHARGE_USE, SoundEvents.GENERIC_EXPLODE.value());
            case ICE -> new Palette("ice", ParticleTypes.SNOWFLAKE, ParticleTypes.WHITE_ASH,
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), 0x7FD8FF, 0xFFFFFF,
                    "minecraft:blue_ice", SoundEvents.AMETHYST_BLOCK_CHIME, SoundEvents.GLASS_BREAK);
            case LIGHTNING -> new Palette("lightning", ParticleTypes.ELECTRIC_SPARK, ParticleTypes.END_ROD, ParticleTypes.FIREWORK, 0xFFE84A, 0xFFFFFF,
                    "minecraft:glowstone", SoundEvents.LIGHTNING_BOLT_IMPACT, SoundEvents.LIGHTNING_BOLT_THUNDER);
            case WIND -> new Palette("wind", ParticleTypes.CLOUD, ParticleTypes.POOF, ParticleTypes.SWEEP_ATTACK, 0xC8F5E0, 0xFFFFFF,
                    "minecraft:white_stained_glass", SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value());
            case EARTH -> new Palette("earth", new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), ParticleTypes.CRIT, 0x8A6A3A, 0x6FA04A,
                    "minecraft:stone", SoundEvents.STONE_PLACE, SoundEvents.STONE_BREAK);
            case LIGHT -> new Palette("light", ParticleTypes.END_ROD, ParticleTypes.GLOW, ParticleTypes.ENCHANT, 0xFFFBE0, 0xFFE070,
                    "minecraft:sea_lantern", SoundEvents.AMETHYST_BLOCK_CHIME, SoundEvents.BEACON_POWER_SELECT);
            case DARK -> new Palette("dark", ParticleTypes.PORTAL, ParticleTypes.SQUID_INK, ParticleTypes.SOUL, 0x7A22C8, 0x1A0A2A,
                    "minecraft:obsidian", SoundEvents.ENDERMAN_TELEPORT, SoundEvents.WITHER_SHOOT);
        };
    }
}
