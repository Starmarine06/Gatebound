package dev.gatebound.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Solid green "+N" button that is easy to spot; turns grey when it cannot be afforded. */
public final class StatButton extends AbstractButton {
    private static final int GREEN = 0xFF2FA84F;
    private static final int GREEN_HOVER = 0xFF4FD66F;
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
        int fill = !this.active ? GREY : (this.isHoveredOrFocused() ? GREEN_HOVER : GREEN);
        int x = getX();
        int y = getY();
        g.fill(x, y, x + getWidth(), y + getHeight(), fill);
        g.outline(x, y, getWidth(), getHeight(), this.active ? OUTLINE : 0xFF22222A);
        int color = this.active ? 0xFFFFFFFF : 0xFF777777;
        g.centeredText(this.font, getMessage().getString(), x + getWidth() / 2, y + (getHeight() - 8) / 2, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
