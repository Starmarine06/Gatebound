package dev.gatebound.client;

import dev.gatebound.data.ClientPlayerData;
import dev.gatebound.data.ClientSkillData;
import dev.gatebound.skill.Skill;
import dev.gatebound.skill.Skills;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The skill wheel. Hold Z, point the mouse at a skill and let go: that skill becomes the selected one and is cast.
 * A quick tap of Z casts the selected skill without showing the wheel. A click also casts; Esc cancels.
 */
public final class SkillWheelScreen extends Screen {
    private static final int BOX_W = 92;
    private static final int BOX_H = 20;
    private static final int DEAD_ZONE = 14;

    /** The selected skill's index in the player's available skills. Client side only. */
    private static int selected;

    /** The wheel stays invisible for a moment so a quick tap (cast the selected skill) does not flash it. */
    private static final long SHOW_AFTER_MS = 150;

    private final List<Skill> skills;
    private final long openedMs = System.currentTimeMillis();
    private int hovered;

    public SkillWheelScreen() {
        super(Component.literal("Skills"));
        this.skills = available();
        this.hovered = clamp(selected, skills.size());
    }

    private static List<Skill> available() {
        return Skills.available(ClientPlayerData.classId, ClientPlayerData.elements, ClientPlayerData.picks, ClientPlayerData.level);
    }

    private static int clamp(int index, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(size - 1, index));
    }

    /** The selected skill's slot, safe even if the skill list changed since it was chosen. */
    public static int selectedSlot() {
        selected = clamp(selected, available().size());
        return selected;
    }

    /** The selected skill, or null if the player has none. */
    public static Skill selectedSkill() {
        List<Skill> skills = available();
        return skills.isEmpty() ? null : skills.get(clamp(selected, skills.size()));
    }

    private double radius() {
        return Math.min(150, 62 + skills.size() * 7);
    }

    private double angleOf(int i) {
        return -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, skills.size());
    }

    private int boxX(int i) {
        return (int) (this.width / 2 + Math.cos(angleOf(i)) * radius() * 1.35) - BOX_W / 2;
    }

    private int boxY(int i) {
        return (int) (this.height / 2 + Math.sin(angleOf(i)) * radius()) - BOX_H / 2;
    }

    /** Follows the mouse: the skill nearest in direction wins once the cursor leaves the centre. */
    private void updateHover(int mouseX, int mouseY) {
        double dx = mouseX - this.width / 2.0;
        double dy = mouseY - this.height / 2.0;
        if (skills.isEmpty() || dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
            return;
        }
        double mouseAngle = Math.atan2(dy / 1.0, dx / 1.35);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < skills.size(); i++) {
            double diff = Math.abs(Math.atan2(Math.sin(mouseAngle - angleOf(i)), Math.cos(mouseAngle - angleOf(i))));
            if (diff < best) {
                best = diff;
                hovered = i;
            }
        }
    }

    /** Selects the pointed-at skill. Casting is a separate key. */
    private void choose() {
        if (!skills.isEmpty()) {
            selected = hovered;
        }
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateHover(mouseX, mouseY);
        if (System.currentTimeMillis() - openedMs < SHOW_AFTER_MS) {
            return;
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        if (skills.isEmpty()) {
            g.centeredText(this.font, "You have no skills yet.", cx, cy, 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < skills.size(); i++) {
            Skill s = skills.get(i);
            int x = boxX(i);
            int y = boxY(i);
            boolean hover = i == hovered;
            boolean affordable = ClientSkillData.mana >= s.manaCost();
            g.fill(x, y, x + BOX_W, y + BOX_H, hover ? 0xE03A4A9A : 0xC0101018);
            g.outline(x, y, BOX_W, BOX_H, hover ? 0xFFFFD866 : (i == selected ? 0xFF3DDC84 : 0xFF404058));
            int color = affordable ? 0xFFFFFFFF : 0xFF7A7A99;
            g.text(this.font, Component.translatable(s.nameKey()).getString(), x + 4, y + 6, color, true);
            g.text(this.font, s.manaCost() + "", x + BOX_W - 4 - this.font.width(s.manaCost() + ""), y + 6,
                    affordable ? 0xFF8FB8FF : 0xFF666680, true);
        }
        Skill h = skills.get(hovered);
        g.centeredText(this.font, Component.translatable(h.nameKey()), cx, cy - 12, 0xFFFFD866);
        g.centeredText(this.font, Component.translatable(h.descriptionKey()), cx, cy, 0xFFCCCCCC);
        g.centeredText(this.font, "Release to select. Esc cancels.", cx, cy + 14, 0xFF888894);
    }

    /** Closes and casts when the key is physically let go. Polled, so repeated key events cannot make the wheel flicker. */
    @Override
    public void tick() {
        super.tick();
        if (!ClientHooks.wheelHeld()) {
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
