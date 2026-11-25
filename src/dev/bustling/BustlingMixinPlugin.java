package dev.bustling;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Optional target discovery through bytecode only. */
public final class BustlingMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        if (mixin.endsWith("DevicePlacementMixin") || mixin.endsWith("DeviceLifecycleMixin") || mixin.endsWith("DeviceSpawnerMixin")
                || mixin.endsWith("DeviceRedstoneMixin")
                || mixin.endsWith("ToolItemModelMixin") || mixin.endsWith("DeviceModelMixin")
                || mixin.endsWith("ClientDevicePlacementMixin")) return true;
        if(!target.equals("net.irisshaders.iris.shadows.ShadowRenderer")
                && !target.equals("noppes.npcs.client.layer.LayerGlow"))return false;
        try{return MixinService.getService().getBytecodeProvider().getClassNode(target,true)!=null;}
        catch(Exception|LinkageError absent){return false;}
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String name,ClassNode target,String mixin,IMixinInfo info){}
    public void postApply(String name,ClassNode target,String mixin,IMixinInfo info){}
}
