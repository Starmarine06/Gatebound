package dev.gatebound.item;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.data.Element;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staffs: the Mage's weapon. One per element, each a plain staff topped with that element's orb. A Mage deals melee damage
 * only with a staff (see MageCore); swords, axes and the rest hit for nothing in their hands.
 */
public final class StaffItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);

    /** Melee damage and attack speed modifier of a staff (a sword uses 3 and -2.4): a stick-and-orb is a poor club. */
    public static final double STAFF_DAMAGE = 2.0;
    public static final double STAFF_SPEED = -2.4;

    public static final Map<Element, RegistrySupplier<Item>> STAFFS = new LinkedHashMap<>();

    static {
        for (Element element : Element.values()) {
            String path = element.id() + "_staff";
            STAFFS.put(element, ITEMS.register(path, () -> new Item(new Item.Properties().arch$tab(GateboundRegistries.MAIN_TAB)
                    .stacksTo(1)
                    .attributes(ItemAttributeModifiers.builder()
                            .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, STAFF_DAMAGE,
                                    AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                            .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, STAFF_SPEED,
                                    AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                            .build())
                    .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, path))))));
        }
    }

    /** Static holder; not instantiable. */
    private StaffItems() {
    }

    /** Registers the staff items. */
    public static void init() {
        ITEMS.register();
    }

    /** True if the stack is a staff. */
    public static boolean isStaff(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (RegistrySupplier<Item> staff : STAFFS.values()) {
            if (stack.is(staff.get())) {
                return true;
            }
        }
        return false;
    }
}
