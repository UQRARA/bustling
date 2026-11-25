package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 数值控件：[−][ 数值 ][+]。
 *
 * <p>点击箭头或滚轮步进。点击数值可直接替换，回车或失焦提交，Esc 取消。</p>
 */
public class UiValue extends AbstractWidget {
    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final int decimals;
    private final boolean compact;
    private String tooltip;
    private String disabledTip;
    private boolean editing;
    private boolean replaceOnType;
    private String buffer = "";
    private long displayedBits = Long.MIN_VALUE;
    private String displayed;

    public UiValue(int x, int y, int w, int h, double min, double max, double step,
                   DoubleSupplier getter, DoubleConsumer setter, int decimals) {
        this(x, y, w, h, min, max, step, getter, setter, decimals, false);
    }

    public UiValue(int x, int y, int w, int h, double min, double max, double step,
                   DoubleSupplier getter, DoubleConsumer setter, int decimals, boolean compact) {
        super(x, y, w, h, Component.empty());
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.decimals = decimals;
        this.compact = compact;
    }

    public UiValue tip(String tip) {
        this.tooltip = tip;
        return this;
    }

    public String tip() {
        return !active && disabledTip != null ? disabledTip : tooltip;
    }

    /** Cancel pending edits before focus loss so locking never commits a hidden value. */
    public UiValue lock(boolean locked, String reason) {
        disabledTip = locked ? reason : null;
        active = !locked;
        if (locked) { cancelEdit(); super.setFocused(false); }
        return this;
    }

    // —— 渲染 ——

    @Override
    protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        if (!active) cancelEdit();
        boolean hover = active && isHovered();
        boolean leftHover = hover && mouseX < getX() + hotZone();
        boolean rightHover = hover && mouseX >= getX() + width - hotZone();
        UiKit.field(gg, getX(), getY(), width, height, UiTheme.field);
        if (leftHover) {
            UiKit.fillA(gg, getX() + 1, getY() + 1, hotZone() - 1, height - 2, UiTheme.hover, 70);
        }
        if (rightHover) {
            UiKit.fillA(gg, getX() + width - hotZone(), getY() + 1, hotZone() - 1, height - 2, UiTheme.hover, 70);
        }
        int chevY = getY() + (height - 6) / 2;
        int chevTint = active ? (hover ? UiTheme.text : UiTheme.muted) : UiTheme.dim;
        if (active) {
            UiKit.chevron(gg, 0, getX() + (hotZone() - 6) / 2, chevY, leftHover ? UiTheme.text : chevTint);
            UiKit.chevron(gg, 1, getX() + width - hotZone() + (hotZone() - 6) / 2, chevY,
                    rightHover ? UiTheme.text : chevTint);
        } else {
            UiKit.fillA(gg, getX()+1, getY()+1, width-2, height-2, UiTheme.shade, 100);
            UiKit.lock(gg,getX()+(compact?3:4),getY()+(height-6)/2,UiTheme.dim);
        }

        Font font = Minecraft.getInstance().font;
        String shown = editing && !replaceOnType ? buffer + "_" : text();
        int centerX = getX() + width / 2;
        int tx = centerX - font.width(shown) / 2;
        // 输入态中心可能超出箭头区，做一次裁剪
        int scissorL = getX() + hotZone();
        int scissorR = getX() + width - hotZone();
        gg.enableScissor(scissorL, getY(), scissorR, getY() + height);
        try {
            // Keep the most recent digits visible while typing into a narrow field.
            int drawX = editing && !replaceOnType && font.width(shown) > scissorR - scissorL
                    ? scissorR - font.width(shown) : Math.max(tx, scissorL);
            gg.drawString(font, shown, drawX, getY() + (height - 8) / 2,
                    active ? UiTheme.text : UiTheme.dim, false);
        } finally {
            gg.disableScissor();
        }
    }

    private int hotZone() {
        return compact ? 10 : 12;
    }

    private String text() {
        double value = getter.getAsDouble();
        long bits = Double.doubleToLongBits(value);
        if (displayed == null || bits != displayedBits) {
            displayedBits = bits;
            displayed = decimals <= 0 ? Long.toString(Math.round(value))
                    : String.format(Locale.ROOT, "%." + decimals + "f", value);
        }
        return displayed;
    }

    // —— 交互 ——

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!(active && visible) || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (button != 0) {
            return false;
        }
        if (mouseX < getX() + hotZone()) {
            nudge(-1, Screen.hasShiftDown() ? 10 : 1);
            return true;
        }
        if (mouseX >= getX() + width - hotZone()) {
            nudge(1, Screen.hasShiftDown() ? 10 : 1);
            return true;
        }
        beginEdit();
        return true;
    }

    private void beginEdit() {
        if (!active || !visible) return;
        if (editing) return;
        editing = true;
        replaceOnType = true;
        buffer = text();
    }

    private void nudge(double direction, int multiplier) {
        if (!active || !visible) return;
        cancelEdit();
        double current = getter.getAsDouble();
        double scale = Math.pow(10, decimals);
        double value = clamp(Math.round((current + direction * step * multiplier) * scale) / scale);
        if (Double.compare(current, value) != 0) setter.accept(value);
    }

    private double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }

    private void commit() {
        boolean changed = !replaceOnType;
        cancelEdit();
        if (!active || !visible || !changed) return;
        double parsed;
        try {
            parsed = Double.parseDouble(buffer);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (!Double.isFinite(parsed)) return;
        double scale = Math.pow(10, decimals);
        double value = clamp(Math.round(clamp(parsed) * scale) / scale);
        if (Double.compare(value, getter.getAsDouble()) != 0) setter.accept(value);
    }

    void cancelEdit() {
        editing = false;
        replaceOnType = false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !visible) { editing = false; return false; }
        if (!editing) {
            if (keyCode != 257 && keyCode != 335 && keyCode != 32) return false;
            beginEdit();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter / 小键盘回车
            commit();
            return true;
        }
        if (keyCode == 256) { // Esc
            cancelEdit();
            return true;
        }
        if (keyCode == 65 && Screen.hasControlDown()) {
            replaceOnType = true;
            return true;
        }
        if (keyCode == 259) { // Backspace
            if (replaceOnType) {
                buffer = "";
                replaceOnType = false;
                return true;
            }
            if (!buffer.isEmpty()) {
                buffer = buffer.substring(0, buffer.length() - 1);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!active || !visible) { editing = false; return false; }
        if (!editing) {
            return false;
        }
        if (chr == '-' && min < 0) {
            if (replaceOnType) buffer = "-";
            else if (!buffer.startsWith("-") && buffer.length() < 8) buffer = "-" + buffer;
            replaceOnType = false;
            return true;
        }
        if ((chr >= '0' && chr <= '9') || chr == '.' || chr == ',') {
            if (replaceOnType) {
                buffer = "";
                replaceOnType = false;
            }
            if (buffer.length() < 8 && (chr != '.' && chr != ',' || decimals > 0 && !buffer.contains("."))) {
                buffer += chr == ',' ? '.' : chr;
            }
            return true;
        }
        return false;
    }

    @Override
    public void setFocused(boolean focused) {
        if (!active || !visible) { editing = false; super.setFocused(false); return; }
        if (!focused && editing) {
            commit();
        }
        super.setFocused(focused);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!(active && visible) || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        double dir = Math.signum(scrollY);
        if (dir == 0) {
            return false;
        }
        nudge(dir, Screen.hasShiftDown() ? 10 : 1);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(text()));
    }
}
