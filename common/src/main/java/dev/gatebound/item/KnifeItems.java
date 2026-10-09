package dev.gatebound.item;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=26.1
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Knives: the Rogue's weapon. Tiered like swords (wood to netherite), fast and light, and they hit harder from behind
 * (see RogueSkills.onHurt). Also holds the Rogue's gear rules: only chainmail armour, and only knives deal damage.
 */
public final class KnifeItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);

    /** Base damage of a knife before the material bonus (a sword uses 3) and its attack speed modifier (a sword uses -2.4). */
    public static final float KNIFE_DAMAGE = 1.5f;
    public static final float KNIFE_SPEED = -1.6f;

    public static final Map<String, RegistrySupplier<Item>> KNIVES = new LinkedHashMap<>();

    public static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    //? if >=26.1 {
    static {
        add("wooden", ToolMaterial.WOOD, false);
        add("stone", ToolMaterial.STONE, false);
        add("copper", ToolMaterial.COPPER, false);
        add("iron", ToolMaterial.IRON, false);
        add("golden", ToolMaterial.GOLD, false);
        add("diamond", ToolMaterial.DIAMOND, false);
        add("netherite", ToolMaterial.NETHERITE, true);
    }
    //?} else {
    /*static { // no copper tier before 1.21.4
        add("wooden", net.minecraft.world.item.Tiers.WOOD, false);
        add("stone", net.minecraft.world.item.Tiers.STONE, false);
        add("iron", net.minecraft.world.item.Tiers.IRON, false);
        add("golden", net.minecraft.world.item.Tiers.GOLD, false);
        add("diamond", net.minecraft.world.item.Tiers.DIAMOND, false);
        add("netherite", net.minecraft.world.item.Tiers.NETHERITE, true);
    }
    *///?}

    private KnifeItems() {
    }

    //? if >=26.1 {
    private static void add(String tier, ToolMaterial material, boolean fireproof) {
        String path = tier + "_knife";
        KNIVES.put(path, ITEMS.register(path, () -> {
            Item.Properties props = new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB)
                    .sword(material, KNIFE_DAMAGE, KNIFE_SPEED)
                    .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path)));
            return new Item(fireproof ? props.fireResistant() : props);
        }));
    }
    //?} else {
    /*private static void add(String tier, net.minecraft.world.item.Tier material, boolean fireproof) {
        String path = tier + "_knife";
        KNIVES.put(path, ITEMS.register(path, () -> {
            Item.Properties props = new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB)
                    .attributes(net.minecraft.world.item.component.ItemAttributeModifiers.builder()
                            .add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                                            KNIFE_DAMAGE + material.getAttackDamageBonus(),
                                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                                    net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND)
                            .add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
                                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(Item.BASE_ATTACK_SPEED_ID,
                                            KNIFE_SPEED,
                                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                                    net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND)
                            .build());
            return new net.minecraft.world.item.SwordItem(material, fireproof ? props.fireResistant() : props);
        }));
    }
    *///?}

    public static void init() {
        ITEMS.register();
    }

    public static boolean isKnife(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (RegistrySupplier<Item> knife : KNIVES.values()) {
            if (stack.is(knife.get())) {
                return true;
            }
        }
        return false;
    }

    /** Anything that is meant to hurt things: carries an attack damage bonus (swords, axes, tools, mace, spears...). */
    public static boolean isMeleeWeapon(ItemStack stack) {
        return !isKnife(stack) && hasAttackDamage(stack);
    }

    /** True for any held item that carries an attack damage bonus, knives and staffs included. */
    public static boolean hasAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemAttributeModifiers.Entry entry : stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
            if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChainmail(ItemStack stack) {
        return stack.is(Items.CHAINMAIL_HELMET) || stack.is(Items.CHAINMAIL_CHESTPLATE)
                || stack.is(Items.CHAINMAIL_LEGGINGS) || stack.is(Items.CHAINMAIL_BOOTS);
    }

    /** Armour a Rogue may not wear: any armour piece that is not chainmail. */
    public static boolean isForbiddenArmor(ItemStack stack) {
        if (stack.isEmpty() || isChainmail(stack)) {
            return false;
        }
        return stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR);
    }
}
