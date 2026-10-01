package dev.gatebound.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Solid green "+N" button that is easy to spot; turns grey when it cannot be afforded. */
public final class StatButton extends AbstractButton {
    private static final int GREEN = 0xFF2ED158;
    private static final int GREEN_HOVER = 0xFF5CFF86;
    private static final int GREY = 0xFF3A3A44;
    private static final int OUTLINE = 0xFF0B3A19;

    private final Font font;
    private final Runnable action;

    public StatButton(int x, int y, int width, int height, Component label, Font font, Runnable action) {
        super(x, y, width, height, label);
        this.font = font;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean hot = this.active && this.isHoveredOrFocused();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        if (!this.active) {
            g.fill(x, y, x + w, y + h, GREY);
            g.outline(x, y, w, h, 0xFF22222A);
            g.centeredText(this.font, getMessage().getString(), x + w / 2, y + (h - 8) / 2, 0xFF777777);
            return;
        }
        // Glow, drop shadow, body, bevel highlight and shade so the button reads as raised.
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, hot ? 0x6657FF7F : 0x3357FF7F);
        g.fill(x + 1, y + 2, x + w + 1, y + h + 2, 0xAA000000);
        g.fill(x, y, x + w, y + h, hot ? GREEN_HOVER : GREEN);
        g.fill(x, y, x + w, y + 2, hot ? 0xFFC8FFD6 : 0xFF8DFFA8);
        g.fill(x, y + h - 2, x + w, y + h, hot ? 0xFF1F9B3F : 0xFF14772E);
        g.outline(x, y, w, h, OUTLINE);
        g.centeredText(this.font, getMessage().getString(), x + w / 2 + 1, y + (h - 8) / 2 + 1, 0xFF0B3A19);
        g.centeredText(this.font, getMessage().getString(), x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
