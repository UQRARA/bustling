package dev.bustling;

import static dev.bustling.Texts.tr;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class SkinPackScreen extends UiScreen {
    private static final int PAD = 6;
    private static final int LIST_TOP = 42;
    private static final int ROW_PITCH = 22;
    private final EditScreen parent;
    private final String groupId;
    private List<ClientSkinPacks.Listing> rows = List.of();
    private int offset;
    private int visibleRows;
    private int revision;
    private boolean folderUnavailable;

    SkinPackScreen(EditScreen parent, String groupId) {
        super(tr("skin_pack.title"));
        this.parent = parent;
        this.groupId = groupId;
        ClientSkinPacks.reload();
    }

    @Override protected int accentColor() { return UiTheme.walk; }
    @Override protected int titleIcon() { return UiIcons.APPEARANCE_SKIN; }
    @Override protected String backgroundMode() { return "edit"; }
    @Override protected Screen parentScreen() { return parent; }
    @Override protected boolean canReset() { return false; }

    @Override protected void buildWidgets() {
        revision = ClientSkinPacks.revision();
        rows = ClientSkinPacks.listings();
        visibleRows = Math.max(1, (panelH - LIST_TOP - 28) / ROW_PITCH);
        offset = Math.clamp(offset, 0, maxOffset());
        var emitter = EditTool.selected();
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < visibleRows && i + offset < rows.size(); i++) {
            ClientSkinPacks.Listing row = rows.get(i + offset);
            String source = tr(row.source().label);
            String details = tr("skin_pack.entry", "", source, row.count());
            String name = UiKit.ellipsis(font, row.name(), Math.max(0, panelW - 42 - font.width(details)));
            String label = tr("skin_pack.entry", name, source, row.count());
            UiButton button = new UiButton(px(PAD), py(LIST_TOP + i * ROW_PITCH), panelW - 2 * PAD, 18,
                    label, UiIcons.APPEARANCE_SKIN, UiButton.Style.BUTTON, () -> addPack(row));
            button.accent(accent);
            boolean assigned = emitter != null && emitter.mobs != null && emitter.mobs.stream()
                    .anyMatch(weight -> weight != null && (SkinPackFiles.ROW_PREFIX + row.id()).equals(weight.id));
            button.setSelected(assigned);
            button.lock(assigned, tr("skin_pack.already_added"));
            if (!assigned && !row.description().isBlank()) {
                button.setTooltip(Tooltip.create(Component.literal(row.description())));
            }
            addRenderableWidget(button);
        }
        int footer = panelH - 24;
        int buttonWidth = Math.min(100, (panelW - 58 - PAD) / 2);
        addRenderableWidget(new UiButton(px(PAD), py(footer), buttonWidth, 18, tr("skin_pack.open_folder"),
                UiIcons.ACT_FOLDER, UiButton.Style.BUTTON, this::openFolder).accent(accent)
                .tip(tr("skin_pack.open_folder_tip")));
        addRenderableWidget(new UiButton(px(2 * PAD + buttonWidth), py(footer), buttonWidth, 18, tr("skin_pack.reload"),
                UiIcons.ACT_RESET, UiButton.Style.BUTTON, () -> ClientSkinPacks.reload()).accent(accent)
                .tip(tr("skin_pack.reload_tip")));
        UiButton up = arrow(UiIcons.NAV_LEFT, panelW - 46, footer, -visibleRows);
        UiButton down = arrow(UiIcons.NAV_RIGHT, panelW - 24, footer, visibleRows);
        up.lock(offset == 0, "");
        down.lock(offset == maxOffset(), "");
    }

    private void openFolder() {
        try {
            var folder = Files.createDirectories(SkinPackFiles.root());
            Util.getPlatform().openPath(folder);
            folderUnavailable = false;
        } catch (IOException | RuntimeException error) {
            folderUnavailable = true;
            Bustling.LOGGER.warn("Could not open the local skin pack folder", error);
        }
    }

    private UiButton arrow(int icon, int x, int y, int step) {
        return addRenderableWidget(new UiButton(px(x), py(y), 18, 18, "", icon,
                UiButton.Style.BUTTON, () -> scroll(step)).accent(accent));
    }

    private int maxOffset() {
        return Math.max(0, rows.size() - visibleRows);
    }

    private void scroll(int step) {
        int target = Math.clamp(offset + step, 0, maxOffset());
        if (target != offset) {
            offset = target;
            rebuild();
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void addPack(ClientSkinPacks.Listing row) {
        CrowdData.Emitter group = EditTool.selected();
        if (group == null || !groupId.equals(group.id) || AppearanceModes.isWhite(group)) return;
        String id = SkinPackFiles.ROW_PREFIX + row.id();
        if (ClientSkinPacks.listing(id) == null) return;
        AppearanceModes.addMob(group, id, "", row.name());
        Minecraft.getInstance().setScreen(parent);
    }

    @Override public void tick() {
        super.tick();
        if (revision != ClientSkinPacks.revision()) rebuild();
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x >= left && x < left + panelW && y >= py(LIST_TOP) && y < py(panelH - 26)) {
            scroll(-(int) Math.signum(dy));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override protected void drawContent(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        String status = tr(folderUnavailable ? "skin_pack.folder_unavailable"
                : ClientSkinPacks.loading() ? "skin_pack.loading"
                : rows.isEmpty() ? "skin_pack.empty" : "skin_pack.select");
        UiKit.text(gg, UiKit.ellipsis(Minecraft.getInstance().font, status, panelW - 2 * PAD),
                px(PAD), py(27), UiTheme.muted);
    }
}
