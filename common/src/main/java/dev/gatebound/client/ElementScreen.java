package dev.gatebound.client;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.Element;
import dev.gatebound.network.ChooseElementPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Mage affinity selection. Cannot be dismissed with Escape; the server accepts exactly one pick. */
public final class ElementScreen extends Screen {
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    /** Creates the element-choice screen. */
    public ElementScreen() {
        super(Component.translatable("screen.gatebound.element.title"));
    }

    @Override
    protected void init() {
        Element[] elements = Element.values();
        int total = elements.length * BUTTON_HEIGHT + (elements.length - 1) * GAP;
        int top = (this.height - total) / 2 + 10;
        int left = (this.width - BUTTON_WIDTH) / 2;
        for (int i = 0; i < elements.length; i++) {
            Element e = elements[i];
            addRenderableWidget(Button.builder(Component.translatable(e.nameKey()), button -> choose(e))
                    .bounds(left, top + i * (BUTTON_HEIGHT + GAP), BUTTON_WIDTH, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable(e.descriptionKey())))
                    .build());
        }
    }

    /** Sends the chosen element to the server and closes the screen. */
    private void choose(Element e) {
        NetworkManager.sendToServer(new ChooseElementPayload(e.id()));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.gatebound.element.subtitle").getString(),
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
