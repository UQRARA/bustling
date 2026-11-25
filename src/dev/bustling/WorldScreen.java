package dev.bustling;

import static dev.bustling.Texts.tr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;

/** Client-only crowd controls, using the handoff's native 12px pixel UI. */
public final class WorldScreen extends UiScreen {
    private final Screen parent;
    private UiButton systemButton, shadowButton, activityButton, originalButton, avoidButton, pushButton, environmentButton;
    private UiValue radiusValue, totalValue, standingValue, pushRadiusValue;
    private static final int W = FRAME_W, H = FRAME_H;
    private int columnWidth, rightColumn, fieldWidth;
    private int firstY, secondY, thirdY, fourthY, firstRuleY, secondRuleY, footerY, behaviorY, rowHeight;
    private int radiusWidth, standingWidth;
    private int displayedEnvironmentPercent;
    private boolean showFooter;

    public WorldScreen() { this(null); }
    public WorldScreen(Screen parent) { super(tr("common.settings")); this.parent = parent; }
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        WorldSystem.load();
        mc.setScreen(new WorldScreen(mc.screen));
    }
    @Override protected int nominalWidth() { return W; }
    @Override protected int nominalHeight() { return H; }
    @Override protected int accentColor() { return 0x86B6D6; }
    @Override protected int titleIcon() { return UiIcons.WORLD_PANEL; }
    @Override protected String titleText() { return tr("common.settings"); }
    @Override protected Screen parentScreen() { return parent; }

    @Override protected void resetDefaults(){WorldSystem.resetDefaults();syncLocks();}
    @Override protected String resetTip(){return tr("world.reset");}
    @Override protected void buildWidgets() {
        // Screen GUI width may be only 320; UiScreen reserves a 6px outer margin.
        // Work from its actual rectangle so the right-hand controls remain inside it.
        columnWidth = Math.max(28, (panelW - 26) / 2);
        rightColumn = 8 + columnWidth + 10;
        // Seven digits must fit between both step arrows at compact GUI scales.
        fieldWidth = Math.min(74, Math.min(columnWidth - 18, Math.max(68, columnWidth - 44)));
        radiusWidth = Math.min(56, fieldWidth);
        standingWidth = Math.min(50, fieldWidth);
        showFooter = panelH >= 182;
        secondRuleY = panelH - 31;
        footerY = panelH - 19;
        int bodyBottom = showFooter ? secondRuleY - 4 : panelH - 4;
        int rowPitch = Math.clamp((bodyBottom - 26) / 6, 12, 22);
        rowHeight = Math.min(18, rowPitch);
        firstY = Math.min(58, bodyBottom - 5 * rowPitch - rowHeight);
        secondY = firstY + rowPitch;
        thirdY = secondY + rowPitch;
        fourthY = thirdY + rowPitch;
        behaviorY = fourthY + rowPitch;
        firstRuleY = firstY - 5;
        boolean narrow = panelW < 280;
        systemButton = row(8, firstY, columnWidth, narrow ? tr("world.system_short") : tr("world.system"), UiIcons.WORLD_POWER,
                () -> WorldSystem.setEnabled(!WorldSystem.enabled),
                tr("world.system_tip"));
        shadowButton = row(rightColumn, firstY, columnWidth, narrow ? tr("world.shadows_short") : tr("world.shadows"), UiIcons.WORLD_SHADOW,
                () -> { WorldSystem.shadows = !WorldSystem.shadows; WorldSystem.save(); },
                tr("world.shadows_tip"));
        activityButton = row(8, secondY, columnWidth, narrow ? tr("world.active_short") : tr("world.active"), UiIcons.WORLD_ACTIVE,
                () -> { WorldSystem.activeEnabled = !WorldSystem.activeEnabled; WorldSystem.save(); syncLocks(); },
                tr("world.active_tip"));
        radiusValue = value(rightColumn + columnWidth - radiusWidth, secondY, radiusWidth, 1, 64, 1,
                () -> WorldSystem.activeRadius,
                v -> { WorldSystem.activeRadius = (int)Math.round(v); WorldSystem.save(); },
                tr("world.radius_tip"));
        originalButton = row(8, thirdY, columnWidth, tr("world.original"), UiIcons.PARAM_DENSITY,
                () -> { WorldSystem.useEmitterCounts = !WorldSystem.useEmitterCounts; WorldSystem.save(); syncLocks(); },
                tr("world.original_tip"));
        totalValue = value(rightColumn + columnWidth - fieldWidth, thirdY, fieldWidth, 0, 1000000, 16,
                () -> WorldSystem.maxTotal,
                v -> WorldSystem.setMaxTotal((int)Math.round(v)),
                tr("world.cap_tip"));
        standingValue = value(8 + columnWidth - standingWidth, fourthY, standingWidth, 0, 100, 5,
                () -> WorldSystem.effectiveStandingFraction() * 100,
                v -> WorldSystem.setStandingFraction(v / 100.0),
                tr("world.idle_tip"));
        pushRadiusValue = value(rightColumn + columnWidth - radiusWidth, behaviorY, radiusWidth, 1, 30, 1,
                () -> WorldSystem.playerRadius,
                v -> { WorldSystem.playerRadius = (int)v; WorldSystem.save(); },
                tr("world.push_radius_tip"));
        avoidButton = row(rightColumn, fourthY, columnWidth, tr("world.avoid_blocks"), UiIcons.BEHAVIOR_AVOID,
                () -> { WorldSystem.avoidBlocks = !WorldSystem.avoidBlocks; WorldSystem.save(); },
                tr("world.avoid_blocks_tip"));
        pushButton = row(8, behaviorY, columnWidth, tr("world.avoid_players"), UiIcons.BEHAVIOR_PUSH,
                () -> { WorldSystem.avoidPlayers = !WorldSystem.avoidPlayers; WorldSystem.save(); syncLocks(); },
                tr("world.avoid_players_tip"));
        environmentButton = row(8, behaviorY + rowPitch, columnWidth, tr("world.environment"), UiIcons.WORLD_ENVIRONMENT,
                () -> { WorldSystem.environmentEnabled = !WorldSystem.environmentEnabled; WorldSystem.save(); },
                tr("world.environment_tip"));
        displayedEnvironmentPercent = 0;
        syncLocks();
    }
    private void syncLocks() {
        if (radiusValue == null) return;
        pushRadiusValue.lock(!WorldSystem.avoidPlayers, tr("world.push_locked"));
        radiusValue.lock(!WorldSystem.activeEnabled, tr("world.active_locked"));
        String reason = tr("world.original_locked");
        totalValue.lock(WorldSystem.useEmitterCounts, reason);
        standingValue.lock(WorldSystem.useEmitterCounts, reason);
    }
    private UiButton row(int x, int y, int w, String label, int icon, UiButton.Press press, String tip) {
        UiButton button = new UiButton(px(x), py(y), w, rowHeight, label, icon,
                UiButton.Style.ROW, false, true, press).accent(accent).tip(tip);
        addRenderableWidget(button); return button;
    }
    private UiValue value(int x, int y, int w, double min, double max, double step,
                          java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter, String tip) {
        UiValue value = new UiValue(px(x), py(y), w, rowHeight, min, max, step, getter, setter, 0).tip(tip);
        addRenderableWidget(value); return value;
    }
    @Override protected void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        systemButton.setSelected(WorldSystem.enabled);
        shadowButton.setSelected(WorldSystem.shadows);
        activityButton.setSelected(WorldSystem.activeEnabled);
        originalButton.setSelected(WorldSystem.useEmitterCounts);
        avoidButton.setSelected(WorldSystem.avoidBlocks);
        pushButton.setSelected(WorldSystem.avoidPlayers);
        environmentButton.setSelected(WorldSystem.environmentEnabled);
        int percent = WorldSystem.enabled && WorldSystem.environmentEnabled ? WorldSystem.environmentPercent() : 0;
        if (percent != displayedEnvironmentPercent) {
            displayedEnvironmentPercent = percent;
            environmentButton.setLabel(percent == 0 ? tr("world.environment") : tr("world.environment_percent", percent));
        }
        syncLocks();
        UiKit.rule(gg, px(8), py(firstRuleY), panelW - 16);
        int labelInset = (rowHeight - 8) / 2;
        label(gg, tr("common.radius"), UiIcons.WORLD_ACTIVE, rightColumn, secondY + labelInset, radiusWidth);
        label(gg, tr("common.people"), UiIcons.STAT_PEOPLE, rightColumn, thirdY + labelInset, fieldWidth);
        label(gg, panelW < 280 ? tr("common.stand_short") : tr("common.stand_ratio"), UiIcons.WORLD_STAND, 8, fourthY + labelInset, standingWidth);
        label(gg, panelW < 260 ? tr("common.radius") : tr("world.push_radius"), UiIcons.BEHAVIOR_PUSH, rightColumn, behaviorY + labelInset, radiusWidth);
        if (showFooter) {
            UiKit.rule(gg, px(8), py(secondRuleY), panelW - 16);
            String state = !WorldSystem.enabled ? tr("world.disabled") : WorldSystem.activeEnabled
                    ? tr("common.active_radius", WorldSystem.activeRadius) : tr("world.static");
            String budget = tr("common.planned", WorldSystem.totalPlanned());
            int budgetWidth = Minecraft.getInstance().font.width(budget);
            state = UiKit.ellipsis(Minecraft.getInstance().font, state, Math.max(0, panelW - 26 - budgetWidth));
            UiKit.text(gg, state, px(8), py(footerY), WorldSystem.enabled ? UiTheme.muted : UiTheme.dim);
            UiKit.textRight(gg, budget, px(panelW - 8), py(footerY), UiTheme.dim);
        }
    }

    private void label(GuiGraphics gg, String label, int icon, int x, int y, int valueWidth) {
        Font font = Minecraft.getInstance().font;
        int space = columnWidth - valueWidth - 3;
        int labelX = x + 1;
        if (font.width(label) + 16 <= space) {
            UiKit.icon(gg, icon, px(labelX), py(y - 2), UiTheme.muted);
            labelX += 16;
            space -= 16;
        }
        UiKit.text(gg, UiKit.ellipsis(font, label, space), px(labelX), py(y), UiTheme.muted);
    }
}
