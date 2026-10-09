package dev.gatebound.item;

import dev.gatebound.compat.Compat;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.gatebound.Gatebound;
import dev.gatebound.registry.GateboundRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A quiver holds up to ten stacks of arrows (640). Right-click packs every plain arrow in your inventory into it;
 * sneak + right-click takes a stack out. While you hold a bow and carry no arrows, the quiver turns into a stack of
 * arrows in its own inventory slot (so a full inventory never matters); the rest of the arrows wait inside that stack's
 * data and are fed in as you shoot. Put the bow away (or log out or die) and it turns back into the quiver.
 */
public final class QuiverItem extends Item {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Gatebound.MOD_ID, Registries.ITEM);
    public static final RegistrySupplier<Item> QUIVER = ITEMS.register("quiver", () -> new QuiverItem(new Item.Properties()
            .arch$tab(GateboundRegistries.MAIN_TAB).stacksTo(1)
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "quiver")))));

    public static final int CAPACITY = 640;
    private static final String COUNT = "arrows";
    private static final String LOAN = "quiver_loan";
    private static final String RESERVE = "quiver_reserve";
    private static final String LOAN_ID = "quiver_loan_id";

    /** A quiver currently turned into arrows: the slot it sits in and how many arrows wait behind them. */
    private static final class Stash {
        final String id;
        int slot;
        int reserve;

        Stash(String id, int slot, int reserve) {
            this.id = id;
            this.slot = slot;
            this.reserve = reserve;
        }
    }

    private static final Map<UUID, Stash> STASH = new HashMap<>();

    private QuiverItem(Properties properties) {
        super(properties);
    }

    public static void init() {
        ITEMS.register();
        TickEvent.SERVER_POST.register(QuiverItem::tick);
        PlayerEvent.PLAYER_QUIT.register(player -> restore(player));
        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                restore(player); // so the quiver, not a bare stack, is what drops
            }
            return EventResult.pass();
        });
        PlayerEvent.PLAYER_RESPAWN.register((player, conquered, reason) -> STASH.remove(player.getUUID()));
    }

    // ---------------------------------------------------------------- stored count

    public static int stored(ItemStack quiver) {
        CustomData data = quiver.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : data.copyTag().getIntOr(COUNT, 0);
    }

    private static void setStored(ItemStack quiver, int count) {
        int n = Math.max(0, Math.min(CAPACITY, count));
        CustomData.update(DataComponents.CUSTOM_DATA, quiver, tag -> tag.putInt(COUNT, n));
        quiver.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Arrows: " + n + " / " + CAPACITY).withStyle(ChatFormatting.GRAY))));
    }

    private static ItemStack makeQuiver(int count) {
        ItemStack q = new ItemStack(QUIVER.get());
        setStored(q, count);
        return q;
    }

    private static boolean isLoan(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(LOAN, false);
    }

    private static int reserveOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : data.copyTag().getIntOr(RESERVE, 0);
    }

    private static void setReserve(ItemStack stack, int reserve) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(RESERVE, reserve));
    }

    private static boolean plainArrow(ItemStack stack) {
        return stack.is(Items.ARROW) && !isLoan(stack);
    }

    // ---------------------------------------------------------------- use

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack quiver = player.getItemInHand(hand);
        int have = stored(quiver);
        if (player.isShiftKeyDown()) {
            if (have <= 0) {
                Compat.overlay(player, Component.literal("The quiver is empty."));
                return InteractionResult.FAIL;
            }
            int take = Math.min(64, have);
            ItemStack out = new ItemStack(Items.ARROW, take);
            if (!player.getInventory().add(out)) {
                Compat.overlay(player, Component.literal("Your inventory is full."));
                return InteractionResult.FAIL;
            }
            setStored(quiver, have - take);
            Compat.overlay(player, Component.literal("Took " + take + " arrows."));
            return InteractionResult.SUCCESS;
        }
        int room = CAPACITY - have;
        int moved = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && room > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (plainArrow(s)) {
                int n = Math.min(room, s.getCount());
                s.shrink(n);
                room -= n;
                moved += n;
            }
        }
        if (moved == 0) {
            Compat.overlay(player, Component.literal(room <= 0 ? "The quiver is full." : "No arrows to pack."));
            return InteractionResult.FAIL;
        }
        setStored(quiver, have + moved);
        Compat.overlay(player, Component.literal("Packed " + moved + " arrows."));
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- feeding the bow

    /** True if the player carries a quiver (or is using one as arrows right now). Used to draw it on their back. */
    public static boolean carriesQuiver(ServerPlayer p) {
        if (STASH.containsKey(p.getUUID())) {
            return true;
        }
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(QUIVER.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdingBow(ServerPlayer p) {
        return p.getMainHandItem().getItem() instanceof BowItem || p.getMainHandItem().getItem() instanceof CrossbowItem
                || p.getOffhandItem().getItem() instanceof BowItem || p.getOffhandItem().getItem() instanceof CrossbowItem;
    }

    /** The id marking which loan stack is the live one; any other loan stack (split off, moved through the cursor, stale) is only plain arrows. */
    private static String loanId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(LOAN_ID, "");
    }

    /** Index of the live loan stack for this stash, or -1. Every other loan stack is turned into plain arrows (its count, no reserve). */
    private static int findLoan(Inventory inv, Stash st) {
        int found = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.ARROW) || !isLoan(s)) {
                continue;
            }
            if (st != null && found < 0 && st.id.equals(loanId(s))) {
                found = i;
            } else {
                inv.setItem(i, new ItemStack(Items.ARROW, s.getCount()));
            }
        }
        return found;
    }

    /** Turns the arrows back into the quiver (in the same slot if it is free, otherwise anywhere). */
    private static void restore(ServerPlayer p) {
        Stash st = STASH.remove(p.getUUID());
        Inventory inv = p.getInventory();
        if (st == null) {
            findLoan(inv, null); // nothing is live: any loan stack left is just arrows
            return;
        }
        int slot = findLoan(inv, st);
        if (slot >= 0) {
            ItemStack s = inv.getItem(slot);
            inv.setItem(slot, makeQuiver(Math.min(CAPACITY, reserveOf(s) + s.getCount())));
        } else {
            ItemStack q = makeQuiver(st.reserve);
            if (st.slot >= 0 && st.slot < inv.getContainerSize() && inv.getItem(st.slot).isEmpty()) {
                inv.setItem(st.slot, q);
            } else {
                inv.add(q);
            }
        }
    }

    private static boolean carriesLoan(ServerPlayer p, Stash st) {
        ItemStack carried = p.containerMenu.getCarried();
        return carried.is(Items.ARROW) && isLoan(carried) && st.id.equals(loanId(carried));
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 4 != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive()) {
                continue;
            }
            Inventory inv = p.getInventory();
            boolean holding = holdingBow(p);
            Stash st = STASH.get(p.getUUID());
            int loanSlot = findLoan(inv, st); // also neutralises stale or split loan stacks
            if (st != null) {
                if (loanSlot < 0 && carriesLoan(p, st)) {
                    continue; // the stack is on the cursor: wait for it to land, restoring now would duplicate the reserve
                }
                if (!holding) {
                    restore(p);
                } else if (loanSlot >= 0) {
                    ItemStack s = inv.getItem(loanSlot);
                    int reserve = reserveOf(s);
                    if (s.getCount() < 24 && reserve > 0) {
                        int add = Math.min(64 - s.getCount(), reserve);
                        s.grow(add);
                        reserve -= add;
                        setReserve(s, reserve);
                    }
                    st.slot = loanSlot;
                    st.reserve = reserve;
                } else {
                    restore(p); // every arrow was shot (or the stack left the inventory): the quiver returns with what was behind it
                }
                continue;
            }
            if (!holding) {
                continue;
            }
            // Holding a bow with no arrows anywhere: the quiver becomes arrows in its own slot.
            boolean hasArrows = false;
            int quiverSlot = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.ARROW)) {
                    hasArrows = true;
                    break;
                }
                if (quiverSlot < 0 && s.is(QUIVER.get()) && stored(s) > 0) {
                    quiverSlot = i;
                }
            }
            if (hasArrows || quiverSlot < 0 || p.containerMenu.getCarried().is(Items.ARROW)) {
                continue;
            }
            int have = stored(inv.getItem(quiverSlot));
            int give = Math.min(64, have);
            String id = UUID.randomUUID().toString();
            ItemStack loan = new ItemStack(Items.ARROW, give);
            CustomData.update(DataComponents.CUSTOM_DATA, loan, tag -> {
                tag.putBoolean(LOAN, true);
                tag.putString(LOAN_ID, id);
                tag.putInt(RESERVE, have - give);
            });
            loan.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("From your quiver").withStyle(ChatFormatting.GRAY))));
            inv.setItem(quiverSlot, loan);
            STASH.put(p.getUUID(), new Stash(id, quiverSlot, have - give));
        }
    }
}
