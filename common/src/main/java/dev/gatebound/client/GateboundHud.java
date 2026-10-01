package dev.gatebound.client;

import dev.gatebound.data.ClientCombatData;
import dev.gatebound.data.ClientPlayerData;
import dev.gatebound.data.ClientSkillData;
import dev.gatebound.skill.Skill;
import dev.gatebound.skill.Skills;
import java.util.List;
import dev.gatebound.data.GateboundClass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** On-screen Gatebound HUD: level and XP, target posture bar, parry timing indicator. */
public final class GateboundHud {
    private static final long TARGET_SHOW_MS = 4000;

    private GateboundHud() {
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        drawStatus(g, mc);
        drawTargetPosture(g, mc);
        drawParry(g, mc);
    }

    private static void drawStatus(GuiGraphicsExtractor g, Minecraft mc) {
        int x = 6;
        int y = 6;
        int w = 112;
        g.fill(x - 3, y - 3, x + w + 3, y + 42, 0x90000000);
        g.outline(x - 3, y - 3, w + 6, 45, 0x885A6BFF);

        GateboundClass c = GateboundClass.byId(ClientPlayerData.classId);
        String cls = c == null ? "" : "  " + Component.translatable(c.nameKey()).getString();
        g.text(mc.font, "Lv " + ClientPlayerData.level + cls, x, y, 0xFFFFD866, true);

        int barY = y + 12;
        g.fill(x, barY, x + w, barY + 6, 0xFF1C1C26);
        int filled = ClientPlayerData.xpToNext <= 0 ? 0 : w * ClientPlayerData.xp / ClientPlayerData.xpToNext;
        g.fill(x, barY, x + Math.min(w, filled), barY + 6, 0xFF3DDC84);
        g.outline(x, barY, w, 6, 0xFF0B0B10);

        // Mana bar
        int manaY = barY + 9;
        g.fill(x, manaY, x + w, manaY + 6, 0xFF1C1C26);
        int manaFilled = w * ClientSkillData.mana / Math.max(1, ClientSkillData.maxMana);
        g.fill(x, manaY, x + Math.min(w, manaFilled), manaY + 6, 0xFF3D8BFF);
        g.outline(x, manaY, w, 6, 0xFF0B0B10);
        g.text(mc.font, ClientSkillData.mana + " / " + ClientSkillData.maxMana + " MP", x + w + 6, manaY - 1, 0xFF8FB8FF, true);

        if (ClientPlayerData.statPoints > 0) {
            g.text(mc.font, ClientPlayerData.statPoints + " points to spend", x, manaY + 9, 0xFF7CFF9A, true);
        }

        drawSkills(g, mc, x, y + 50, w);
    }

    /** One row per usable skill: key, name, mana cost and a cooldown fill. Greyed when mana is short. */
    private static void drawSkills(GuiGraphicsExtractor g, Minecraft mc, int x, int y, int w) {
        List<Skill> skills = Skills.available(ClientPlayerData.classId, ClientPlayerData.level);
        for (int i = 0; i < skills.size() && i < ClientHooks.SKILL_KEYS.length; i++) {
            Skill s = skills.get(i);
            int rowY = y + i * 15;
            float cd = ClientSkillData.cooldownRemaining(s.id());
            boolean affordable = ClientSkillData.mana >= s.manaCost();
            g.fill(x - 3, rowY - 2, x + w + 3, rowY + 11, 0x90000000);
            if (cd > 0) {
                g.fill(x - 3, rowY - 2, x - 3 + (int) ((w + 6) * (1f - cd)), rowY + 11, 0x55808090);
            } else if (affordable) {
                g.fill(x - 3, rowY - 2, x - 1, rowY + 11, 0xFF3DDC84);
            }
            String key = ClientHooks.SKILL_KEYS[i].getTranslatedKeyMessage().getString();
            int color = cd > 0 ? 0xFF888894 : (affordable ? 0xFFFFFFFF : 0xFF7A7A99);
            g.text(mc.font, "[" + key + "] " + Component.translatable(s.nameKey()).getString(), x + 3, rowY, color, true);
            g.text(mc.font, s.manaCost() + "mp", x + w - 22, rowY, affordable ? 0xFF8FB8FF : 0xFF666680, true);
        }
    }

    private static void drawTargetPosture(GuiGraphicsExtractor g, Minecraft mc) {
        long age = System.currentTimeMillis() - ClientCombatData.targetUpdatedMs;
        if (ClientCombatData.targetId < 0 || age > TARGET_SHOW_MS || mc.level == null) {
            return;
        }
        Entity target = mc.level.getEntity(ClientCombatData.targetId);
        if (target == null || !target.isAlive()) {
            return;
        }
        int cx = g.guiWidth() / 2;
        int w = 120;
        int x = cx - w / 2;
        int y = 22;

        g.centeredText(mc.font, target.getDisplayName().getString(), cx, y - 11, 0xFFFFFFFF);
        g.fill(x - 1, y - 1, x + w + 1, y + 7, 0xCC000000);
        float ratio = Math.min(1f, (float) ClientCombatData.posture / ClientCombatData.postureMax);
        int color;
        if (ClientCombatData.staggered) {
            // Flash between red and white while the mob is open to a finisher.
            color = (System.currentTimeMillis() / 120) % 2 == 0 ? 0xFFFF3B3B : 0xFFFFFFFF;
            ratio = 1f;
        } else {
            color = ratio < 0.5f ? 0xFFE8C547 : (ratio < 0.8f ? 0xFFFF9A2E : 0xFFFF4A3A);
        }
        g.fill(x, y, x + w, y + 6, 0xFF2A2A33);
        g.fill(x, y, x + (int) (w * ratio), y + 6, color);
        if (ClientCombatData.staggered) {
            g.centeredText(mc.font, "STAGGERED", cx, y + 9, 0xFFFF6B6B);
        }
    }

    /** Small bar under the crosshair: gold during the parry window, filling grey during recovery, hidden when ready. */
    private static void drawParry(GuiGraphicsExtractor g, Minecraft mc) {
        long since = System.currentTimeMillis() - ClientCombatData.parryStartMs;
        if (ClientCombatData.parryStartMs == 0 || since >= ClientCombatData.parryCooldownMs) {
            return;
        }
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int w = 30;
        int x = cx - w / 2;
        int y = cy + 14;
        boolean inWindow = since <= ClientCombatData.parryWindowMs;
        g.fill(x - 1, y - 1, x + w + 1, y + 4, 0xAA000000);
        if (inWindow) {
            g.fill(x, y, x + w, y + 3, 0xFFFFC53D);
        } else {
            float p = (float) since / ClientCombatData.parryCooldownMs;
            g.fill(x, y, x + (int) (w * p), y + 3, 0xFF8A8A96);
        }
    }
}
