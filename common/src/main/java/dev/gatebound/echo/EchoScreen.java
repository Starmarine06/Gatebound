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
 * The Echoes command screen. Echoes belong to four groups; each group has one order (Follow, Guard, Seek Gate, Guide).
 * Below that: which group each echo is in, and the gates your echoes have found, so you can pick the one guides lead to.
 * Every click is sent to the server, which answers with the new state.
 */
final class EchoScreen extends Screen {
    private static final int ROW = 22;
    private static final String[] ORDERS = {"Follow", "Guard", "Seek", "Guide"};
    private static final String[] HINTS = {
            "Trails behind you and fights whatever attacks you.",
            "Stays close and attacks any threat near you.",
            "Scouts for a gate and reports it to you.",
            "Leads you to the selected gate."};

    private EchoRosterPayload data;
    private int groupsY;
    private int echoesY;
    private int gatesY;

    EchoScreen(EchoRosterPayload data) {
        super(Component.literal("Echoes"));
        this.data = data;
    }

    void update(EchoRosterPayload newData) {
        this.data = newData;
        rebuildWidgets();
    }

    private int countIn(int group) {
        int n = 0;
        for (EchoRosterPayload.Row r : data.rows()) {
            if (r.group() == group) {
                n++;
            }
        }
        return n;
    }

    private Component orderLabel(int mode, int selected) {
        Component base = Component.literal(ORDERS[mode]);
        return mode == selected ? Component.literal("> ").append(base).withStyle(ChatFormatting.GOLD) : base;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int y = 34;
        groupsY = y;
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            int group = g;
            int selected = g < data.groupModes().size() ? data.groupModes().get(g) : 0;
            for (int m = 0; m < 4; m++) {
                int mode = m;
                addRenderableWidget(Button.builder(orderLabel(m, selected),
                                b -> NetworkManager.sendToServer(new EchoOrderPayload(0, group, mode)))
                        .bounds(left + 104 + m * 54, y + g * ROW, 52, 20)
                        .tooltip(Tooltip.create(Component.literal(HINTS[m])))
                        .build());
            }
        }
        y += EchoRoster.GROUPS * ROW + 18;
        echoesY = y;
        int shown = Math.min(data.rows().size(), 12);
        for (int i = 0; i < shown; i++) {
            EchoRosterPayload.Row row = data.rows().get(i);
            int slot = i;
            Button b = Button.builder(Component.literal("Group " + (row.group() + 1)),
                            btn -> NetworkManager.sendToServer(new EchoOrderPayload(1, slot, (row.group() + 1) % EchoRoster.GROUPS)))
                    .bounds(left + 104, y + i * ROW, 60, 20)
                    .tooltip(Tooltip.create(Component.literal("Click to move this echo to the next group.")))
                    .build();
            b.active = row.unlocked();
            addRenderableWidget(b);
        }
        y += Math.max(shown, 1) * ROW + 18;
        gatesY = y;
        int gateShown = Math.min(data.gates().size(), 5);
        for (int i = 0; i < gateShown; i++) {
            EchoRosterPayload.GateRow gate = data.gates().get(i);
            int index = i;
            String dist = gate.distance() < 0 ? "other dimension" : gate.distance() + " blocks";
            Component text = Component.literal((gate.selected() ? "> " : "") + gate.label() + " - " + dist + " (found by " + gate.by() + ")");
            addRenderableWidget(Button.builder(gate.selected() ? text.copy().withStyle(ChatFormatting.GOLD) : text,
                            b -> NetworkManager.sendToServer(new EchoOrderPayload(2, index, 0)))
                    .bounds(left, y + i * ROW, 320, 20)
                    .tooltip(Tooltip.create(Component.literal("Select this gate. Groups on Guide will lead you to it.")))
                    .build());
        }
        y += Math.max(gateShown, 1) * ROW + 8;
        addRenderableWidget(Button.builder(Component.literal("Summon / Dismiss all"),
                        b -> NetworkManager.sendToServer(new EchoTogglePayload()))
                .bounds(left, y, 158, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + 162, y, 158, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.width / 2 - 160;
        graphics.text(this.font, Component.literal("Echoes").withStyle(ChatFormatting.BOLD), left, 14, 0xFFFFFFFF);
        graphics.text(this.font, "Give each group an order. Echoes in a group obey together.", left + 50, 14, 0xFF999999);
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            graphics.text(this.font, "Group " + (g + 1) + " (" + countIn(g) + ")", left, groupsY + g * ROW + 6, 0xFFFFFFFF);
        }
        graphics.text(this.font, "Your echoes", left, echoesY - 14, 0xFFFFD27C);
        if (data.rows().isEmpty()) {
            graphics.text(this.font, "None yet. Defeat monsters as a Summoner to bind them.", left, echoesY + 6, 0xFFAAAAAA);
        }
        for (int i = 0; i < Math.min(data.rows().size(), 12); i++) {
            EchoRosterPayload.Row row = data.rows().get(i);
            int color = row.unlocked() ? (row.active() ? 0xFF7CFF7C : 0xFFFFFFFF) : 0xFF777777;
            String label = (i + 1) + ". " + row.name();
            graphics.text(this.font, label, left, echoesY + i * ROW + 6, color);
            graphics.text(this.font, row.active() ? "out" : "away", left + 170, echoesY + i * ROW + 6, 0xFF999999);
        }
        graphics.text(this.font, "Found gates (pick one for Guides to lead you to)", left, gatesY - 14, 0xFFFFD27C);
        if (data.gates().isEmpty()) {
            graphics.text(this.font, "None yet. Send a group to Seek and wait for a report.", left, gatesY + 6, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
