package dev.bustling;

import static dev.bustling.Texts.tr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 所有柔和像素面板的基类：背景、边框、标题 banner、关闭按钮、悬浮提示。
 *
 * <p>子类只关心内容布局（{@link #buildWidgets()} + {@link #drawContent}）。
 * 版面统一单位 6px：面板内边距 6、分组间距 6、标题 banner 内缩 3。</p>
 */
public abstract class UiScreen extends Screen {
    protected int left;
    protected int top;
    protected int panelW;
    protected int panelH;
    protected int accent;
    public static final int FRAME_W=336, FRAME_H=216;
    protected UiButton headerReset;
    protected static final int HEADER_GAP=4, HEADER_BUTTON_W=14, HEADER_BUTTON_H=12;

    protected UiScreen(String title) {
        super(Component.literal(title));
    }

    // —— 子类定义 ——

    protected int nominalWidth() { return FRAME_W; }

    protected int nominalHeight() { return FRAME_H; }

    protected abstract int accentColor();

    /** 建立控件（已确定 left/top/panelW/panelH）。 */
    protected abstract void buildWidgets();

    /** 绘制面板内容（在控件之前）。 */
    protected abstract void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick);

    /** 标题栏图标，返回 -1 表示不画。 */
    protected int titleIcon() {
        return -1;
    }

    /** 标题文字（默认取屏幕标题）。 */
    protected String titleText() {
        return this.getTitle().getString();
    }

    /** 标题 banner 相对面板边缘的内缩。 */
    protected int bannerInset() {
        return 3;
    }

    /** 画在控件之上的一层（悬浮预览等）。 */
    protected void drawOverlay(GuiGraphics gg, int mouseX, int mouseY) {
    }

    protected void resetDefaults() {}
    protected String resetTip() { return tr("common.reset"); }
    protected boolean canReset() { return true; }
    protected String resetLockReason() { return tr("common.mode_locked"); }
    protected Screen parentScreen() { return null; }
    protected int backgroundIcon() { return titleIcon(); }
    protected String backgroundMode() { return "world"; }
    private static final java.util.Map<String,ResourceLocation> BACKDROPS=new java.util.HashMap<>();
    private static final java.util.Map<String,ResourceLocation> WATERMARKS=new java.util.HashMap<>();

    /** Original native-resolution artwork stays anchored without enlarging toolbar glyphs. */
    protected void drawModeBackground(GuiGraphics gg) {
        ResourceLocation backdrop=BACKDROPS.computeIfAbsent(backgroundMode(),key->ResourceLocation.fromNamespaceAndPath("bustling","textures/gui/background_"+key+".png"));
        ResourceLocation watermark=WATERMARKS.computeIfAbsent(backgroundMode(),key->ResourceLocation.fromNamespaceAndPath("bustling","textures/gui/watermark_"+key+".png"));
        gg.setColor(1,1,1,1);
        gg.blit(backdrop,left+4,top+23,4f,23f,panelW-8,panelH-27,FRAME_W,FRAME_H);
        gg.enableScissor(left+4,top+23,left+panelW-4,top+panelH-4);
        try {
            gg.blit(watermark,left+panelW-114,top+panelH-116,0f,0f,104,104,104,104);
        } finally {gg.disableScissor();}
    }

    // —— 生命周期 ——

    @Override
    protected void init() {
        UiTheme.reloadIfChanged();
        this.accent = accentColor();
        this.panelW = Math.min(nominalWidth(), this.width - 12);
        this.panelH = Math.min(nominalHeight(), this.height - 12);
        this.left = (this.width - panelW) / 2;
        this.top = (this.height - panelH) / 2;
        this.buildWidgets();
        int closeX=left+panelW-6-HEADER_BUTTON_W;
        int headerStep = HEADER_BUTTON_W + HEADER_GAP;
        addHeaderButton(closeX, UiIcons.ACT_CLOSE, tr("common.close_panels"), this::closePanels);
        int nextX = closeX - headerStep;
        headerReset = addHeaderButton(nextX, UiIcons.ACT_RESET, resetTip(), this::requestReset);
        headerReset.lock(!canReset(), resetLockReason());
        nextX -= headerStep;
        if (hasSettingsShortcut()) {
            addHeaderButton(nextX, UiIcons.WORLD_PANEL, tr("common.settings"), () -> {
                cancelFocusedValue();
                WorldScreen.open();
            });
            nextX -= headerStep;
        }
        if (parentScreen() != null) {
            addHeaderButton(nextX, UiIcons.NAV_LEFT, tr("common.back"), this::returnToParent);
        }
    }

    private boolean hasSettingsShortcut() {
        return !(this instanceof WorldScreen);
    }

    private UiButton addHeaderButton(int x, int icon, String tip, UiButton.Press action) {
        return addRenderableWidget(new UiButton(x, top + 6, HEADER_BUTTON_W, HEADER_BUTTON_H, "", icon,
                UiButton.Style.BUTTON, true, false, action).accent(accent).tip(tip));
    }

    private void requestReset() {
        if (!canReset()) return;
        confirmAction(resetTip(), () -> {
            if (canReset()) resetDefaults();
        });
    }

    protected final void confirmAction(String action, Runnable confirmed) {
        cancelFocusedValue();
        Minecraft.getInstance().setScreen(new ResetConfirmationScreen(this, action, confirmed));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        UiTheme.reloadIfChanged();
        this.accent = accentColor();
        for (Renderable renderable : this.renderables) {
            if (renderable instanceof UiButton button) {
                button.accent(this.accent);
            }
        }
        headerReset.lock(!canReset(),resetLockReason());
        // 背景（模糊）只做一次：不能调用 super.render()，否则它会再次执行
        // renderBackground 的模糊 pass，把我们刚画好的面板糊掉。
        this.renderBackground(gg, mouseX, mouseY, partialTick);
        UiKit.panel(gg, left, top, panelW, panelH);
        drawModeBackground(gg);
        int inset = bannerInset();
        UiKit.banner(gg, left + inset, top + inset, panelW - 2 * inset, accent);
        drawTitleBar(gg);
        drawContent(gg, mouseX, mouseY, partialTick);
        for (Renderable renderable : this.renderables) {
            renderable.render(gg, mouseX, mouseY, partialTick);
        }
        drawOverlay(gg, mouseX, mouseY);
        drawTooltips(gg, mouseX, mouseY);
    }

    protected void drawTitleBar(GuiGraphics gg) {
        int inset = bannerInset();
        int icon = titleIcon();
        if (icon >= 0) {
            UiKit.icon(gg, icon, left + inset + 5, top + inset + 3, accent);
        }
        int titleLeft=left+inset+24;
        int titleRight=left+panelW-6-3*HEADER_BUTTON_W-2*HEADER_GAP-6;
        if (hasSettingsShortcut() && parentScreen() != null) titleRight -= HEADER_BUTTON_W + HEADER_GAP;
        String title=UiKit.ellipsis(Minecraft.getInstance().font,titleText(),Math.max(0,titleRight-titleLeft));
        UiKit.textCenter(gg,title,(titleLeft+titleRight)/2,top+inset+(18-8)/2,UiTheme.text);
    }

    protected void drawTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        for (Renderable renderable : this.renderables) {
            if (!(renderable instanceof AbstractWidget widget)) {
                continue;
            }
            if (!widget.visible || mouseX < widget.getX() || mouseX >= widget.getX()+widget.getWidth()
                    || mouseY < widget.getY() || mouseY >= widget.getY()+widget.getHeight()) {
                continue;
            }
            String tip = null;
            if (widget instanceof UiButton button) {
                tip = button.tip();
            } else if (widget instanceof UiValue value) {
                tip = value.tip();
            }
            if (tip != null && !tip.isEmpty()) {
                // GUI 顶点缓冲按 RenderType 排序 flush：不先 flush 的话，
                // 提示框背景会排在此前文字之后被覆盖（被下层文字"穿透"）。
                gg.flush();
                UiKit.tooltip(gg, tip, mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 点击空白处取消数值输入焦点
        if (button == 0) setFocused(null);
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Vanilla Screen handles Escape before dispatching to its focused child.
        if (keyCode == 256 && getFocused() instanceof UiValue value
                && value.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected final void cancelFocusedValue() {
        if (getFocused() instanceof UiValue value) {
            value.cancelEdit();
            setFocused(null);
        }
    }

    @Override
    protected void removeWidget(GuiEventListener widget) {
        if (widget == getFocused()) cancelFocusedValue();
        super.removeWidget(widget);
    }

    @Override
    public void removed() {
        cancelFocusedValue();
        super.removed();
    }

    @Override
    public void onClose() {
        if (parentScreen() == null) closePanels();
        else returnToParent();
    }

    private void returnToParent() {
        cancelFocusedValue();
        Minecraft.getInstance().setScreen(parentScreen());
    }

    protected final void closePanels() {
        cancelFocusedValue();
        setFocused(null);
        super.onClose();
    }

    /** 面板内的相对坐标。 */
    protected int px(int x) {
        return left + x;
    }

    protected int py(int y) {
        return top + y;
    }
}
