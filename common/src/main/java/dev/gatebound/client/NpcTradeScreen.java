package dev.gatebound.client;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.trade.NpcBuyPayload;
import dev.gatebound.trade.NpcOffersPayload;
import dev.gatebound.trade.TradeItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Custom shop window for the Gatebound NPCs: one row per offer with item, price, stock and a Buy button. */
public final class NpcTradeScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int ROW_H = 30;
    private static final int HEADER_H = 40;
    private static final int FOOTER_H = 20;

    private NpcOffersPayload data;
    private final List<StatButton> buttons = new ArrayList<>();
    private int left;
    private int top;
    private int panelH;

    public NpcTradeScreen(NpcOffersPayload data) {
        super(Component.translatable(data.broker() ? "npc.gatebound.broker" : "npc.gatebound.scholar"));
        this.data = data;
    }

    public int npcId() {
        return data.entityId();
    }

    public void update(NpcOffersPayload fresh) {
        this.data = fresh;
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        buttons.clear();
        panelH = HEADER_H + data.offers().size() * ROW_H + FOOTER_H;
        left = (this.width - PANEL_W) / 2;
        top = (this.height - panelH) / 2;
        for (int i = 0; i < data.offers().size(); i++) {
            int index = i;
            StatButton b = new StatButton(left + PANEL_W - 54, rowY(i) + 3, 44, 20,
                    Component.translatableWithFallback("gatebound.npc.buy", "Buy"), this.font,
                    () -> NetworkManager.sendToServer(new NpcBuyPayload(data.entityId(), index)));
            buttons.add(addRenderableWidget(b));
        }
    }

    private int rowY(int i) {
        return top + HEADER_H + i * ROW_H;
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.BARRIER : BuiltInRegistries.ITEM.getOptional(key).orElse(Items.BARRIER);
    }

    private static int count(Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }
        Inventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + PANEL_W, top + panelH, 0xF0101018);
        g.outline(left, top, PANEL_W, panelH, 0xFFE0B040);
        for (int i = 0; i < data.offers().size(); i++) {
            int y = rowY(i);
            g.fill(left + 6, y + 1, left + PANEL_W - 6, y + ROW_H - 1, i % 2 == 0 ? 0x40FFFFFF : 0x20FFFFFF);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, top + 8, 0xFFFFD866);
        int shards = count(TradeItems.GATE_SHARD.get());
        g.fakeItem(new ItemStack(TradeItems.GATE_SHARD.get()), left + 10, top + 18);
        g.text(this.font, Component.translatableWithFallback("gatebound.npc.shards", "Gate Shards: %s", shards).getString(),
                left + 30, top + 22, 0xFF8FD0FF);

        ItemStack hover = null;
        for (int i = 0; i < data.offers().size(); i++) {
            NpcOffersPayload.OfferView o = data.offers().get(i);
            int y = rowY(i);
            Item resultItem = item(o.itemId());
            Item payItem = item(o.payId());
            ItemStack result = new ItemStack(resultItem, o.count());
            g.fakeItem(result, left + 10, y + 6);
            g.itemDecorations(this.font, result, left + 10, y + 6);
            if (mouseX >= left + 10 && mouseX < left + 26 && mouseY >= y + 6 && mouseY < y + 22) {
                hover = result;
            }
            String path = BuiltInRegistries.ITEM.getKey(resultItem).getPath();
            g.text(this.font, result.getHoverName().getString(), left + 32, y + 4, 0xFFFFFFFF);
            String desc = Component.translatableWithFallback("npc.gatebound.desc." + path, "").getString();
            if (!desc.isEmpty()) {
                g.text(this.font, desc, left + 32, y + 15, 0xFF888899);
            }
            // Price and stock, right aligned left of the button.
            int have = count(payItem);
            boolean afford = have >= o.price();
            boolean inStock = o.stock() > 0;
            int px = left + PANEL_W - 160;
            g.fakeItem(new ItemStack(payItem), px, y + 6);
            g.text(this.font, "x" + o.price(), px + 18, y + 11, afford ? 0xFFFFFFFF : 0xFFFF6666);
            g.text(this.font, Component.translatableWithFallback("gatebound.npc.stock", "Stock %s/%s", o.stock(), o.maxStock()).getString(),
                    px + 44, y + 11, inStock ? 0xFF99DD99 : 0xFFFF6666);
            buttons.get(i).active = afford && inStock;
        }
        if (hover != null) {
            g.setTooltipForNextFrame(this.font, hover, mouseX, mouseY);
        }
        g.centeredText(this.font, Component.translatableWithFallback("gatebound.npc.hint", "Prices scale with your level. Stock restocks daily.").getString(),
                this.width / 2, top + panelH - 14, 0xFF777777);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
