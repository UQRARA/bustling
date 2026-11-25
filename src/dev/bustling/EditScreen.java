package dev.bustling;

import static dev.bustling.Texts.tr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 对象编辑器（实体列表 / 地表）。
 *
 * <p>版面单位：6px。外框内边距 6、分组间距 6、列表行距 15（14 内容 + 1 间隙）。
 * 面板 336×204（宽高比 1.647，与设置器一致）。</p>
 */
public final class EditScreen extends UiScreen {
    private static final int W = FRAME_W;
    private static final int H = FRAME_H;

    // —— 版面常量 ——
    private static final int BANNER_INSET = 3;
    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int TAB_Y = 27;
    private static final int TAB_H = 18;
    private static final int LIST_X = 6;
    private static final int LIST_ROW_H = 14;
    private static final int LIST_ROW_PITCH = 15;

    private static int page = 0;

    /** 仅调试：强制显示某行的悬浮预览（-Dbustling.uitest）。 */
    static boolean debugPreview;

    private final Set<String> selectedMobs = new LinkedHashSet<>();
    private final Set<String> selectedGround = new LinkedHashSet<>();

    private UiButton pageObjectTab;
    private UiButton pageGroundTab;
    private UiButton whiteBtn;
    private UiButton skinBtn;
    private UiButton includeBtn;
    private UiButton excludeBtn;
    private UiButton plusBtn, removeBtn, resetBtn, packBtn;
    private final List<UiValue> rowFields = new ArrayList<>();
    private int listWidth,listHeight,listTop,previewX,previewWidth,footerY,ruleY,buttonWidth,summaryY,optionY;
    private boolean previewVisible;
    private CrowdData.Emitter rowEmitter;
    private List<CrowdData.Emitter.MobWeight> boundRows = List.of();
    private CrowdData.Emitter.MobWeight failedPreview;
    private static final String WHITE_LOCK = "edit.white_lock";
    private static final String DEFAULT_LOCK = "edit.default_lock";
    private int scroll;
    private int hoverRow = -1;

    private final DiffuserScreen parent;
    public EditScreen() { this(null); }
    EditScreen(DiffuserScreen parent) {
        super(tr("common.editor"));
        this.parent = parent;
    }

    @Override protected Screen parentScreen() { return parent; }

    // —— 基本信息 ——

    private static CrowdData.Emitter emitter() {
        return EditTool.selected();
    }

    private int visibleRows() {
        return Math.max(1, (listHeight - 3) / LIST_ROW_PITCH);
    }
    private static List<CrowdData.Emitter.MobWeight> entityRows(CrowdData.Emitter e) {
        return e == null ? List.of() : AppearanceModes.rows(e);
    }
    private String editLockReason(){return tr(WHITE_LOCK);}
    private boolean entityLocked() { return page == 0 && (emitter() == null || AppearanceModes.isWhite(emitter())); }
    private boolean defaultsOnly() {
        List<CrowdData.Emitter.MobWeight> rows = entityRows(emitter());
        return !rows.isEmpty() && rows.stream().allMatch(AppearanceModes::isDefault);
    }
    private boolean overList(double x,double y) {
        return x >= px(LIST_X) && x <= px(LIST_X+listWidth) && y >= py(listTop) && y <= py(listTop+listHeight);
    }

    private int rowCount() {
        CrowdData.Emitter emitter = emitter();
        if (emitter == null) {
            return 0;
        }
        if (page == 0) {
            return entityRows(emitter).size();
        }
        return groundRows(emitter).size();
    }

    private static List<String> groundRows(CrowdData.Emitter emitter) {
        List<String> rows = new ArrayList<>();
        if (emitter != null && emitter.groundBlocks != null) {
            rows.addAll(emitter.groundBlocks);
        }
        return rows;
    }

    private static String keyOf(CrowdData.Emitter.MobWeight weight) {
        return weight.id + "|" + (weight.nbt == null ? "" : weight.nbt);
    }

    private static String groundName(String id) {
        try {
            ResourceLocation rl = ResourceLocation.parse(id);
            Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.getHolder(rl);
            if (holder.isPresent()) {
                Item item = holder.get().value().asItem();
                if (item != Items.AIR) {
                    return new ItemStack(item).getHoverName().getString();
                }
            }
        } catch (Throwable ignored) {
            // 返回原始 id
        }
        return id;
    }

    private static String mobName(CrowdData.Emitter.MobWeight weight) {
        if (YsmClientModels.isModelRow(weight.id)) {
            var model = ClientSkinPacks.listing(weight.id);
            return model != null ? model.name() : tr("skin_pack.missing", weight.name);
        }
        if (SkinPackFiles.isPackRow(weight.id)) {
            var pack = ClientSkinPacks.listing(weight.id);
            if (pack != null) return pack.name();
            String name = weight.name == null || weight.name.isBlank() ? SkinPackFiles.packId(weight.id) : weight.name;
            return tr("skin_pack.missing", name);
        }
        return weight.name != null && !weight.name.isBlank()
                ? weight.name
                : MobLibrary.displayNameOf(weight.id);
    }

    // —— 布局 ——

    @Override
    protected int nominalWidth() {
        return W;
    }

    @Override
    protected int nominalHeight() {
        return H;
    }

    @Override
    protected int bannerInset() {
        return BANNER_INSET;
    }

    @Override
    protected int accentColor() {
        return UiTheme.walk;
    }

    @Override
    protected int titleIcon() {
        return page == 0 ? UiIcons.MODE_WALK : UiIcons.GROUND;
    }

    @Override
    protected String titleText() {
        CrowdData.Emitter emitter = emitter();
        return tr("edit.title", emitter == null ? tr("common.not_selected")
                : DiffuserData.isDiffuser(emitter) ? tr("common.device") : emitter.id);
    }

    @Override protected String backgroundMode(){return "edit";}
    @Override protected boolean canReset(){return emitter()!=null;}
    @Override protected String resetTip(){return tr("edit.reset");}
    @Override protected String resetLockReason(){return tr("common.select_group");}
    @Override protected void resetDefaults(){
        if(!canReset())return;AppearanceModes.reset(emitter());selectedMobs.clear();selectedGround.clear();scroll=0;rebuildRowFields();syncControls();
    }

    @Override
    protected void buildWidgets() {
        previewVisible = panelW >= 280 && panelH >= 190;
        previewWidth = previewVisible ? Math.min(84,panelW/4) : 0;
        listWidth = panelW - 2*PAD - (previewVisible ? previewWidth+GAP : 0);
        previewX = LIST_X + listWidth + GAP;
        boolean compact = panelH < 150;
        int tabY = compact ? 23 : TAB_Y;
        optionY = compact ? 42 : panelH >= 190 ? 58 : 51;
        summaryY = optionY + (compact ? 19 : 24);
        listTop = summaryY + (compact ? 11 : 14);
        footerY = panelH - 24; ruleY = footerY - 7;
        listHeight = Math.max(18,Math.min(78,footerY-13-listTop));
        int buttonCount = page == 0 ? 4 : 3;
        buttonWidth = (panelW - 2 * PAD - (buttonCount - 1) * GAP) / buttonCount;
        int tabW = (panelW - 2 * PAD - GAP) / 2;
        pageObjectTab = new UiButton(px(PAD), py(tabY), tabW, TAB_H, tr("edit.object_tab"),
                UiIcons.MODE_WALK, UiButton.Style.TAB, () -> switchPage(0));
        pageGroundTab = new UiButton(px(PAD + tabW + GAP), py(tabY), tabW, TAB_H, tr("edit.ground_tab"),
                UiIcons.GROUND, UiButton.Style.TAB, () -> switchPage(1));
        pageObjectTab.accent(this.accent).tip(tr("edit.object_tab_tip"));
        pageGroundTab.accent(this.accent).tip(tr("edit.ground_tab_tip"));
        this.addRenderableWidget(pageObjectTab);
        this.addRenderableWidget(pageGroundTab);

        int optW = tabW;
        if (page == 0) {
            whiteBtn = new UiButton(px(PAD), py(optionY), optW, TAB_H, tr("edit.white"),
                    UiIcons.APPEARANCE_WHITE, UiButton.Style.BUTTON, () -> setAppearance("white"));
            skinBtn = new UiButton(px(PAD + optW + GAP), py(optionY), optW, TAB_H, tr("edit.entities"),
                    UiIcons.APPEARANCE_SKIN, UiButton.Style.BUTTON, () -> setAppearance("entity"));
            whiteBtn.accent(this.accent).tip(tr("edit.white_tip"));
            skinBtn.accent(this.accent).tip(tr("edit.entities_tip"));
            this.addRenderableWidget(whiteBtn);
            this.addRenderableWidget(skinBtn);
        } else {
            includeBtn = new UiButton(px(PAD), py(optionY), optW, TAB_H, tr("edit.include"),
                    UiIcons.GROUND_INCLUDE, UiButton.Style.BUTTON, () -> setGroundMode(0));
            excludeBtn = new UiButton(px(PAD + optW + GAP), py(optionY), optW, TAB_H, tr("edit.exclude"),
                    UiIcons.GROUND_EXCLUDE, UiButton.Style.BUTTON, () -> setGroundMode(1));
            includeBtn.accent(this.accent).tip(tr("edit.include_tip"));
            excludeBtn.accent(this.accent).tip(tr("edit.exclude_tip"));
            this.addRenderableWidget(includeBtn);
            this.addRenderableWidget(excludeBtn);
        }

        // 页脚动作
        plusBtn = new UiButton(px(PAD), py(footerY), buttonWidth, TAB_H, tr("edit.pick"),
                UiIcons.ACT_PICK, UiButton.Style.BUTTON, this::beginPick);
        plusBtn.accent(this.accent).tip(tr("edit.pick_tip"));
        this.addRenderableWidget(plusBtn);

        removeBtn = new UiButton(px(PAD + buttonWidth + GAP), py(footerY), buttonWidth, TAB_H,
                tr("common.delete"), UiIcons.MODE_DELETE, UiButton.Style.BUTTON, this::removeSelected);
        removeBtn.accent(this.accent).tip(tr("edit.remove_tip"));
        this.addRenderableWidget(removeBtn);

        resetBtn = new UiButton(px(PAD + (buttonWidth + GAP) * 2), py(footerY), buttonWidth, TAB_H,
                page == 0 ? tr("edit.balance") : tr("edit.clear"),
                page == 0 ? UiIcons.ACT_RESET : UiIcons.ACT_CLEAR,
                UiButton.Style.BUTTON, this::confirmListReset);
        resetBtn.accent(this.accent).tip(page == 0 ? tr("edit.balance_tip") : tr("edit.clear_ground"));
        this.addRenderableWidget(resetBtn);
        if (page == 0) {
            packBtn = new UiButton(px(PAD + (buttonWidth + GAP) * 3), py(footerY), buttonWidth, TAB_H,
                    tr("skin_pack.button"), UiIcons.APPEARANCE_SKIN, UiButton.Style.BUTTON, this::openPacks);
            packBtn.accent(accent).tip(tr("skin_pack.add_tip"));
            addRenderableWidget(packBtn);
        }

        rebuildRowFields();
        syncControls();
    }

    private void syncControls() {
        CrowdData.Emitter e=emitter(); boolean has=e!=null;
        boolean locked=entityLocked(), defaults=page==0 && defaultsOnly();
        String reason=!has?tr("common.select_group"):editLockReason();
        plusBtn.lock(!has || locked,reason);
        removeBtn.lock(!has || locked || defaults,defaults&&!locked?tr(DEFAULT_LOCK):reason);
        resetBtn.lock(!has || locked || defaults,defaults&&!locked?tr(DEFAULT_LOCK):reason);
        if (page == 0) {
            whiteBtn.lock(!has, reason);
            skinBtn.lock(!has, reason);
            packBtn.lock(locked, reason);
        }
        else {includeBtn.lock(!has,reason);excludeBtn.lock(!has,reason);}
        boolean single = boundRows.size() == 1;
        for (UiValue field : rowFields) {
            field.lock(locked || defaults || single,
                    locked ? reason : defaults ? tr(DEFAULT_LOCK) : tr("edit.single_weight"));
        }
    }

    /** 为可见行建立占比输入控件（滚动 / 翻页后重建）。 */
    private void rebuildRowFields() {
        for(UiValue field:rowFields) removeWidget(field);
        rowFields.clear();
        rowEmitter = emitter();
        boundRows = page == 0 && !entityLocked() ? List.copyOf(entityRows(rowEmitter)) : List.of();
        int count = page == 0 ? boundRows.size() : rowCount();
        scroll = Math.max(0, Math.min(scroll, count - visibleRows()));
        for (int i = 0; i < visibleRows() && i + scroll < count && page == 0; i++) {
            CrowdData.Emitter group = rowEmitter;
            CrowdData.Emitter.MobWeight row = boundRows.get(i + scroll);
            String key = keyOf(row);
            int rowY = py(rowTop(i));
            UiValue field = new UiValue(px(LIST_X + listWidth - 46), rowY + 1, 40, 12,
                    0, 100, 1,
                    () -> boundRows.size() == 1 ? 100 : row.weight * 100,
                    value -> {
                        if (group == emitter() && !entityLocked()
                                && group.mobs != null && group.mobs.contains(row)) {
                            EditTool.setMobWeightByKey(group, key, value / 100.0);
                        }
                    }, 0, true);
            field.tip(tr("edit.object_weight"));
            rowFields.add(field);
            addRenderableWidget(field);
        }
    }

    private int rowTop(int i) {
        return listTop + 2 + i * LIST_ROW_PITCH;
    }

    // —— 动作 ——

    private void switchPage(int target) {
        if (page == target) return;
        setFocused(null);
        page = target;
        selectedMobs.clear();
        selectedGround.clear();
        scroll = 0;
        Minecraft.getInstance().setScreen(new EditScreen(parent));
    }

    private void openPacks() {
        CrowdData.Emitter group = emitter();
        if (group != null && !entityLocked()) {
            cancelFocusedValue();
            Minecraft.getInstance().setScreen(new SkinPackScreen(this, group.id));
        }
    }

    private void beginPick() {
        CrowdData.Emitter emitter = emitter();
        if (emitter == null || entityLocked()) return;
        EditTool.pickTargetId = emitter == null ? null : emitter.id;
        if (page == 0) {
            EditTool.groundMode = false;
            EditTool.selectMode = true;
        } else {
            EditTool.selectMode = false;
            EditTool.groundMode = true;
        }
        closePanels();
    }

    private void setAppearance(String appearance) {
        CrowdData.Emitter emitter = emitter();
        if (emitter != null) {
            AppearanceModes.set(emitter,appearance);
            selectedMobs.clear(); scroll=0;
            rebuildRowFields(); syncControls();
        }
    }

    private void setGroundMode(int mode) {
        CrowdData.Emitter emitter = emitter();
        if (emitter != null) {
            emitter.groundMode = mode;
            CrowdData.save();
        }
    }

    private void confirmListReset() {
        if (page == 0) confirmAction(tr("edit.balance_confirm"), this::resetWeights);
        else confirmAction(tr("edit.clear_ground"), this::clearGround);
    }

    private void clearGround() {
        CrowdData.Emitter emitter = emitter();
        if (emitter != null) {
            emitter.groundBlocks = null;
            selectedGround.clear();
            CrowdData.save();
        }
    }

    private void resetWeights() {
        CrowdData.Emitter emitter = emitter();
        if (entityLocked() || defaultsOnly() || emitter == null || emitter.mobs == null || emitter.mobs.isEmpty()) {
            return;
        }
        double share = 1.0 / emitter.mobs.size();
        for (CrowdData.Emitter.MobWeight weight : emitter.mobs) {
            weight.weight = share;
        }
        CrowdData.save();
        rebuildRowFields();
    }

    private void removeSelected() {
        CrowdData.Emitter emitter = emitter();
        if (emitter == null || entityLocked()) {
            return;
        }
        if (page == 0) {
            if (defaultsOnly() || emitter.mobs == null || selectedMobs.isEmpty()) {
                return;
            }
            emitter.mobs.removeIf(weight -> selectedMobs.contains(keyOf(weight)));
            if (!emitter.mobs.isEmpty()) {
                double share = 1.0 / emitter.mobs.size();
                for (CrowdData.Emitter.MobWeight weight : emitter.mobs) {
                    weight.weight = share;
                }
            }
            selectedMobs.clear();
        } else {
            if (selectedGround.isEmpty()) {
                return;
            }
            if (emitter.groundBlocks != null) {
                emitter.groundBlocks.removeAll(selectedGround);
                if (emitter.groundBlocks.isEmpty()) {
                    emitter.groundBlocks = null;
                }
            }
            selectedGround.clear();
        }
        CrowdData.save();
        ToolSounds.remove();
        rebuildRowFields();
    }

    // —— 交互 ——

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entityLocked() && overList(mouseX,mouseY)) return true;
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        int lx = px(LIST_X);
        int ly = py(listTop);
        if (mouseX >= lx && mouseX <= lx + listWidth && mouseY >= ly && mouseY <= ly + listHeight) {
            int max = Math.max(0, rowCount() - visibleRows());
            int before = scroll;
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            if (before != scroll) {
                rebuildRowFields();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (entityLocked() && overList(mouseX,mouseY)) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int lx = px(LIST_X);
        int ly = py(listTop);
        if (mouseX < lx || mouseX > lx + listWidth || mouseY < ly || mouseY > ly + listHeight) {
            return false;
        }
        double rowOffset = mouseY - ly - 2;
        int visibleIndex = (int) (rowOffset / LIST_ROW_PITCH);
        int index = visibleIndex + scroll;
        if (rowOffset < 0 || visibleIndex >= visibleRows()
                || rowOffset % LIST_ROW_PITCH >= LIST_ROW_H || index >= rowCount()) {
            return false;
        }
        CrowdData.Emitter emitter = emitter();
        if (emitter == null) {
            return false;
        }
        if (page == 0) {
            List<CrowdData.Emitter.MobWeight> rows=entityRows(emitter);
            if (index >= rows.size()) return false;
            String key = keyOf(rows.get(index));
            if (Screen.hasShiftDown()) {
                selectedMobs.clear();
                for (CrowdData.Emitter.MobWeight weight : rows) {
                    selectedMobs.add(keyOf(weight));
                }
            } else if (Screen.hasControlDown()) {
                if (!selectedMobs.remove(key)) {
                    selectedMobs.add(key);
                }
            } else {
                selectedMobs.clear();
                selectedMobs.add(key);
            }
        } else {
            List<String> rows = groundRows(emitter);
            String id = rows.get(index);
            if (Screen.hasShiftDown()) {
                selectedGround.clear();
                selectedGround.addAll(rows);
            } else if (Screen.hasControlDown()) {
                if (!selectedGround.remove(id)) {
                    selectedGround.add(id);
                }
            } else {
                selectedGround.clear();
                selectedGround.add(id);
            }
        }
        return true;
    }

    // —— 绘制 ——

    @Override
    protected void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        CrowdData.Emitter emitter = emitter();
        boolean has = emitter != null;
        if (emitter != rowEmitter) {
            selectedMobs.clear();
            selectedGround.clear();
            scroll = 0;
            failedPreview = null;
            rebuildRowFields();
        } else if (page == 0) {
            List<CrowdData.Emitter.MobWeight> current = entityLocked() ? List.of() : entityRows(emitter);
            if (!boundRows.equals(current)) {
                selectedMobs.clear();
                failedPreview = null;
                rebuildRowFields();
            }
        }
        syncControls();

        pageObjectTab.setSelected(page == 0);
        pageGroundTab.setSelected(page == 1);
        plusBtn.setSelected(page == 0 ? EditTool.selectMode : EditTool.groundMode);
        if (page == 0) {
            whiteBtn.setSelected(has && AppearanceModes.isWhite(emitter));
            skinBtn.setSelected(has && !AppearanceModes.isWhite(emitter));
        } else {
            includeBtn.setSelected(has && emitter.groundMode == 0);
            excludeBtn.setSelected(has && emitter.groundMode == 1);
        }

        // 摘要行（文字底边与列表之间留 6px，且不再与铜线重叠）
        int summaryY = py(this.summaryY);
        int colRight = px(LIST_X + listWidth - GAP);
        if (page == 0) {
            String header = has ? tr("common.population", emitter.count) : tr("common.no_group");
            UiKit.text(gg, header, px(PAD), summaryY, UiTheme.muted);
            UiKit.textRight(gg, "%", colRight, summaryY, UiTheme.dim);
        } else {
            int count = has && emitter.groundBlocks != null ? emitter.groundBlocks.size() : 0;
            UiKit.text(gg, has ? tr("edit.ground_count", count) : tr("common.no_group"), px(PAD), summaryY, UiTheme.muted);
        }

        // 列表 + 预览
        UiKit.listBox(gg, px(LIST_X), py(listTop), listWidth, listHeight);
        UiKit.fillA(gg, px(LIST_X) + 1, py(listTop) + 1, listWidth - 2, 1, UiTheme.shade, 130);

        hoverRow = -1;
        if (page == 0) {
            drawMobRows(gg, emitter, mouseX, mouseY);
        } else {
            drawGroundRows(gg, emitter, mouseX, mouseY);
        }
        drawScrollbar(gg);
        drawPreviewPane(gg, emitter);
        if(entityLocked()&&has){
            UiKit.fillA(gg,px(LIST_X)+1,py(listTop)+1,listWidth-2,listHeight-2,UiTheme.shade,205);
            UiKit.icon(gg,UiIcons.LOCK,px(LIST_X+listWidth/2-6),py(listTop+Math.max(1,listHeight/2-15)),UiTheme.dim);
            if(listHeight>=34)UiKit.textCenter(gg,(page==0?tr("edit.objects_locked"):tr("edit.ground_locked")),px(LIST_X+listWidth/2),py(listTop+listHeight/2+1),UiTheme.dim);
        }

        // 页脚
        UiKit.rule(gg, px(PAD), py(ruleY), panelW - 2 * PAD);
    }

    private void drawMobRows(GuiGraphics gg, CrowdData.Emitter emitter, int mouseX, int mouseY) {
        int lx = px(LIST_X);
        int ly = py(listTop);
        if (emitter == null) {
            drawEmptyHint(gg, tr("edit.no_entities"));
            return;
        }
        List<CrowdData.Emitter.MobWeight> rows=entityRows(emitter);
        int total = rows.size();
        int maxScroll = Math.max(0, total - visibleRows());
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < visibleRows(); i++) {
            int index = i + scroll;
            if (index >= total) {
                break;
            }
            CrowdData.Emitter.MobWeight weight = rows.get(index);
            int rowY = py(rowTop(i));
            boolean selected = selectedMobs.contains(keyOf(weight));
            boolean hover = mouseX >= lx && mouseX <= lx + listWidth && mouseY >= rowY - 1
                    && mouseY <= rowY + LIST_ROW_H - 1;
            if (hover) {
                hoverRow = index;
            }
            drawRowBackground(gg, lx, rowY, listWidth, selected, hover);
            String name = UiKit.ellipsis(Minecraft.getInstance().font, mobName(weight),
                    listWidth - 62);
            UiKit.text(gg, name, lx + 8, rowY + 3, UiTheme.text);
        }
        if (total == 0) {
            drawEmptyHint(gg, tr("edit.no_entities"));
        }
        if (debugPreview && hoverRow < 0 && total > 0) {
            hoverRow = Math.min(2, total - 1);
        }
    }

    private void drawGroundRows(GuiGraphics gg, CrowdData.Emitter emitter, int mouseX, int mouseY) {
        int lx = px(LIST_X);
        int ly = py(listTop);
        List<String> rows = groundRows(emitter);
        if (rows.isEmpty()) {
            drawEmptyHint(gg, tr("edit.no_ground"));
            return;
        }
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < visibleRows(); i++) {
            int index = i + scroll;
            if (index >= rows.size()) {
                break;
            }
            String id = rows.get(index);
            int rowY = py(rowTop(i));
            boolean selected = selectedGround.contains(id);
            boolean hover = mouseX >= lx && mouseX <= lx + listWidth && mouseY >= rowY - 1
                    && mouseY <= rowY + LIST_ROW_H - 1;
            if (hover) {
                hoverRow = index;
            }
            drawRowBackground(gg, lx, rowY, listWidth, selected, hover);
            String name = UiKit.ellipsis(Minecraft.getInstance().font, groundName(id), listWidth - 16);
            UiKit.text(gg, name, lx + 8, rowY + 3, UiTheme.text);
        }
    }

    @Override protected void drawTooltips(GuiGraphics gg,int mouseX,int mouseY){
        if(entityLocked()&&emitter()!=null&&overList(mouseX,mouseY)){
            gg.flush();UiKit.tooltip(gg,editLockReason(),mouseX,mouseY);return;
        }
        super.drawTooltips(gg,mouseX,mouseY);
    }

    private void drawRowBackground(GuiGraphics gg, int lx, int rowY, int w, boolean selected, boolean hover) {
        if (selected) {
            UiKit.fillA(gg, lx + 1, rowY - 1, w - 2, LIST_ROW_H, accent, 34);
            UiKit.fillA(gg, lx + 1, rowY - 1, 2, LIST_ROW_H, accent, 220);
        } else if (hover) {
            UiKit.fillA(gg, lx + 1, rowY - 1, w - 2, LIST_ROW_H, UiTheme.hover, 70);
        }
    }

    private void drawEmptyHint(GuiGraphics gg, String text) {
        UiKit.text(gg, UiKit.ellipsis(Minecraft.getInstance().font,text,listWidth-16), px(LIST_X) + 8, py(listTop) + 6, UiTheme.dim);
    }

    private void drawScrollbar(GuiGraphics gg) {
        int total = rowCount();
        int visible = visibleRows();
        if (total <= visible) {
            return;
        }
        int lx = px(LIST_X + listWidth) - 5;
        int ly = py(listTop) + 2;
        int trackH = listHeight - 4;
        UiKit.fill(gg, lx, ly, 3, trackH, UiTheme.alpha(UiTheme.field, 200));
        int thumbH = Math.max(10, trackH * visible / total);
        int maxScroll = total - visible;
        int thumbY = ly + (trackH - thumbH) * scroll / Math.max(1, maxScroll);
        int u = UiAtlas.THUMB_U;
        int v = UiAtlas.THUMB_V;
        UiKit.blit(gg, UiKit.TEX_PANEL, lx, thumbY, u, v, 3, Math.min(thumbH, UiAtlas.THUMB_H), accent);
        if (thumbH > UiAtlas.THUMB_H) {
            UiKit.fill(gg, lx, thumbY + UiAtlas.THUMB_H, 3, thumbH - UiAtlas.THUMB_H,
                    UiTheme.alpha(accent, 150));
        }
    }

    /** 右侧常驻预览窗格：悬浮行时显示 3D 模型。 */
    private void drawPreviewPane(GuiGraphics gg, CrowdData.Emitter emitter) {
        if(!previewVisible)return;
        int x = px(previewX);
        int y = py(listTop);
        UiKit.listBox(gg, x, y, previewWidth, listHeight);
        UiKit.fillA(gg, x + 1, y + 1, previewWidth - 2, 1, UiTheme.shade, 130);

        if (page != 0) {
            int count = emitter != null && emitter.groundBlocks != null ? emitter.groundBlocks.size() : 0;
            UiKit.icon(gg, UiIcons.GROUND, x + previewWidth / 2 - 6, y + 22, UiTheme.dim);
            UiKit.textCenter(gg, tr("edit.ground_list"), x + previewWidth / 2, y + 42, UiTheme.dim);
            UiKit.textCenter(gg, tr("common.count_items", count), x + previewWidth / 2, y + 54, UiTheme.muted);
            return;
        }

        if(entityLocked()){
            UiKit.icon(gg,UiIcons.LOCK,x+previewWidth/2-6,y+Math.max(2,listHeight/2-6),UiTheme.dim);
            return;
        }
        CrowdData.Emitter.MobWeight weight = null;
        List<CrowdData.Emitter.MobWeight> rows=entityRows(emitter);
        if (hoverRow >= 0 && hoverRow < rows.size()) weight=rows.get(hoverRow);
        if (weight == null) {
            UiKit.icon(gg, UiIcons.STAT_PEOPLE, x + previewWidth / 2 - 6, y + 20, UiTheme.dim);
            UiKit.textCenter(gg, tr("edit.model_preview"), x + previewWidth / 2, y + 40, UiTheme.dim);
            return;
        }

        UiKit.fillA(gg, x + 2, y + 2, previewWidth - 4, 12, accent, 55);
        String name = UiKit.ellipsis(Minecraft.getInstance().font, mobName(weight), previewWidth - 6);
        UiKit.textCenter(gg, name, x + previewWidth / 2, y + 4, UiTheme.text);

        if (AppearanceModes.isDefault(weight)) {
            UiKit.icon(gg, UiIcons.MODE_WALK, x + previewWidth / 2 - 6,
                    y + Math.max(18, listHeight / 2 - 4), UiTheme.muted);
            if (listHeight >= 64) UiKit.textCenter(gg, tr("edit.default_entities"), x + previewWidth / 2, y + listHeight - 16, UiTheme.dim);
            return;
        }
        if (SkinPackFiles.isPackRow(weight.id)) {
            SkinLibrary.Skin skin = ClientSkinPacks.pickSkin(weight, emitter.id, 0);
            if (skin != null) {
                renderPackPreview(gg, skin, x, y);
            } else {
                UiKit.textCenter(gg, tr("skin_pack.unavailable"), x + previewWidth / 2,
                        y + listHeight / 2, UiTheme.dim);
            }
            return;
        }
        if ((weight != failedPreview || YsmClientModels.isModelRow(weight.id))
                && renderPreview(gg, weight, x, y)) return;
        failedPreview = weight;
        UiKit.textCenter(gg, tr("edit.no_preview"), x + previewWidth / 2, y + listHeight / 2, UiTheme.dim);
    }

    private void renderPackPreview(GuiGraphics gg, SkinLibrary.Skin skin, int x, int y) {
        PoseStack pose = gg.pose();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var layer = RenderType.entityCutoutNoCull(skin.texture());
        gg.enableScissor(x + 2, y + 15, x + previewWidth - 2, y + listHeight - 2);
        pose.pushPose();
        try {
            pose.translate(x + previewWidth / 2.0, y + listHeight - 7.0, 200.0);
            float scale = Math.min(22, (listHeight - 24) * .5f);
            pose.scale(scale, scale, scale);
            pose.mulPose(Axis.YP.rotationDegrees((System.currentTimeMillis() % 5000) * (360.0f / 5000.0f)));
            pose.translate(0, -1.5, 0);
            ClientSkinPacks.renderPlayer(ClientSkinPacks.previewModel(skin.slim()), pose, buffers.getBuffer(layer),
                    0xf000f0, OverlayTexture.NO_OVERLAY, -1, true);
            buffers.endBatch(layer);
        } finally {
            pose.popPose();
            gg.disableScissor();
        }
    }

    private boolean renderPreview(GuiGraphics gg, CrowdData.Emitter.MobWeight weight, int x, int y) {
        try {
            Entity entity = YsmClientModels.isModelRow(weight.id)
                    ? YsmClientModels.instance("ysm-preview", 0, weight.id, "")
                    : AppearanceModes.preview(weight);
            if (entity == null) return false;
            entity.setPos(0.0, 0.0, 0.0);
            entity.setYRot(0.0f);
            entity.setXRot(0.0f);
            entity.yRotO = 0.0f;
            entity.xRotO = 0.0f;
            entity.setOldPosAndRot();
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
            long now = System.currentTimeMillis();
            entity.tickCount = (int) (now % 100000L / 50L);
            float spin = (now % 5000L) * (360.0f / 5000.0f);
            Minecraft minecraft = Minecraft.getInstance();
            PoseStack pose = gg.pose();
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            gg.enableScissor(x + 2, y + 15, x + previewWidth - 2, y + listHeight - 2);
            pose.pushPose();
            try {
                pose.translate(x + previewWidth / 2.0, y + listHeight - 8.0, 200.0);
                pose.scale(13.5f, -13.5f, 13.5f);
                pose.mulPose(Axis.YP.rotationDegrees(spin));
                RenderPolicy.renderNpcAware(minecraft.getEntityRenderDispatcher(), entity,
                        0, 0, 0, 0, 1, pose, buffers, 0xF000F0);
            } finally {
                pose.popPose();
                try {
                    buffers.endBatch();
                } finally {
                    gg.disableScissor();
                }
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            System.getLogger("Bustling").log(System.Logger.Level.WARNING,
                    "Could not preview entity " + weight.id, failure);
            return false;
        }
    }
}
