package dev.bustling.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DynamicTexture.class)
public abstract class DynamicTextureUploadMixin {
    @Shadow private NativeImage pixels;

    @Inject(method = "lambda$new$0()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bustling$skipDisposedUpload(CallbackInfo callback) {
        // A queued constructor upload can run after close() clears its pixels.
        if (this.pixels == null) callback.cancel();
    }
}
