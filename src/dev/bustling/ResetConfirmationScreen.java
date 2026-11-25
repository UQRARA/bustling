package dev.bustling;

import static dev.bustling.Texts.tr;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ResetConfirmationScreen extends Screen {
    private static final int PANEL_WIDTH = 212;
    private static final int PANEL_HEIGHT = 56;
    private final UiScreen owner;
    private final Runnable confirmed;
    private int left;
    private int top;
    private int panelWidth;
    private int accent;

    ResetConfirmationScreen(UiScreen owner, String action, Runnable confirmed) {
        super(Component.literal(tr("common.ask", action)));
        this.owner = owner;
        this.confirmed = confirmed;
    }

    boolean isForWorldSettings() {
        return owner instanceof WorldScreen;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, width - 12);
        left = (width - panelWidth) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        accent = owner.accentColor();

        UiButton close = new UiButton(left + panelWidth - 20, top + 6, 14, 12, "", UiIcons.ACT_CLOSE,
                UiButton.Style.BUTTON, true, false, () -> minecraft.setScreen(null));
        addRenderableWidget(close.accent(accent).tip(tr("common.close_panels")));

        int gap = 8;
        int buttonWidth = Math.min(78, (panelWidth - 2 * gap - gap) / 2);
        int center = left + panelWidth / 2;
        addRenderableWidget(new UiButton(center - gap / 2 - buttonWidth, top + 30, buttonWidth, 18,
                tr("common.cancel"), UiIcons.UI_CROSS, UiButton.Style.BUTTON, this::onClose).accent(accent));
        addRenderableWidget(new UiButton(center + gap / 2, top + 30, buttonWidth, 18,
                tr("common.confirm"), UiIcons.UI_CHECK, UiButton.Style.BUTTON, this::confirm).accent(accent));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(owner);
    }

    private void confirm() {
        minecraft.setScreen(owner);
        confirmed.run();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        UiKit.panel(graphics, left, top, panelWidth, PANEL_HEIGHT);
        UiKit.banner(graphics, left + 3, top + 3, panelWidth - 6, accent);
        UiKit.icon(graphics, UiIcons.ACT_RESET, left + 8, top + 6, accent);
        String prompt = UiKit.ellipsis(font, getTitle().getString(), panelWidth - 50);
        UiKit.textCenter(graphics, prompt, left + panelWidth / 2, top + 8, UiTheme.text);
        for (Renderable widget : renderables) widget.render(graphics, mouseX, mouseY, partialTick);
    }
}
