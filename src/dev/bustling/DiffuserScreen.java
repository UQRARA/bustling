package dev.bustling;

import static dev.bustling.Texts.tr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

public final class DiffuserScreen extends UiScreen {
    private final String emitterId;
    private final List<UiValue> fields = new ArrayList<>();
    private UiButton walk, redstone;
    private int col, right, topRow, pitch, valueWidth;
    private CrowdData.Emitter displayed;

    private DiffuserScreen(String emitterId) {
        super(tr("common.device"));
        this.emitterId = emitterId;
    }

    public static void open(String emitterId) {
        Minecraft.getInstance().setScreen(new DiffuserScreen(emitterId));
    }

    CrowdData.Emitter emitter() {
        for (CrowdData.Emitter e : CrowdData.EMITTERS) {
            if (DiffuserData.isDiffuser(e) && emitterId.equals(e.id)) return e;
        }
        return null;
    }

    @Override protected int accentColor() { return 0xC193F3; }
    @Override protected String backgroundMode() { return "diffuser"; }
    @Override protected int titleIcon() { return UiIcons.DIFFUSER; }
    @Override protected boolean canReset() { return emitter() != null; }
    @Override protected String resetLockReason() { return tr("device.sync_wait"); }
    @Override protected void resetDefaults() {
        CrowdData.Emitter e = emitter();
        if (e != null) {
            DiffuserData.reset(e);
            if (ClientCrowdStore.supportsDevicePower() && Boolean.TRUE.equals(e.redstoneControlled)) {
                e.redstoneControlled = false;
            }
            CrowdData.save();
        }
    }

    @Override protected void buildWidgets() {
        fields.clear();
        col = (panelW - 26) / 2;
        right = 18 + col;
        valueWidth = Math.min(70, Math.max(55, col - 40));
        topRow = panelH >= 175 ? 58 : 26;
        pitch = Math.clamp((panelH - topRow - 40) / 3, 18, 24);
        addField(8, topRow, 2, 64, 1, 1, e -> e.diffuserRadius, (e,v) -> e.diffuserRadius = v, tr("device.radius_tip"));
        addField(right, topRow, 0, 200, 1, 0, e -> e.density, (e,v) -> e.density = (int)v, tr("device.density_tip"));
        addField(8, topRow + pitch, 0, DiffuserData.MAX_TOTAL, 1, 0, e -> e.diffuserLimit, (e,v) -> e.diffuserLimit = (int)v, tr("common.population_limit"));
        addField(right, topRow + pitch, 0, 2, .1, 1, e -> e.stepHeight, (e,v) -> e.stepHeight = v, tr("common.step_tip"));
        addField(8, topRow + pitch * 2, 0, 4, .1, 1, e -> e.diffuserDecay, (e,v) -> e.diffuserDecay = v, tr("device.falloff_tip"));
        walk = new UiButton(px(right), py(topRow + pitch * 2), col, 18,
                tr("common.editor"), UiIcons.MODE_WALK, UiButton.Style.BUTTON, this::openWalk).tip(tr("device.edit_tip"));
        addRenderableWidget(walk);
        redstone = new UiButton(px(8), py(topRow + pitch * 3), col, 18,
                tr("device.redstone"), UiIcons.WORLD_POWER, UiButton.Style.ROW, false, true, this::toggleRedstone)
                .tip(tr("device.redstone_tip"));
        addRenderableWidget(redstone);
        syncControls();
    }

    private void addField(int x, int y, double min, double max, double step, int decimals,
                         ToDoubleFunction<CrowdData.Emitter> get, ObjDoubleConsumer<CrowdData.Emitter> set, String tip) {
        UiValue field = new UiValue(px(x + col - valueWidth), py(y), valueWidth, 18, min, max, step,
                () -> {
                    CrowdData.Emitter e = emitter();
                    return e == null ? 0 : get.applyAsDouble(e);
                },
                v -> {
                    CrowdData.Emitter e = emitter();
                    if (e == null || displayed != e) return;
                    set.accept(e, v);
                    DiffuserData.refresh(e);
                    CrowdData.save();
                }, decimals).tip(tip);
        fields.add(field);
        addRenderableWidget(field);
    }

    private void openWalk() {
        CrowdData.Emitter e = emitter();
        if (e == null) return;
        setFocused(null);
        EditTool.selectDiffuser(e);
        EditTool.selectMode = false;
        EditTool.groundMode = false;
        Minecraft.getInstance().setScreen(new EditScreen(this));
    }

    private void toggleRedstone() {
        CrowdData.Emitter e = emitter();
        if (e == null || !ClientCrowdStore.supportsDevicePower()) return;
        e.redstoneControlled = !Boolean.TRUE.equals(e.redstoneControlled);
        CrowdData.save();
    }

    private void syncControls() {
        CrowdData.Emitter e = emitter();
        if (displayed != e) cancelFocusedValue();
        displayed = e;
        for (UiValue field : fields) field.lock(e == null, tr("device.sync_wait"));
        walk.lock(e == null, tr("device.sync_wait"));
        redstone.setSelected(e != null && Boolean.TRUE.equals(e.redstoneControlled));
        redstone.lock(e == null || !ClientCrowdStore.supportsDevicePower(),
                e == null ? tr("device.sync_wait") : tr("device.redstone_unavailable"));
    }

    @Override protected void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        syncControls();
        UiKit.rule(gg, px(8), py(topRow - 6), panelW - 16);
        label(gg, 8, topRow, tr("common.radius"), UiIcons.STAT_AREA);
        label(gg, right, topRow, tr("common.density"), UiIcons.PARAM_DENSITY);
        label(gg, 8, topRow + pitch, tr("common.total"), UiIcons.STAT_PEOPLE);
        label(gg, right, topRow + pitch, tr("common.step"), UiIcons.STEP_HEIGHT);
        label(gg, 8, topRow + pitch * 2, tr("device.falloff"), UiIcons.FALLOFF);
        String state = displayed == null ? tr("device.sync_wait") : tr("common.planned", displayed.enabled ? displayed.count : 0);
        UiKit.text(gg, state, px(8), py(panelH - 19), UiTheme.muted);
        if (displayed != null && Boolean.TRUE.equals(displayed.redstoneControlled)) {
            UiKit.textRight(gg, displayed.enabled ? tr("device.powered") : tr("device.await_power"), px(panelW - 8), py(panelH - 19), UiTheme.muted);
        }
    }

    private void label(GuiGraphics gg, int x, int y, String name, int icon) {
        UiKit.icon(gg, icon, px(x), py(y + 3), accent);
        UiKit.text(gg, name, px(x + 16), py(y + 5), UiTheme.muted);
    }
}
