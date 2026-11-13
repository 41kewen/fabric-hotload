package dev.fabric.hotload.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleManagerTest {
    @TempDir Path directory;

    private ModuleManager manager() throws Exception {
        Path candidates = Files.createDirectories(directory.resolve("candidates"));
        Files.copy(Path.of(System.getProperty("demo.v1")), candidates.resolve("v1.jar"));
        Files.copy(Path.of(System.getProperty("demo.v2")), candidates.resolve("v2.jar"));
        return new ModuleManager(directory, getClass().getClassLoader(), message -> { });
    }

    @Test
    void switchesActualImplementationAndStopsDispatch() throws Exception {
        try (ModuleManager manager = manager()) {
            manager.load("v1.jar");
            manager.tick();
            assertEquals("demo-v1 ticks=1", manager.execute("demo_counter", "hello", List.of()));
            manager.reload("v2.jar");
            assertEquals("demo-v2 ticks=0", manager.execute("demo_counter", "hello", List.of()));
            manager.tick();
            assertEquals("demo-v2 ticks=1", manager.execute("demo_counter", "hello", List.of()));
            manager.disable("demo_counter");
            manager.tick();
            assertEquals(0, manager.resourceCount());
            assertThrows(IllegalArgumentException.class,
                    () -> manager.execute("demo_counter", "hello", List.of()));
        }
    }

    @Test
    void hundredCyclesDoNotAccumulateManagedResources() throws Exception {
        try (ModuleManager manager = manager()) {
            for (int iteration = 0; iteration < 100; iteration++) {
                manager.load("v1.jar");
                manager.tick();
                assertEquals("demo-v1 ticks=1", manager.execute("demo_counter", "hello", List.of()));
                manager.reload("v2.jar");
                manager.tick();
                assertEquals("demo-v2 ticks=1", manager.execute("demo_counter", "hello", List.of()));
                assertEquals(2, manager.resourceCount());
                manager.disable("demo_counter");
                assertEquals(0, manager.resourceCount());
            }
            assertFalse(manager.dirty());
        }
    }

    @Test
    void invalidCandidateDoesNotStopCurrentModule() throws Exception {
        try (ModuleManager manager = manager()) {
            manager.load("v1.jar");
            assertThrows(IllegalArgumentException.class, () -> manager.reload("../outside.jar"));
            assertThrows(IllegalArgumentException.class, () -> manager.load("v1.jar"));
            assertEquals("demo-v1 ticks=0", manager.execute("demo_counter", "hello", List.of()));
        }
    }

    @Test
    void detectsCandidateChangesBeforeStaging() throws Exception {
        Path candidates = Files.createDirectories(directory.resolve("candidates"));
        Files.copy(Path.of(System.getProperty("demo.v1")), candidates.resolve("module.jar"));
        JarInspection inspection = new JarInspector(candidates).inspect("module.jar");
        Files.writeString(candidates.resolve("module.jar"), "replaced");
        assertThrows(java.io.IOException.class,
                () -> new CandidateStore(directory.resolve("store")).stage(inspection));
    }

    @Test
    void rejectsTraversalInsideJar() throws Exception {
        Path candidates = Files.createDirectories(directory.resolve("candidates"));
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(candidates.resolve("bad.jar")))) {
            stream.putNextEntry(new JarEntry("../outside.txt"));
            stream.write(1);
            stream.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> new JarInspector(candidates).inspect("bad.jar"));
    }

    @Test
    void mutationsFromAnotherThreadAreRejected() throws Exception {
        try (ModuleManager manager = manager()) {
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread thread = new Thread(() -> {
                try { manager.load("v1.jar"); } catch (Exception exception) { failure.set(exception); }
            });
            thread.start();
            thread.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(0, manager.resourceCount());
        }
    }

    private void failureFixture(String start, String stop) throws Exception {
        Path output = Files.createDirectories(directory.resolve("compiled"));
        Path closed = directory.resolve("owned-resource-closed.txt");
        String literal = closed.toString().replace('\\', '/');
        String source = "package test.module; public final class Failure implements dev.fabric.hotload.api.HotModule {"
                + "public void start(dev.fabric.hotload.api.ModuleContext c) throws Exception {"
                + "c.command(\"hello\", args -> \"active\");"
                + "c.own(() -> java.nio.file.Files.writeString(java.nio.file.Path.of(\"" + literal + "\"), \"closed\"));"
                + start + "} public void stop() throws Exception {" + stop + "}}";
        Path input = output.resolve("Failure.java");
        Files.writeString(input, source);
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require the configured JDK, not a JRE");
        Path api = Path.of(dev.fabric.hotload.api.HotModule.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-classpath", api.toString(),
                "-d", output.toString(), input.toString()));
        Path candidates = Files.createDirectories(directory.resolve("candidates"));
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(candidates.resolve("failure.jar")))) {
            stream.putNextEntry(new JarEntry("test/module/Failure.class"));
            stream.write(Files.readAllBytes(output.resolve("test/module/Failure.class")));
            stream.closeEntry();
            stream.putNextEntry(new JarEntry("hotload-module.properties"));
            stream.write("id=failure_fixture\nversion=1.0.0\nentrypoint=test.module.Failure\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.closeEntry();
        }
    }

    @Test
    void initializationFailureCleansOwnedResourcesButRequiresRestart() throws Exception {
        failureFixture("throw new IllegalStateException(\"initializer failed\");", "");
        try (ModuleManager manager = manager()) {
            assertThrows(IllegalStateException.class, () -> manager.load("failure.jar"));
            assertEquals("closed", Files.readString(directory.resolve("owned-resource-closed.txt")));
            assertEquals(0, manager.resourceCount());
            assertTrue(manager.dirty());
            assertThrows(IllegalStateException.class, () -> manager.load("v1.jar"));
            assertTrue(Files.readString(directory.resolve("operations.jsonl")).contains("DIRTY_RESTART_REQUIRED"));
        }
    }

    @Test
    void stopFailureStillClosesOwnedResourcesAndBlocksFurtherLoading() throws Exception {
        failureFixture("", "throw new IllegalStateException(\"stop failed\");");
        try (ModuleManager manager = manager()) {
            manager.load("failure.jar");
            assertThrows(Exception.class, () -> manager.disable("failure_fixture"));
            assertEquals("closed", Files.readString(directory.resolve("owned-resource-closed.txt")));
            assertEquals(0, manager.resourceCount());
            assertTrue(manager.dirty());
            assertThrows(IllegalStateException.class, () -> manager.load("v1.jar"));
        }
    }
}
