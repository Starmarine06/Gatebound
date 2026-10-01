package dev.gatebound.data;

import dev.gatebound.Gatebound;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Maps Gatebound stats onto vanilla attributes with permanent modifiers, replaced on every refresh. */
public final class StatEffects {
    private StatEffects() {
    }

    public static void apply(ServerPlayer player, PlayerData data) {
        set(player, Attributes.ATTACK_DAMAGE, "strength_damage", bonus(data, Stat.STRENGTH) * 0.15, Operation.ADD_VALUE);
        set(player, Attributes.MOVEMENT_SPEED, "agility_speed", bonus(data, Stat.AGILITY) * 0.004, Operation.ADD_MULTIPLIED_BASE);
        set(player, Attributes.ATTACK_SPEED, "agility_attack_speed", bonus(data, Stat.AGILITY) * 0.02, Operation.ADD_VALUE);
        set(player, Attributes.MAX_HEALTH, "vitality_health", bonus(data, Stat.VITALITY) * 1.0, Operation.ADD_VALUE);
        set(player, Attributes.LUCK, "luck_luck", bonus(data, Stat.LUCK) * 0.25, Operation.ADD_VALUE);
        // Intellect has no vanilla attribute; it drives mana in the skills phase.
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    private static int bonus(PlayerData data, Stat stat) {
        return data.get(stat) - Stat.BASE_VALUE;
    }

    private static void set(ServerPlayer player, Holder<Attribute> attribute, String key, double amount, Operation op) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, key);
        if (amount == 0) {
            instance.removeModifier(id);
        } else {
            instance.addOrReplacePermanentModifier(new AttributeModifier(id, amount, op));
        }
    }
}
