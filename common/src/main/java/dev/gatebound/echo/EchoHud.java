package dev.gatebound.echo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** Client only: a compact list of your own echoes with health bars, top right, so you do not need the H screen mid-fight. */
final class EchoHud {
    private static final int ROW_W = 96;
    private static final int ROW_H = 12;
    private static final int MAX_ROWS = 14;

    private EchoHud() {
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        String me = mc.player.getUUID().toString();
        List<EchoEntity> mine = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof EchoEntity echo && !echo.isCorpse() && me.equals(echo.ownerId()) && echo.isAlive()) {
                mine.add(echo);
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        int x = g.guiWidth() - ROW_W - 6;
        int y = 6;
        g.text(mc.font, "Echoes (" + mine.size() + ")", x, y, 0xFFD9B8FF, true);
        y += 11;
        for (int i = 0; i < mine.size() && i < MAX_ROWS; i++) {
            EchoEntity e = mine.get(i);
            float ratio = Math.max(0f, Math.min(1f, e.getHealth() / Math.max(1f, e.getMaxHealth())));
            int color = ratio > 0.5f ? 0xFF3DDC84 : (ratio > 0.25f ? 0xFFE8C547 : 0xFFFF4A3A);
            int rowY = y + i * ROW_H;
            g.fill(x, rowY, x + ROW_W, rowY + ROW_H - 1, 0x90000000);
            g.fill(x, rowY, x + (int) (ROW_W * ratio), rowY + ROW_H - 1, (color & 0x00FFFFFF) | 0x66000000);
            String name = e.mobName();
            if (name.length() > 14) {
                name = name.substring(0, 13) + ".";
            }
            if (e.isSelected()) {
                g.fill(x - 2, rowY, x, rowY + ROW_H - 1, 0xFFFFAA00);
                name = "> " + name;
            }
            g.text(mc.font, name, x + 3, rowY + 2, e.isSelected() ? 0xFFFFD27C : 0xFFFFFFFF, true);
        }
    }
}
