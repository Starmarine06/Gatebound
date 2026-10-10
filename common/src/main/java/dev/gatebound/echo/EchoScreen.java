package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Echoes command screen. Echoes belong to four colour-coded groups; each group has one order
 * (Guard, Attack, Seek, Guide). Echoes are put into groups with one click on a group button on their row, or in bulk
 * (even split, group by mob type, everyone into one group). Found gates are listed below for Guides to lead to.
 */
final class EchoScreen extends Screen {
    private static final int ROW = 22;
    private static final int PER_PAGE = 8;
    private static final String[] ORDERS = {"Guard", "Attack", "Seek", "Guide", "", "Hold"};
    private static final String[] HINTS = {
            "Stays close and only fights what has just hurt you or it. Never wanders far.",
            "Hunts the nearest enemies around it and what you fight.",
            "Scouts for a gate with a wide sweep and reports it to you.",
            "Waits beside you; once a gate is found, right-click it and it leads you there.",
            "",
            "Stays exactly where it is, never follows or teleports to you, and only fights what comes close. Soul Swap can still call it."};
    private static final ChatFormatting[] GROUP_COLORS = {
            ChatFormatting.GOLD, ChatFormatting.AQUA, ChatFormatting.GREEN, ChatFormatting.LIGHT_PURPLE};
    private static final int[] GROUP_RGB = {0xFFFFAA00, 0xFF55FFFF, 0xFF55FF55, 0xFFFF55FF};

    private EchoRosterPayload data;
    private int page;
    private int viewY = -1;
    private int selY = -1;
    private EchoRosterPayload.Row selRow;
    private int groupsY;
    private int bulkY;
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

    private int pages() {
        return Math.max(1, (data.rows().size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void send(int kind, int a, int b) {
        NetworkManager.sendToServer(new EchoOrderPayload(kind, a, b));
    }

    private Component orderLabel(int mode, int selected) {
        Component base = Component.literal(ORDERS[mode]);
        return mode == selected ? Component.literal("> ").append(base).withStyle(ChatFormatting.GOLD) : base;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 160;
        int y = 34;
        if (!data.viewing().isEmpty()) {
            viewY = y;
            addRenderableWidget(Button.builder(Component.literal("Eye: Scout"), b -> send(3, 0, 0))
                    .bounds(left, y + 12, 100, 20).tooltip(Tooltip.create(Component.literal("The echo you are watching through searches for gates."))).build());
            addRenderableWidget(Button.builder(Component.literal("Eye: Go to gate"), b -> send(3, 1, 0))
                    .bounds(left + 106, y + 12, 108, 20).tooltip(Tooltip.create(Component.literal("It flies or runs to the selected gate."))).build());
            addRenderableWidget(Button.builder(Component.literal("Stop viewing"), b -> {
                send(3, 2, 0);
                onClose();
            }).bounds(left + 220, y + 12, 100, 20).build());
            y += 40;
        } else {
            viewY = -1;
        }

        // The echo picked with the select key gets its own order, overriding its group.
        selY = -1;
        selRow = null;
        for (EchoRosterPayload.Row r : data.rows()) {
            if (r.selected()) {
                selRow = r;
            }
        }
        if (selRow != null) {
            selY = y;
            int slot = data.rows().indexOf(selRow);
            for (int m : new int[]{0, 1, 2, 3, 5}) {
                int mode = m;
                int col = m == 5 ? 4 : m;
                addRenderableWidget(Button.builder(orderLabel(m, selRow.personal()), b -> send(7, slot, mode))
                        .bounds(left + col * 52, y + 12, 50, 20)
                        .tooltip(Tooltip.create(Component.literal("Give only this echo this order. " + HINTS[m]))).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Group order"), b -> send(7, slot, 4))
                    .bounds(left + 262, y + 12, 58, 20)
                    .tooltip(Tooltip.create(Component.literal("Clear its own order: it follows its group again."))).build());
            y += 40;
        }

        // Group orders.
        groupsY = y;
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            int group = g;
            int selected = g < data.groupModes().size() ? data.groupModes().get(g) : 0;
            for (int m = 0; m < 4; m++) {
                int mode = m;
                addRenderableWidget(Button.builder(orderLabel(m, selected), b -> send(0, group, mode))
                        .bounds(left + 104 + m * 54, y + g * ROW, 52, 20)
                        .tooltip(Tooltip.create(Component.literal(HINTS[m])))
                        .build());
            }
        }
        y += EchoRoster.GROUPS * ROW + 16;

        // Bulk assignment.
        bulkY = y;
        addRenderableWidget(Button.builder(Component.literal("Even split"), b -> send(4, 0, 0))
                .bounds(left, y, 76, 20)
                .tooltip(Tooltip.create(Component.literal("Spread all echoes evenly over the four groups."))).build());
        addRenderableWidget(Button.builder(Component.literal("By type"), b -> send(6, 0, 0))
                .bounds(left + 80, y, 64, 20)
                .tooltip(Tooltip.create(Component.literal("Put echoes of the same mob type in the same group."))).build());
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            int group = g;
            addRenderableWidget(Button.builder(Component.literal("" + (g + 1)).withStyle(GROUP_COLORS[g]), b -> send(5, group, 0))
                    .bounds(left + 200 + g * 30, y, 28, 20)
                    .tooltip(Tooltip.create(Component.literal("Move every echo into group " + (g + 1) + ".")))
                    .build());
        }
        y += 28;

        // Echo rows for the current page, each with a direct button per group.
        echoesY = y;
        page = Math.max(0, Math.min(page, pages() - 1));
        int first = page * PER_PAGE;
        int shown = Math.max(0, Math.min(PER_PAGE, data.rows().size() - first));
        for (int i = 0; i < shown; i++) {
            EchoRosterPayload.Row row = data.rows().get(first + i);
            int slot = first + i;
            for (int g = 0; g < EchoRoster.GROUPS; g++) {
                int group = g;
                boolean current = row.group() == g;
                Button b = Button.builder(Component.literal("G" + (g + 1)).withStyle(current ? GROUP_COLORS[g] : ChatFormatting.GRAY),
                                btn -> send(1, slot, group))
                        .bounds(left + 150 + g * 43, y + i * ROW, 41, 20)
                        .tooltip(Tooltip.create(Component.literal("Put this echo in group " + (g + 1) + ".")))
                        .build();
                b.active = row.unlocked() && !current;
                addRenderableWidget(b);
            }
        }
        y += Math.max(shown, 1) * ROW;
        if (pages() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(left + 100, y, 24, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(left + 196, y, 24, 20).build()).active = page < pages() - 1;
            y += ROW;
        }
        y += 16;

        // Found gates.
        gatesY = y;
        int gateShown = Math.min(data.gates().size(), 5);
        for (int i = 0; i < gateShown; i++) {
            EchoRosterPayload.GateRow gate = data.gates().get(i);
            int index = i;
            String dist = gate.distance() < 0 ? "other dimension" : gate.distance() + " blocks";
            Component text = Component.literal((gate.selected() ? "> " : "") + gate.label() + " - " + dist + " (found by " + gate.by() + ")");
            addRenderableWidget(Button.builder(gate.selected() ? text.copy().withStyle(ChatFormatting.GOLD) : text, b -> send(2, index, 0))
                    .bounds(left, y + i * ROW, 320, 20)
                    .tooltip(Tooltip.create(Component.literal("Select this gate. Groups on Guide will lead you to it.")))
                    .build());
        }
        y += Math.max(gateShown, 1) * ROW + 8;
        addRenderableWidget(Button.builder(Component.literal("Summon / Dismiss all"), b -> NetworkManager.sendToServer(new EchoTogglePayload()))
                .bounds(left, y, 158, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + 162, y, 158, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.width / 2 - 160;
        graphics.text(this.font, Component.literal("Echoes").withStyle(ChatFormatting.BOLD), left, 14, 0xFFFFFFFF);
        graphics.text(this.font, "Each group follows one order. Echoes in a group obey together.", left + 50, 14, 0xFF999999);
        if (viewY >= 0) {
            graphics.text(this.font, "Viewing through " + data.viewing(), left, viewY, 0xFFFFD27C);
        }
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            graphics.text(this.font, Component.literal("Group " + (g + 1) + " (" + countIn(g) + ")").withStyle(GROUP_COLORS[g]),
                    left, groupsY + g * ROW + 6, 0xFFFFFFFF);
        }
        if (selRow != null) {
            graphics.text(this.font, "Selected: " + selRow.name() + (selRow.personal() < 0 ? " (follows its group)" : " (own order)"),
                    this.width / 2 - 160, selY, 0xFFFFAA00);
        }
        graphics.text(this.font, "Assign:", left + 148, bulkY - 12, 0xFFFFD27C);
        graphics.text(this.font, "All to group:", left + 200, bulkY - 12, 0xFF999999);
        graphics.text(this.font, "Your echoes (click a group on each row)", left, echoesY - 12, 0xFFFFD27C);
        if (data.rows().isEmpty()) {
            graphics.text(this.font, "None yet. Defeat monsters as a Summoner to bind them.", left, echoesY + 6, 0xFFAAAAAA);
        }
        int first = page * PER_PAGE;
        int shown = Math.max(0, Math.min(PER_PAGE, data.rows().size() - first));
        for (int i = 0; i < shown; i++) {
            EchoRosterPayload.Row row = data.rows().get(first + i);
            int rowY = echoesY + i * ROW + 6;
            graphics.fill(left - 4, rowY - 6, left - 1, rowY + 14, GROUP_RGB[Math.max(0, Math.min(3, row.group()))]);
            String name = this.font.plainSubstrByWidth((first + i + 1) + ". " + row.name() + (row.active() ? "" : " (away)"), 138);
            int color = row.unlocked() ? (row.active() ? 0xFF7CFF7C : 0xFFFFFFFF) : 0xFF777777;
            graphics.text(this.font, name, left, rowY, color);
        }
        if (pages() > 1) {
            int y = echoesY + Math.max(shown, 1) * ROW + 6;
            graphics.centeredText(this.font, "Page " + (page + 1) + "/" + pages(), this.width / 2, y, 0xFFAAAAAA);
        }
        graphics.text(this.font, "Found gates (pick one for Guides to lead you to)", left, gatesY - 12, 0xFFFFD27C);
        if (data.gates().isEmpty()) {
            graphics.text(this.font, "None yet. Send a group to Seek and wait for a report.", left, gatesY + 6, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
