package dev.fabric.hotload.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JarInspectorTest {
    @TempDir
    Path directory;

    @Test
    void rejectsNewerJavaBytecodeBeforeActivation() throws Exception {
        candidate(0xCAFEBABE, Runtime.version().feature() + 45);
        var result = new JarInspector(directory).inspect("module.jar");
        assertTrue(result.blockers().stream().anyMatch(reason -> reason.startsWith("Class requires Java ")));
    }

    @Test
    void rejectsInvalidClassHeader() throws Exception {
        candidate(0, 61);
        assertThrows(IllegalArgumentException.class, () -> new JarInspector(directory).inspect("module.jar"));
    }

    private void candidate(int magic, int major) throws Exception {
        try (var jar = new JarOutputStream(java.nio.file.Files.newOutputStream(directory.resolve("module.jar")))) {
            jar.putNextEntry(new JarEntry("hotload-module.properties"));
            jar.write("id=test_module\nversion=1.0\nentrypoint=sample.Main\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("sample/Main.class"));
            var header = new DataOutputStream(jar);
            header.writeInt(magic);
            header.writeShort(0);
            header.writeShort(major);
            jar.closeEntry();
        }
    }
}
