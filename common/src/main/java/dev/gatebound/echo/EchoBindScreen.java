package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown when you right-click a soul body: pick the group (role) the new echo joins, or leave the body. */
final class EchoBindScreen extends Screen {
    private static final String[] ORDERS = {"Follow", "Guard", "Seek gates", "Guide to gate"};
    private static final String[] HINTS = {
            "Trails behind you and fights whatever attacks you.",
            "Stays close and attacks any threat near you.",
            "Scouts for a gate and reports it to you.",
            "Leads you to the selected gate."};
    private static final ChatFormatting[] COLORS = {
            ChatFormatting.GOLD, ChatFormatting.AQUA, ChatFormatting.GREEN, ChatFormatting.LIGHT_PURPLE};

    private final EchoBindMenuPayload data;

    EchoBindScreen(EchoBindMenuPayload data) {
        super(Component.literal("Bind " + data.name()));
        this.data = data;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 130;
        int top = this.height / 2 - 52;
        for (int g = 0; g < EchoRoster.GROUPS; g++) {
            int group = g;
            int mode = g < data.modes().size() ? data.modes().get(g) : 0;
            int count = g < data.counts().size() ? data.counts().get(g) : 0;
            Button b = Button.builder(Component.literal("Group " + (g + 1) + ": " + ORDERS[Math.max(0, Math.min(3, mode))]
                            + " (" + count + ")").withStyle(COLORS[g]), btn -> {
                        NetworkManager.sendToServer(new EchoBindPayload(data.entityId(), group));
                        onClose();
                    })
                    .bounds(left, top + g * 24, 260, 20)
                    .tooltip(Tooltip.create(Component.literal(HINTS[Math.max(0, Math.min(3, mode))])))
                    .build();
            b.active = data.free() > 0;
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(Component.literal("Leave it"), b -> onClose())
                .bounds(left, top + 4 * 24 + 6, 260, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = this.height / 2 - 52;
        graphics.centeredText(this.font, Component.literal("Bind " + data.name() + " as an Echo?").withStyle(ChatFormatting.BOLD),
                this.width / 2, top - 34, 0xFFFFFFFF);
        if (data.free() > 0) {
            graphics.centeredText(this.font, "Pick its group. The group's order decides what it does. Free slots: " + data.free(),
                    this.width / 2, top - 18, 0xFFAAAAAA);
        } else {
            graphics.centeredText(this.font, Component.literal("No free echo slot. Use an Echo Shard or release an echo.")
                    .withStyle(ChatFormatting.RED), this.width / 2, top - 18, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
