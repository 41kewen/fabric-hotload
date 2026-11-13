package dev.fabric.hotload.host.mixin;

import dev.fabric.hotload.host.ProbeTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ProbeTarget.class, remap = false)
public abstract class ProbeTargetMixin {
    @Inject(method = "value", at = @At("HEAD"), remap = false)
    private static void countInvocation(CallbackInfoReturnable<String> callback) {
        ProbeTarget.mixinTouches++;
    }
}
