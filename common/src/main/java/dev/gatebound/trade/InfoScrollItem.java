package dev.gatebound.trade;

import dev.gatebound.sound.GateboundSounds;
import dev.gatebound.data.Element;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.PlayerData;
import dev.gatebound.data.PlayerDataStore;
import dev.gatebound.data.Stat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;

/** Reads out the player's build and gives flavour advice about the nearest hostile mob. */
public class InfoScrollItem extends Item {
    /** Creates the info scroll item. */
    public InfoScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        try {
            report(sp);
        } catch (RuntimeException e) {
            sp.sendSystemMessage(Component.literal("[Gatebound] The scroll's ink runs and fades."));
            return InteractionResult.FAIL;
        }
        if (!player.hasInfiniteMaterials()) {
            player.getItemInHand(hand).shrink(1);
        }
        level.playSound(null, sp.blockPosition(), GateboundSounds.custom("item.scroll"), SoundSource.PLAYERS, 1f, 1f);
        return InteractionResult.SUCCESS;
    }

    /** Sends the player their level, class and stat report. */
    private static void report(ServerPlayer sp) {
        PlayerData d = PlayerDataStore.of(sp);
        GateboundClass cls = GateboundClass.byId(d.classId);
        sp.sendSystemMessage(Component.literal("[Gatebound] --- Info Scroll ---").withStyle(ChatFormatting.GOLD));

        MutableComponent head = Component.literal("Level " + d.level + " ");
        head.append(cls == null ? Component.literal("(unawakened)") : Component.translatable(cls.nameKey()));
        head.append(Component.literal(", " + d.statPoints + " unspent points"));
        sp.sendSystemMessage(head.withStyle(ChatFormatting.YELLOW));

        StringBuilder stats = new StringBuilder();
        for (Stat s : Stat.values()) {
            stats.append(s.id()).append(' ').append(d.get(s)).append("  ");
        }
        sp.sendSystemMessage(Component.literal(stats.toString().trim()).withStyle(ChatFormatting.WHITE));

        MutableComponent elems = Component.literal("Elements: ");
        if (d.elements.isEmpty()) {
            elems.append("none");
        } else {
            boolean first = true;
            for (String id : d.elements) {
                Element e = Element.byId(id);
                if (e == null) {
                    continue;
                }
                if (!first) {
                    elems.append(", ");
                }
                elems.append(Component.translatable(e.nameKey()));
                first = false;
            }
        }
        sp.sendSystemMessage(elems.withStyle(ChatFormatting.LIGHT_PURPLE));

        List<Monster> near = sp.level().getEntitiesOfClass(Monster.class, sp.getBoundingBox().inflate(32));
        near.sort(Comparator.comparingDouble(m -> m.distanceToSqr(sp)));
        if (near.isEmpty()) {
            sp.sendSystemMessage(Component.literal("No hostile presence nearby. Rest while you can.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            Monster m = near.get(0);
            String path = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath();
            sp.sendSystemMessage(Component.literal("Nearest foe: ").append(m.getName())
                    .append(Component.literal(" (" + (int) Math.sqrt(m.distanceToSqr(sp)) + " blocks). " + tip(path, m.getMaxHealth())))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** Combat tip for a mob type. */
    private static String tip(String type, float maxHealth) {
        String flavour = switch (type) {
            case "zombie", "husk", "drowned" -> "Slow and sturdy; a perfectly timed parry staggers it fast.";
            case "skeleton", "stray", "bogged" -> "Fragile up close; its arrows can be parried if your timing is true.";
            case "creeper" -> "Hit it and back off; posture means little before it blows.";
            case "spider", "cave_spider" -> "Quick but frail; a few strong hits break its guard.";
            case "enderman" -> "Resists the usual tricks; break its posture and finish it fast.";
            case "witch" -> "Closes distance poorly; rush it and keep breaking its posture.";
            case "blaze", "ghast" -> "Fire-born; water-attuned magic bites deep.";
            default -> "Watch its swings, parry on the flash, and finish it once its posture breaks.";
        };
        return flavour + (maxHealth >= 50 ? " It is a formidable one." : "");
    }
}
