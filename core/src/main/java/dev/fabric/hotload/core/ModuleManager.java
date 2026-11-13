package dev.fabric.hotload.core;

import dev.fabric.hotload.api.HotModule;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 所有生命周期和分派均在创建此对象的服务器线程串行运行。 */
public final class ModuleManager implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final JarInspector inspector;
    private final CandidateStore store;
    private final OperationJournal journal;
    private final ClassLoader parent;
    private final Consumer<String> logger;
    private final Map<String, Active> active = new LinkedHashMap<>();
    private boolean dirty;

    public ModuleManager(Path directory, ClassLoader parent, Consumer<String> logger) throws Exception {
        inspector = new JarInspector(directory.resolve("candidates"));
        store = new CandidateStore(directory.resolve("store"));
        journal = new OperationJournal(directory.resolve("operations.jsonl"));
        this.parent = parent;
        this.logger = logger;
    }

    private void check(boolean mutation) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Operation must run on server thread");
        }
        if (mutation && dirty) {
            throw new IllegalStateException("DIRTY_RESTART_REQUIRED: previous cleanup/activation failed");
        }
    }

    public JarInspection inspect(String filename) throws Exception {
        check(false);
        return inspector.inspect(filename);
    }

    public String load(String filename) throws Exception {
        check(true);
        JarInspection info = inspect(filename);
        validateModule(info);
        if (active.containsKey(info.id())) {
            throw new IllegalArgumentException("Already active; use reload: " + info.id());
        }
        return activate(info);
    }

    private static void validateModule(JarInspection info) {
        if (info.kind() != JarInspection.Kind.MODULE || !info.blockers().isEmpty()) {
            throw new IllegalArgumentException("Not a supported managed module: " + info.blockers());
        }
    }

    private String activate(JarInspection info) throws Exception {
        Path staged = store.stage(info);
        journal.record("load", info.id(), info.sha256(), "ACTIVATING", info.version());
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{staged.toUri().toURL()}, parent);
        ManagedContext context = new ManagedContext(message -> logger.accept("[" + info.id() + "] " + message));
        HotModule module = null;
        boolean codeStarted = false;
        try {
            Class<?> type = Class.forName(info.entrypoint(), false, loader);
            if (type.getClassLoader() != loader || !HotModule.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Entrypoint is not an isolated HotModule");
            }
            codeStarted = true;
            module = (HotModule) type.getDeclaredConstructor().newInstance();
            module.start(context);
            journal.record("load", info.id(), info.sha256(), "STARTED_UNVERIFIED",
                    "Initializer returned; verify actual commands/events before declaring compatibility");
            active.put(info.id(), new Active(info, loader, context, module));
            return "MODULE_STARTED " + info.id() + " version=" + info.version() + " sha256=" + info.sha256();
        } catch (Exception | LinkageError failure) {
            Exception cleanup = cleanup(module, context, loader);
            // 任意模块代码可能已有未受管副作用；只报告受管资源的清理范围。
            dirty = codeStarted || cleanup != null;
            if (cleanup != null) {
                failure.addSuppressed(cleanup);
            }
            journal.record("load", info.id(), info.sha256(), dirty ? "DIRTY_RESTART_REQUIRED" : "FAILED_CLEAN",
                    "Managed cleanup only; initialization side effects are not rolled back: " + failure);
            throw failure;
        }
    }

    public String reload(String filename) throws Exception {
        check(true);
        JarInspection info = inspect(filename);
        validateModule(info);
        if (!active.containsKey(info.id())) {
            throw new IllegalArgumentException("No active module with id " + info.id());
        }
        // P0 采用停用后加载；不承诺原子状态迁移或失败时自动重启旧版本。
        store.stage(info);
        disable(info.id());
        return activate(info);
    }

    public String disable(String id) throws Exception {
        check(false);
        Active entry = active.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("No active module: " + id);
        }
        journal.record("disable", id, entry.info().sha256(), "QUIESCING", "Stopping managed dispatch");
        active.remove(id);
        Exception failure = cleanup(entry.module(), entry.context(), entry.loader());
        if (failure != null) {
            dirty = true;
            journal.record("disable", id, entry.info().sha256(), "DIRTY_RESTART_REQUIRED", failure.toString());
            throw failure;
        }
        try {
            journal.record("disable", id, entry.info().sha256(), "DISABLED",
                    "Managed handles cleared; JVM class unloading is not guaranteed");
        } catch (java.io.IOException exception) {
            dirty = true;
            throw new IllegalStateException("DIRTY_RESTART_REQUIRED: cannot record completed cleanup", exception);
        }
        return "MODULE_DISABLED " + id;
    }

    private static Exception cleanup(HotModule module, ManagedContext context, URLClassLoader loader) {
        Exception failure = null;
        try {
            if (module != null) {
                module.stop();
            }
        } catch (Exception | LinkageError exception) {
            if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            failure = new Exception("Module stop failed", exception);
        }
        try {
            context.close();
        } catch (Exception exception) {
            if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); }
        }
        try {
            loader.close();
        } catch (Exception exception) {
            if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); }
        }
        return failure;
    }

    public String execute(String id, String command, List<String> arguments) throws Exception {
        check(false);
        Active entry = active.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("No active module: " + id);
        }
        return entry.context().execute(command, arguments);
    }

    public void tick() {
        check(false);
        for (Active entry : List.copyOf(active.values())) {
            try {
                entry.context().tick();
            } catch (RuntimeException | LinkageError failure) {
                dirty = true;
                logger.accept("Module tick failed: " + entry.info().id() + ": " + failure);
                try {
                    disable(entry.info().id());
                } catch (Exception cleanup) {
                    dirty = true;
                    logger.accept("DIRTY_RESTART_REQUIRED: " + cleanup);
                }
            }
        }
    }

    public List<String> status() {
        check(false);
        return active.values().stream().map(entry -> entry.info().id() + " version=" + entry.info().version()
                + " resources=" + entry.context().resourceCount()).toList();
    }

    public int resourceCount() {
        check(false);
        return active.values().stream().mapToInt(entry -> entry.context().resourceCount()).sum();
    }

    public boolean dirty() {
        return dirty;
    }

    @Override
    public void close() {
        check(false);
        Exception failure = null;
        for (String id : List.copyOf(active.keySet())) {
            try { disable(id); } catch (Exception exception) {
                if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); }
            }
        }
        if (failure != null) { throw new IllegalStateException("Managed shutdown failed", failure); }
    }

    private record Active(JarInspection info, URLClassLoader loader, ManagedContext context, HotModule module) {
    }
}
