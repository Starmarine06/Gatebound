package dev.gatebound.client;

import dev.architectury.networking.NetworkManager;
import dev.gatebound.network.ChooseSpellPayload;
import dev.gatebound.skill.Skill;
import dev.gatebound.skill.Skills;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A Mage with several elements picks which element's spell to take at each unlock level. Esc closes it for now;
 * it reopens on the next level-up or join, or with /gatebound spells.
 */
public final class SpellPickScreen extends Screen {
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final List<Skill> options = new ArrayList<>();

    public SpellPickScreen(List<String> ids) {
        super(Component.translatable("screen.gatebound.spellpick.title"));
        for (String id : ids) {
            Skill s = Skills.byId(id);
            if (s != null) {
                options.add(s);
            }
        }
    }

    @Override
    protected void init() {
        int total = options.size() * BUTTON_HEIGHT + Math.max(0, options.size() - 1) * GAP;
        int top = (this.height - total) / 2 + 10;
        int left = (this.width - BUTTON_WIDTH) / 2;
        for (int i = 0; i < options.size(); i++) {
            Skill s = options.get(i);
            Component label = Component.translatable(s.element().nameKey()).append(": ").append(Component.translatable(s.nameKey()));
            addRenderableWidget(Button.builder(label, button -> choose(s))
                    .bounds(left, top + i * (BUTTON_HEIGHT + GAP), BUTTON_WIDTH, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable(s.descriptionKey())))
                    .build());
        }
    }

    private void choose(Skill s) {
        NetworkManager.sendToServer(new ChooseSpellPayload(s.id()));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        if (!options.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("screen.gatebound.spellpick.subtitle", options.get(0).minLevel()).getString(),
                    this.width / 2, 40, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
