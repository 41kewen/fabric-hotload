package dev.fabric.hotload.core;

import dev.fabric.hotload.api.ModuleCommand;
import dev.fabric.hotload.api.ModuleContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class ManagedContext implements ModuleContext, AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Consumer<String> logger;
    private final Map<String, ModuleCommand> commands = new LinkedHashMap<>();
    private final List<Runnable> ticks = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;

    ManagedContext(Consumer<String> logger) {
        this.logger = logger;
    }

    private void check() {
        if (Thread.currentThread() != owner || closed) {
            throw new IllegalStateException("Module context is closed or accessed off its owner thread");
        }
    }

    @Override
    public AutoCloseable command(String name, ModuleCommand handler) {
        check();
        Objects.requireNonNull(handler);
        if (!name.matches("[a-z][a-z0-9_-]{0,31}") || commands.putIfAbsent(name, handler) != null) {
            throw new IllegalArgumentException("Invalid or duplicate module command: " + name);
        }
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Command removal must run on owner thread");
            }
            commands.remove(name);
        };
    }

    @Override
    public AutoCloseable onTick(Runnable callback) {
        check();
        ticks.add(Objects.requireNonNull(callback));
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Listener removal must run on owner thread");
            }
            ticks.remove(callback);
        };
    }

    @Override
    public <T extends AutoCloseable> T own(T resource) {
        check();
        resources.add(Objects.requireNonNull(resource));
        return resource;
    }

    @Override
    public void log(String message) {
        check();
        logger.accept(message);
    }

    String execute(String command, List<String> arguments) throws Exception {
        check();
        ModuleCommand handler = commands.get(command);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown module command: " + command);
        }
        return handler.execute(List.copyOf(arguments));
    }

    void tick() {
        check();
        for (Runnable callback : List.copyOf(ticks)) {
            callback.run();
        }
    }

    int resourceCount() {
        return commands.size() + ticks.size() + resources.size();
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Cleanup must run on owner thread");
        }
        if (closed) {
            return;
        }
        closed = true;
        commands.clear();
        ticks.clear();
        RuntimeException failure = null;
        for (AutoCloseable resource : resources.reversed()) {
            try {
                resource.close();
            } catch (Exception | LinkageError exception) {
                if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                if (failure == null) {
                    failure = new IllegalStateException("Some owned resources did not close", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        resources.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
