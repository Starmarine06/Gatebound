package dev.gatebound.client;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.Stat;
import dev.gatebound.network.ChooseClassPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** First-join class selection. Cannot be dismissed with Escape; the server accepts exactly one choice. */
public final class AwakeningScreen extends Screen {
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    public AwakeningScreen() {
        super(Component.translatable("screen.gatebound.awakening.title"));
    }

    @Override
    protected void init() {
        GateboundClass[] classes = GateboundClass.values();
        int total = classes.length * BUTTON_HEIGHT + (classes.length - 1) * GAP;
        int top = (this.height - total) / 2 + 10;
        int left = (this.width - BUTTON_WIDTH) / 2;
        for (int i = 0; i < classes.length; i++) {
            GateboundClass c = classes[i];
            addRenderableWidget(Button.builder(Component.translatable(c.nameKey()), button -> choose(c))
                    .bounds(left, top + i * (BUTTON_HEIGHT + GAP), BUTTON_WIDTH, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(describe(c)))
                    .build());
        }
    }

    private static Component describe(GateboundClass c) {
        Component text = Component.translatable(c.descriptionKey());
        StringBuilder bonus = new StringBuilder();
        for (Stat s : Stat.values()) {
            int b = c.bonus(s);
            if (b > 0) {
                bonus.append("\n+").append(b).append(' ').append(Component.translatable("stat.gatebound." + s.id()).getString());
            }
        }
        return Component.empty().append(text).append(Component.literal(bonus.toString()));
    }

    private void choose(GateboundClass c) {
        NetworkManager.sendToServer(new ChooseClassPayload(c.id()));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.gatebound.awakening.subtitle").getString(),
                this.width / 2, 40, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
