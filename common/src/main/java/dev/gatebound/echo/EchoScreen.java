package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Echoes command screen: one row per bound echo with three orders (Follow, Guard, Seek Gate), a row to order all
 * echoes at once, and a summon/dismiss button. Every click is sent to the server, which answers with the new roster.
 */
final class EchoScreen extends Screen {
    private static final int ROW = 24;
    private static final String[] NAMES = {"Follow", "Guard", "Seek Gate"};
    private static final String[] HINTS = {
            "Trails behind you and fights whatever attacks you.",
            "Stays close and attacks any threat near you.",
            "Scouts for a gate and tells you where it is."};

    private List<EchoRosterPayload.Row> rows;

    EchoScreen(List<EchoRosterPayload.Row> rows) {
        super(Component.literal("Echoes"));
        this.rows = rows;
    }

    void update(List<EchoRosterPayload.Row> newRows) {
        this.rows = newRows;
        rebuildWidgets();
    }

    private Component orderLabel(int mode, int selected) {
        Component base = Component.literal(NAMES[mode]);
        return mode == selected ? Component.literal("> ").append(base).append(" <").withStyle(ChatFormatting.GOLD) : base;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 150;
        int y = 52;
        // Order everyone at once.
        for (int m = 0; m < 3; m++) {
            int mode = m;
            addRenderableWidget(Button.builder(Component.literal("All: " + NAMES[m]),
                            b -> NetworkManager.sendToServer(new EchoOrderPayload(-1, mode)))
                    .bounds(left + 100 + m * 68, 26, 66, 20)
                    .tooltip(Tooltip.create(Component.literal(HINTS[m])))
                    .build());
        }
        for (int i = 0; i < rows.size(); i++) {
            EchoRosterPayload.Row row = rows.get(i);
            int slot = i;
            for (int m = 0; m < 3; m++) {
                int mode = m;
                Button b = Button.builder(orderLabel(m, row.mode()),
                                btn -> NetworkManager.sendToServer(new EchoOrderPayload(slot, mode)))
                        .bounds(left + 100 + m * 68, y + i * ROW, 66, 20)
                        .tooltip(Tooltip.create(Component.literal(HINTS[m])))
                        .build();
                b.active = row.unlocked();
                addRenderableWidget(b);
            }
        }
        int bottom = y + Math.max(rows.size(), 1) * ROW + 6;
        addRenderableWidget(Button.builder(Component.literal("Summon / Dismiss all"),
                        b -> NetworkManager.sendToServer(new EchoTogglePayload()))
                .bounds(left, bottom, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + 154, bottom, 146, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.width / 2 - 150;
        graphics.text(this.font, Component.literal("Echoes").withStyle(ChatFormatting.BOLD), left, 14, 0xFFFFFFFF);
        graphics.text(this.font, "Order everyone:", left, 32, 0xFFAAAAAA);
        if (rows.isEmpty()) {
            graphics.text(this.font, "No echoes yet. Defeat monsters as a Summoner to bind them.", left, 56, 0xFFAAAAAA);
        }
        for (int i = 0; i < rows.size(); i++) {
            EchoRosterPayload.Row row = rows.get(i);
            int color = row.unlocked() ? (row.active() ? 0xFF7CFF7C : 0xFFFFFFFF) : 0xFF777777;
            String label = (i + 1) + ". " + row.name() + (row.active() ? "" : " (away)");
            graphics.text(this.font, label, left, 52 + i * ROW + 6, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
