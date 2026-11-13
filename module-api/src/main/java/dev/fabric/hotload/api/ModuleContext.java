package dev.fabric.hotload.api;

public interface ModuleContext {
    AutoCloseable command(String name, ModuleCommand handler);

    AutoCloseable onTick(Runnable callback);

    <T extends AutoCloseable> T own(T resource);

    void log(String message);
}
