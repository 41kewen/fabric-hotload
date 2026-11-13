package dev.fabric.hotload.demo;

import dev.fabric.hotload.api.HotModule;
import dev.fabric.hotload.api.ModuleContext;

public final class DemoModule implements HotModule {
    private int ticks;

    @Override
    public void start(ModuleContext context) {
        context.command("hello", arguments -> "demo-v2 ticks=" + ticks);
        context.onTick(() -> ticks++);
        context.log("demo-v2 started");
    }
}
