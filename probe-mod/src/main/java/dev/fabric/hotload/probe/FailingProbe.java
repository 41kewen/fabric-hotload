package dev.fabric.hotload.probe;

import net.fabricmc.api.ModInitializer;

/** 只用于隔离实例中的负向测试，不包含在普通探针 JAR 中。 */
public final class FailingProbe implements ModInitializer {
    @Override
    public void onInitialize() {
        throw new IllegalStateException("Intentional ordinary initializer failure");
    }
}
