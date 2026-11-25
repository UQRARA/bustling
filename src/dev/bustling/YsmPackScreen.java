package dev.bustling;

import static dev.bustling.Texts.tr;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

final class YsmPackScreen extends UiScreen {
    private static final int PAD = 6;
    private static final int LIST_TOP = 42;
    private static final int ROW_PITCH = 22;
    private static final long DOUBLE_CLICK_MS = 350;

    private final Screen parent;
    private final EditScreen editor;
    private final String groupId;
    private final String prefix;
    private final Map<Entry, UiButton> rowButtons = new LinkedHashMap<>();
    private List<Entry> entries = List.of();
    private Entry selected;
    private UiButton addButton;
    private int revision;
    private int offset;
    private int visibleRows;
    private long lastClick;
    private boolean folderUnavailable;

    record Entry(String label, String folder, List<YsmClientModels.Model> models) {}

    YsmPackScreen(Screen parent, EditScreen editor, String groupId, String prefix) {
        super(tr("ysm_pack.title"));
        this.parent = parent;
        this.editor = editor;
        this.groupId = groupId;
        this.prefix = prefix;
    }

    @Override protected boolean canReset() { return false; }
    @Override protected int accentColor() { return UiTheme.walk; }
    @Override protected int titleIcon() { return UiIcons.APPEARANCE_SKIN; }
    @Override protected String backgroundMode() { return "edit"; }
    @Override protected Screen parentScreen() { return parent; }

    static List<Entry> entries(List<YsmClientModels.Model> catalog, String prefix) {
        Map<String, List<YsmClientModels.Model>> folders = new LinkedHashMap<>();
        List<Entry> models = new ArrayList<>();
        for (var model : catalog) {
            if (!model.id().startsWith(prefix)) continue;
            String local = model.id().substring(prefix.length());
            int slash = local.indexOf('/');
            if (slash < 0) {
                models.add(new Entry(model.listing().name(), null, List.of(model)));
            } else {
                String folder = local.substring(0, slash);
                folders.computeIfAbsent(folder, key -> new ArrayList<>()).add(model);
            }
        }
        List<Entry> rows = new ArrayList<>(folders.size() + models.size());
        folders.forEach((folder, children) ->
                rows.add(new Entry(folder, prefix + folder + "/", List.copyOf(children))));
        rows.addAll(models);
        return rows;
    }

    @Override protected void buildWidgets() {
        revision = YsmClientModels.revision();
        entries = entries(YsmClientModels.models(), prefix);
        if (selected != null && !entries.contains(selected)) selected = null;
        visibleRows = Math.max(1, (panelH - LIST_TOP - 28) / ROW_PITCH);
        offset = Math.clamp(offset, 0, maxOffset());
        rowButtons.clear();
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < visibleRows && i + offset < entries.size(); i++) {
            Entry entry = entries.get(i + offset);
            String text = entry.folder() == null ? entry.label()
                    : tr("ysm_pack.folder_row", entry.label(), entry.models().size());
            String label = UiKit.ellipsis(font, text, panelW - 2 * PAD - 28);
            UiButton button = new UiButton(px(PAD), py(LIST_TOP + ROW_PITCH * i), panelW - 2 * PAD, 18,
                    label, entry.folder() == null ? UiIcons.APPEARANCE_SKIN : UiIcons.ACT_FOLDER,
                    UiButton.Style.BUTTON, () -> select(entry));
            button.accent(accent);
            button.tip(entry.folder() == null ? entry.models().getFirst().id() : tr("ysm_pack.folder_tip"));
            rowButtons.put(entry, addRenderableWidget(button));
        }

        int footer = panelH - 24;
        int buttonWidth = Math.min(100, (panelW - 58 - 2 * PAD) / 3);
        addButton = addRenderableWidget(new UiButton(px(PAD), py(footer), buttonWidth, 18,
                tr("ysm_pack.add"), UiIcons.ACT_PICK, UiButton.Style.BUTTON, this::addSelected).accent(accent));
        addRenderableWidget(new UiButton(px(2 * PAD + buttonWidth), py(footer), buttonWidth, 18,
                tr("ysm_pack.open_folder"), UiIcons.ACT_FOLDER, UiButton.Style.BUTTON, this::openFolder).accent(accent)
                .tip(tr("ysm_pack.folder_path")));
        boolean local = Minecraft.getInstance().getSingleplayerServer() != null;
        addRenderableWidget(new UiButton(px(3 * PAD + 2 * buttonWidth), py(footer), buttonWidth, 18,
                tr("skin_pack.reload"), UiIcons.ACT_RESET, UiButton.Style.BUTTON, YsmClientModels::reloadLocalModels)
                .accent(accent)).lock(!local, tr("ysm_pack.server_reload"));
        arrow(UiIcons.NAV_LEFT, panelW - 46, footer, -visibleRows).lock(offset == 0, "");
        arrow(UiIcons.NAV_RIGHT, panelW - 24, footer, visibleRows).lock(offset == maxOffset(), "");
        syncSelection();
    }

    private void select(Entry row) {
        long now = Util.getMillis();
        if (row.equals(selected) && now - lastClick <= DOUBLE_CLICK_MS) {
            if (row.folder() != null) {
                Minecraft.getInstance().setScreen(new YsmPackScreen(this, editor, groupId, row.folder()));
            } else {
                addSelected();
            }
            return;
        }
        selected = row;
        lastClick = now;
        syncSelection();
    }

    private void syncSelection() {
        rowButtons.forEach((row, button) -> button.setSelected(row.equals(selected)));
        addButton.lock(selected == null, tr("ysm_pack.select"));
    }

    private void addSelected() {
        CrowdData.Emitter group = EditTool.selected();
        if (selected == null || group == null || !groupId.equals(group.id)) return;
        if (YsmClientModels.addModels(group, selected.models())) {
            Minecraft.getInstance().setScreen(editor);
        }
    }

    private void openFolder() {
        try {
            Util.getPlatform().openPath(Files.createDirectories(YsmSupport.customFolder()));
            folderUnavailable = false;
        } catch (IOException | RuntimeException failure) {
            folderUnavailable = true;
            Bustling.LOGGER.warn("Could not open the YSM model folder", failure);
        }
    }

    private int maxOffset() {
        return Math.max(0, entries.size() - visibleRows);
    }

    private UiButton arrow(int icon, int x, int y, int change) {
        return addRenderableWidget(new UiButton(px(x), py(y), 18, 18, "", icon, UiButton.Style.BUTTON,
                () -> scroll(change)).accent(accent));
    }

    private void scroll(int change) {
        int next = Math.clamp(offset + change, 0, maxOffset());
        if (next != offset) {
            offset = next;
            rebuild();
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override public void tick() {
        super.tick();
        if (revision != YsmClientModels.revision()) rebuild();
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x >= left && x < left + panelW && y >= py(LIST_TOP) && y < py(panelH - 26)) {
            scroll(-(int)Math.signum(dy));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override protected void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        String status = folderUnavailable ? tr("skin_pack.folder_unavailable")
                : entries.isEmpty() ? tr("ysm_pack.empty")
                : prefix.isEmpty() ? tr("ysm_pack.select") : prefix;
        UiKit.text(gg, UiKit.ellipsis(Minecraft.getInstance().font, status, panelW - 2 * PAD),
                px(PAD), py(27), UiTheme.muted);
    }
}
