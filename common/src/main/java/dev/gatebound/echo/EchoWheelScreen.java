package dev.gatebound.echo;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The C wheel. Hold C on a soul body (pick the group to bind it into) or on one of your echoes (pick its order), point
 * the mouse at an entry and let go. A click also picks; Esc, or letting go with nothing pointed at, cancels. If the key
 * was already released when the wheel opened, it stays up until you click.
 */
final class EchoWheelScreen extends Screen {
    private static final int BOX_W = 118;
    private static final int BOX_H = 20;
    private static final int DEAD_ZONE = 14;

    private final EchoWheelPayload data;
    private final boolean heldAtOpen;
    private int hovered = -1;

    EchoWheelScreen(EchoWheelPayload data) {
        super(Component.literal(data.title()));
        this.data = data;
        this.heldAtOpen = EchoClient.selectHeld();
    }

    /** Option labels shown on the wheel. */
    private List<String> labels() {
        return data.labels();
    }

    /** Radius of the wheel. */
    private double radius() {
        return Math.min(150, 58 + labels().size() * 8);
    }

    /** Angle of option i around the wheel. */
    private double angleOf(int i) {
        return -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, labels().size());
    }

    /** Screen x of option i's box. */
    private int boxX(int i) {
        return (int) (this.width / 2 + Math.cos(angleOf(i)) * radius() * 1.35) - BOX_W / 2;
    }

    /** Screen y of option i's box. */
    private int boxY(int i) {
        return (int) (this.height / 2 + Math.sin(angleOf(i)) * radius()) - BOX_H / 2;
    }

    /** Updates which option the mouse is over. */
    private void updateHover(int mouseX, int mouseY) {
        double dx = mouseX - this.width / 2.0;
        double dy = mouseY - this.height / 2.0;
        if (labels().isEmpty() || dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
            return;
        }
        double mouseAngle = Math.atan2(dy, dx / 1.35);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < labels().size(); i++) {
            double diff = Math.abs(Math.atan2(Math.sin(mouseAngle - angleOf(i)), Math.cos(mouseAngle - angleOf(i))));
            if (diff < best) {
                best = diff;
                hovered = i;
            }
        }
    }

    /** Sends the hovered choice and closes the wheel. */
    private void choose() {
        if (hovered >= 0 && hovered < labels().size()) {
            NetworkManager.sendToServer(new EchoWheelPickPayload(data.entityId(), hovered));
        }
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        for (int i = 0; i < labels().size(); i++) {
            int x = boxX(i);
            int y = boxY(i);
            boolean hover = i == hovered;
            g.fill(x, y, x + BOX_W, y + BOX_H, hover ? 0xE03A4A9A : 0xC0101018);
            g.outline(x, y, BOX_W, BOX_H, hover ? 0xFFFFD866 : (i == data.current() ? 0xFF3DDC84 : 0xFF404058));
            g.text(this.font, this.font.plainSubstrByWidth(labels().get(i), BOX_W - 8), x + 4, y + 6, 0xFFFFFFFF, true);
        }
        g.centeredText(this.font, data.title(), cx, cy - 8, 0xFFFFD866);
        g.centeredText(this.font, data.body() ? "Bind into which group?" : "Order for this echo", cx, cy + 4, 0xFFCCCCCC);
        g.centeredText(this.font, "Release to pick. Esc cancels.", cx, cy + 16, 0xFF888894);
    }

    /** Picks when the key is physically let go (polled, so key repeats cannot make the wheel flicker). */
    @Override
    public void tick() {
        super.tick();
        if (heldAtOpen && !EchoClient.selectHeld()) {
            choose();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        updateHover((int) event.x(), (int) event.y());
        choose();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
