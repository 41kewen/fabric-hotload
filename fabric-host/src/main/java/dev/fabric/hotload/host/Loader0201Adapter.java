package dev.fabric.hotload.host;

import dev.fabric.hotload.core.CandidateStore;
import dev.fabric.hotload.core.JarInspection;
import dev.fabric.hotload.core.OperationJournal;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/** 绑定 Loader 0.20.1；不解除 frozen、不重跑整个启动阶段。 */
final class Loader0201Adapter implements OrdinaryModAdapter {
    private final CandidateStore store;
    private final OperationJournal journal;
    private boolean dirty;

    Loader0201Adapter(Path directory) throws Exception {
        store = new CandidateStore(directory.resolve("store"));
        journal = new OperationJournal(directory.resolve("operations.jsonl"));
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public String description() { return "loader-0.20.1-experimental"; }

    @Override
    public String load(JarInspection inspection) throws Exception {
        if (!Boolean.getBoolean("hotload.experimentalMods")) {
            throw new IllegalStateException("Experimental mod loading requires -Dhotload.experimentalMods=true");
        }
        if (dirty) { throw new IllegalStateException("DIRTY_RESTART_REQUIRED: failed ordinary mod activation"); }
        FabricLoader loader = FabricLoader.getInstance();
        String version = loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
        if (!version.equals("0.20.1")) {
            throw new IllegalStateException("Unsupported Loader version: " + version);
        }
        if (inspection.kind() != JarInspection.Kind.FABRIC || !inspection.blockers().isEmpty()) {
            throw new IllegalArgumentException("Candidate requires unsupported adaptation: " + inspection.blockers());
        }
        Path staged = store.stage(inspection);
        FabricLoaderImpl implementation = FabricLoaderImpl.INSTANCE;
        LoaderModMetadata metadata;
        try (JarFile jar = new JarFile(staged.toFile()); InputStream stream = jar.getInputStream(jar.getJarEntry("fabric.mod.json"))) {
            metadata = ModMetadataParser.parseMetadata(stream, staged.toString(), List.of(),
                    new VersionOverrides(), implementation.getDependencyOverrides(), loader.isDevelopmentEnvironment());
        }
        if (loader.isModLoaded(metadata.getId()) || metadata.getProvides().stream().anyMatch(loader::isModLoaded)) {
            throw new IllegalArgumentException("Duplicate mod id or provided alias: " + metadata.getId());
        }
        for (String key : metadata.getEntrypointKeys()) {
            for (var entry : metadata.getEntrypoints(key)) {
                if (!entry.getAdapter().equals("default")) {
                    throw new IllegalArgumentException("P0 supports only the default language adapter");
                }
            }
        }
        checkDependencies(metadata, loader);
        ClassLoader target = FabricLauncherBase.getLauncher().getTargetClassLoader();
        for (String name : inspection.classes()) {
            if (target.getResource(name.replace('.', '/') + ".class") != null) {
                throw new IllegalArgumentException("Class name already exists on game classpath: " + name);
            }
        }
        Method create = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class,
                LoaderModMetadata.class, boolean.class, Collection.class);
        create.setAccessible(true);
        ModCandidateImpl candidate = (ModCandidateImpl) create.invoke(null, List.of(staged), metadata, false, List.of());
        Method add = FabricLoaderImpl.class.getDeclaredMethod("addMod", ModCandidateImpl.class);
        add.setAccessible(true);
        EntrypointStorage entries = (EntrypointStorage) field(implementation, "entrypointStorage");
        Map<String, LanguageAdapter> adapters = adapters(implementation);
        journal.record("loadmod", metadata.getId(), inspection.sha256(), "COMMITTING_IRREVERSIBLE",
                "Knot classpath/entrypoint changes cannot be generically undone");
        try {
            FabricLauncherBase.getLauncher().addToClassPath(staged);
            add.invoke(implementation, candidate);
            ModContainerImpl container = (ModContainerImpl) loader.getModContainer(metadata.getId()).orElseThrow();
            for (String key : metadata.getEntrypointKeys()) {
                for (var entry : metadata.getEntrypoints(key)) { entries.add(container, key, entry, adapters); }
            }
            for (var entry : loader.getEntrypointContainers("main", ModInitializer.class)) {
                if (entry.getProvider().getMetadata().getId().equals(metadata.getId())) {
                    entry.getEntrypoint().onInitialize();
                }
            }
            for (var entry : loader.getEntrypointContainers("server", DedicatedServerModInitializer.class)) {
                if (entry.getProvider().getMetadata().getId().equals(metadata.getId())) {
                    entry.getEntrypoint().onInitializeServer();
                }
            }
            journal.record("loadmod", metadata.getId(), inspection.sha256(), "STARTED_UNVERIFIED",
                    "Entrypoints returned; load only, no disable/update/unload guarantee");
            return "MOD_STARTED_EXPERIMENTAL " + metadata.getId() + " sha256=" + inspection.sha256()
                    + " capabilities=load-only behavior=unverified";
        } catch (Exception | LinkageError failure) {
            dirty = true;
            journal.record("loadmod", metadata.getId(), inspection.sha256(), "DIRTY_RESTART_REQUIRED", failure.toString());
            throw new IllegalStateException("DIRTY_RESTART_REQUIRED: ordinary mod activation failed", failure);
        }
    }

    private static void checkDependencies(LoaderModMetadata metadata, FabricLoader loader) {
        for (ModDependency dependency : metadata.getDependencies()) {
            var existing = loader.getModContainer(dependency.getModId());
            boolean matches = existing.isPresent() && dependency.matches(existing.get().getMetadata().getVersion());
            if (dependency.getKind() == ModDependency.Kind.DEPENDS && !matches) {
                throw new IllegalArgumentException("Unsatisfied dependency: " + dependency);
            }
            if ((dependency.getKind() == ModDependency.Kind.BREAKS || dependency.getKind() == ModDependency.Kind.CONFLICTS)
                    && matches) { throw new IllegalArgumentException("Dependency conflict: " + dependency); }
        }
        for (var existing : loader.getAllMods()) {
            for (ModDependency dependency : existing.getMetadata().getDependencies()) {
                if ((dependency.getKind() == ModDependency.Kind.BREAKS || dependency.getKind() == ModDependency.Kind.CONFLICTS)
                        && (dependency.getModId().equals(metadata.getId()) || metadata.getProvides().contains(dependency.getModId()))
                        && dependency.matches(metadata.getVersion())) {
                    throw new IllegalArgumentException("Existing mod conflicts with candidate: " + existing.getMetadata().getId());
                }
            }
        }
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, LanguageAdapter> adapters(FabricLoaderImpl implementation) throws ReflectiveOperationException {
        return (Map<String, LanguageAdapter>) field(implementation, "adapterMap");
    }
}
