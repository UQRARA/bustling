package dev.bustling;

import static dev.bustling.Texts.tr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Centered tool HUD above the hotbar; native actionbar and item-name layers remain free. */
public final class ToolHud {
    private static final int MARGIN=8, PADDING=6, BOTTOM_CLEARANCE=72;
    private static final int BACKGROUND=0xE52A313D, OUTLINE=0xEE171B22;
    private static final int TEXT=0xFFF2EEE6;
    private static final int AREA=0x86CFD6, PATH=0xE7CF87, DELETE=0xE79A90;
    private static final int EDIT=0x96CFA4, WORLD=0x91AFDA, COPY=0xC7A57F;
    private static String notice="";
    private static Object noticeWorld;
    private static long noticeUntilTick,noticeUntilNanos;

    private ToolHud() {}

    public static void notify(String text) {
        if(text==null || text.isBlank())return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null)return;
        String clean=text.replace('\n',' ').replace('\r',' ').trim();
        int count=clean.codePointCount(0,clean.length());
        if(count>512)clean=clean.substring(0,clean.offsetByCodePoints(0,512));
        notice=clean;
        noticeWorld=mc.level;
        noticeUntilTick=mc.level.getGameTime()+80;
        noticeUntilNanos=System.nanoTime()+4_000_000_000L;
    }

    /** Replacement for known client-only tool feedback calls; boolean kept for call-site ABI. */
    public static void message(Player player,Component component,boolean actionbar) {
        if(component!=null)notify(component.getString());
    }

    /** Keep selection feedback state without forcing vanilla's bottom item-name popup. */
    public static void flash() {
        SelectionTool.noteStateChanged();
    }

    private static String group(CrowdData.Emitter emitter) {
        if (emitter == null || emitter.id == null || emitter.id.isBlank()) return tr("common.not_selected");
        return DiffuserData.isDiffuser(emitter) ? tr("common.device") : emitter.id;
    }

    private static String status(Minecraft mc, ItemStack item) {
        if (ToolItems.isRegion(item)) {
            String mode = tr(switch (ToolConfig.mode) {
                case AREA -> "common.area";
                case PATH -> "common.path";
                case DELETE -> "common.delete";
            });
            CrowdData.Emitter target = ToolAim.current(mc).target();
            String label = group(target);
            if (ToolConfig.mode == ToolConfig.Mode.AREA && !SelectionTool.areaPoints().isEmpty()) {
                label = tr("hud.area_points", SelectionTool.areaPoints().size());
            }
            CrowdData.Emitter active = SelectionTool.activePath;
            if (ToolConfig.mode == ToolConfig.Mode.PATH && active != null) {
                label = tr("hud.path_points", group(active), active.path == null ? 0 : active.path.size());
            }
            return tr("hud.separator", mode, label);
        }
        if (ToolItems.isObject(item)) {
            if (EditTool.groundMode) return tr("hud.separator", tr("hud.ground"), group(EditTool.selected()));
            if (EditTool.selectMode) return tr("hud.separator", tr("hud.pick"), group(EditTool.selected()));
            return tr("hud.separator", tr("common.editor"), group(ToolAim.current(mc).target()));
        }
        if (ToolItems.isCopy(item)) return tr("hud.separator", tr("common.copy"), CopyTool.stateText());
        if (ToolItems.isSettings(item)) {
            String count = WorldSystem.useEmitterCounts ? tr("world.layout_counts") : tr("world.local_cap", WorldSystem.maxTotal);
            String active = WorldSystem.activeEnabled ? tr("common.active_radius", WorldSystem.activeRadius) : tr("world.passive");
            return tr("hud.separator", tr("common.settings"), count + " · " + active);
        }
        return null;
    }

    private static int accent(ItemStack item){
        if(ToolItems.isRegion(item))return switch(ToolConfig.mode){case AREA->AREA;case PATH->PATH;case DELETE->DELETE;};
        if(ToolItems.isObject(item))return EDIT;
        return ToolItems.isSettings(item)?WORLD:COPY;
    }

    private static int tint(int rgb){
        int r=((BACKGROUND>>16)&255)*4+(rgb>>16&255),g=((BACKGROUND>>8)&255)*4+(rgb>>8&255),b=(BACKGROUND&255)*4+(rgb&255);
        return 0xE5000000|(r/5<<16)|(g/5<<8)|(b/5);
    }

    private static String fit(Font font,String text,int width) {
        if(text==null || width<=0)return "";
        if(font.width(text)<=width)return text;
        String ellipsis="…";
        if(font.width(ellipsis)>width)return "";
        int low=0,high=text.codePointCount(0,text.length());
        while(low<high){
            int middle=(low+high+1)/2;
            String prefix=text.substring(0,text.offsetByCodePoints(0,middle));
            if(font.width(prefix+ellipsis)<=width)low=middle;else high=middle-1;
        }
        return text.substring(0,text.offsetByCodePoints(0,low))+ellipsis;
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null || mc.screen!=null)return;
        ItemStack held=mc.player.getMainHandItem();
        if(!(ToolItems.isRegion(held) || ToolItems.isObject(held) || ToolItems.isCopy(held) || ToolItems.isSettings(held)))return;
        String state=status(mc,held);
        GuiGraphics gg=event.getGuiGraphics();
        int available=gg.guiWidth()-MARGIN*2,availableHeight=gg.guiHeight()-MARGIN*2;
        if(available<=PADDING*2||availableHeight<20)return;
        String first=fit(mc.font,state,available-PADDING*2);
        boolean visible=!notice.isEmpty() && noticeWorld==mc.level && mc.level.getGameTime()<noticeUntilTick
                && System.nanoTime()-noticeUntilNanos<0;
        String second=visible&&availableHeight>=34?fit(mc.font,notice,available-PADDING*2):"";
        int width=Math.min(available,Math.max(mc.font.width(first),mc.font.width(second))+PADDING*2);
        int height=second.isEmpty()?20:34;
        int left=(gg.guiWidth()-width)/2;
        int bottom=Math.min(gg.guiHeight()-MARGIN,Math.max(MARGIN+height,gg.guiHeight()-BOTTOM_CLEARANCE)),top=bottom-height;
        int color=0xFF000000|accent(held);
        gg.fill(left,top,left+width,bottom,OUTLINE);
        gg.fill(left+1,top+1,left+width-1,bottom-1,tint(color));
        gg.fill(left+1,top+1,left+width-1,top+2,color);
        int stateY=bottom-13;
        gg.drawString(mc.font,first,(gg.guiWidth()-mc.font.width(first))/2,stateY,color,false);
        if(!second.isEmpty())gg.drawString(mc.font,second,(gg.guiWidth()-mc.font.width(second))/2,stateY-13,TEXT,false);
    }
}
