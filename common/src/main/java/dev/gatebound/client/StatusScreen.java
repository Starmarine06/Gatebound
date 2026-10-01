package dev.gatebound.client;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.data.ClientPlayerData;
import dev.gatebound.data.GateboundClass;
import dev.gatebound.data.Stat;
import dev.gatebound.network.SpendStatPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Status window: level, XP, class and stats, with buttons to spend unspent points. Reads ClientPlayerData live. */
public final class StatusScreen extends Screen {
    private static final int PANEL_W = 230;
    private static final int PANEL_H = 206;
    private static final int ROW_H = 24;

    private final StatButton[] plusOne = new StatButton[Stat.values().length];
    private final StatButton[] plusFive = new StatButton[Stat.values().length];
    private int left;
    private int top;

    public StatusScreen() {
        super(Component.translatable("screen.gatebound.status.title"));
    }

    @Override
    protected void init() {
        left = (this.width - PANEL_W) / 2;
        top = (this.height - PANEL_H) / 2;
        Stat[] stats = Stat.values();
        for (int i = 0; i < stats.length; i++) {
            Stat s = stats[i];
            int y = rowY(i);
            plusOne[i] = addRenderableWidget(new StatButton(left + PANEL_W - 84, y - 5, 36, 20,
                    Component.literal("+1"), this.font, () -> spend(s, 1)));
            plusOne[i].setTooltip(Tooltip.create(Component.translatable("stat.gatebound." + s.id() + ".desc")));
            plusFive[i] = addRenderableWidget(new StatButton(left + PANEL_W - 44, y - 5, 36, 20,
                    Component.literal("+5"), this.font, () -> spend(s, 5)));
        }
    }

    private int rowY(int index) {
        return top + 78 + index * ROW_H;
    }

    private void spend(Stat stat, int points) {
        if (ClientPlayerData.statPoints >= points) {
            NetworkManager.sendToServer(new SpendStatPayload(stat.id(), points));
        }
    }

    /** The panel is drawn with the background so it sits underneath the buttons, which render afterwards. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xF0101018);
        g.outline(left, top, PANEL_W, PANEL_H, 0xFF5A6BFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);

        // Class and level
        String className = ClientPlayerData.classId.isEmpty()
                ? Component.translatable("screen.gatebound.status.no_class").getString()
                : Component.translatable(classKey()).getString();
        g.text(this.font, Component.translatable("screen.gatebound.status.class", className).getString(), left + 10, top + 24, 0xFFDDDDDD);
        g.text(this.font, Component.translatable("screen.gatebound.status.level", ClientPlayerData.level).getString(), left + 10, top + 36, 0xFFFFD866);

        // XP bar
        int barX = left + 10;
        int barY = top + 50;
        int barW = PANEL_W - 20;
        g.fill(barX, barY, barX + barW, barY + 8, 0xFF303040);
        int filled = ClientPlayerData.xpToNext <= 0 ? 0 : barW * ClientPlayerData.xp / ClientPlayerData.xpToNext;
        g.fill(barX, barY, barX + Math.min(barW, filled), barY + 8, 0xFF58C878);
        g.centeredText(this.font, ClientPlayerData.xp + " / " + ClientPlayerData.xpToNext + " XP", this.width / 2, barY + 10, 0xFFFFFFFF);

        // Unspent points
        int points = ClientPlayerData.statPoints;
        g.text(this.font, Component.translatable("screen.gatebound.status.points", points).getString(), left + 10, top + 68,
                points > 0 ? 0xFFFFD866 : 0xFF888888);

        // Stat rows
        Stat[] stats = Stat.values();
        for (int i = 0; i < stats.length; i++) {
            Stat s = stats[i];
            int y = rowY(i);
            g.text(this.font, Component.translatable("stat.gatebound." + s.id()).getString(), left + 10, y, 0xFFFFFFFF);
            g.text(this.font, String.valueOf(ClientPlayerData.STATS[i]), left + 110, y, 0xFF8FD0FF);
            plusOne[i].active = points >= 1;
            plusFive[i].active = points >= 5;
        }

        g.centeredText(this.font, Component.translatable("screen.gatebound.status.hint").getString(), this.width / 2, top + PANEL_H - 12, 0xFF777777);
    }

    private static String classKey() {
        GateboundClass c = GateboundClass.byId(ClientPlayerData.classId);
        return c == null ? "screen.gatebound.status.no_class" : c.nameKey();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ClientHooks.OPEN_STATUS.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
