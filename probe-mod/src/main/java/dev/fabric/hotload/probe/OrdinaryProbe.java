package dev.fabric.hotload.probe;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** 标准 Fabric Mod，不依赖宿主或受管模块 API。 */
public final class OrdinaryProbe implements ModInitializer, DedicatedServerModInitializer {
    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        var container = loader.getModContainer("hotload_probe").orElseThrow();
        if (!loader.isModLoaded("hotload_probe") || loader.getEntrypointContainers("main", ModInitializer.class)
                .stream().noneMatch(entry -> entry.getProvider().getMetadata().getId().equals("hotload_probe"))) {
            throw new IllegalStateException("Loader metadata/entrypoints are inconsistent");
        }
        try {
            String resource = Files.readString(container.findPath("hotload-probe.txt").orElseThrow()).trim();
            if (!resource.equals("ordinary-resource-ok")) { throw new IllegalStateException("Resource mismatch"); }
            loader.getObjectShare().put("hotload_probe:resource", resource);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot read ordinary mod resource", exception); }
        AtomicInteger starts = new AtomicInteger(1);
        if (loader.getObjectShare().putIfAbsent("hotload_probe:main", starts) != null) {
            throw new IllegalStateException("Ordinary mod was initialized twice");
        }
        AtomicLong ticks = new AtomicLong();
        loader.getObjectShare().put("hotload_probe:ticks", ticks);
        ServerTickEvents.END_SERVER_TICK.register(server -> ticks.incrementAndGet());
    }

    @Override
    public void onInitializeServer() {
        FabricLoader.getInstance().getObjectShare().put("hotload_probe:server", Boolean.TRUE);
    }
}
