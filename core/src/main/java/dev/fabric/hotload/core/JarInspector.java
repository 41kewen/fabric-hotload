package dev.fabric.hotload.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class JarInspector {
    private static final long MAX_JAR = 128L * 1024 * 1024;
    private static final int MAX_METADATA = 65536;
    private static final List<String> PROTECTED = List.of("java/", "javax/", "net/minecraft/",
            "net/fabricmc/", "com/mojang/", "dev/fabric/hotload/api/", "dev/fabric/hotload/core/",
            "dev/fabric/hotload/host/", "dev/fabric/hotload/agent/");
    private final Path directory;

    public JarInspector(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
    }

    public JarInspection inspect(String filename) throws IOException {
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*\\.jar")) {
            throw new IllegalArgumentException("Only a JAR filename inside hotmods/candidates is allowed");
        }
        Path path = directory.resolve(filename).toRealPath();
        if (!path.startsWith(directory) || !Files.isRegularFile(path) || Files.size(path) > MAX_JAR) {
            throw new IllegalArgumentException("Candidate escapes directory or exceeds 128 MiB");
        }
        List<String> classes = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            var entries = jar.entries();
            int count = 0;
            long expanded = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 10000 || name.startsWith("/") || name.contains("\\")
                        || List.of(name.split("/")).contains("..")) {
                    throw new IllegalArgumentException("Unsafe JAR entry: " + name);
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_JAR - expanded) {
                    throw new IllegalArgumentException("Expanded JAR exceeds limit");
                }
                expanded += entry.getSize();
                if (name.endsWith(".class")) {
                    if (name.startsWith("META-INF/versions/")) {
                        blockers.add("Multi-release class definitions are not supported in P0");
                    }
                    if (PROTECTED.stream().anyMatch(name::startsWith)) {
                        blockers.add("Candidate bundles protected class: " + name);
                    }
                    classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
            boolean module = jar.getJarEntry("hotload-module.properties") != null;
            boolean fabric = jar.getJarEntry("fabric.mod.json") != null;
            if (module == fabric) {
                throw new IllegalArgumentException("Exactly one supported module descriptor is required");
            }
            if (module) {
                String raw = read(jar, "hotload-module.properties");
                Properties properties = new Properties();
                properties.load(new java.io.StringReader(raw));
                String id = required(properties.getProperty("id"), "id");
                validateId(id);
                String version = required(properties.getProperty("version"), "version");
                String main = required(properties.getProperty("entrypoint"), "entrypoint");
                if (!classes.contains(main)) {
                    blockers.add("Module entrypoint class is absent");
                }
                return new JarInspection(path, sha256(path), JarInspection.Kind.MODULE, id,
                        version, main, raw, classes, blockers);
            }
            String raw = read(jar, "fabric.mod.json");
            JsonObject metadata = JsonParser.parseString(raw).getAsJsonObject();
            String id = stringField(metadata, "id");
            validateId(id);
            String version = stringField(metadata, "version");
            if (!metadata.has("schemaVersion") || metadata.get("schemaVersion").getAsInt() != 1) {
                blockers.add("Only Fabric metadata schema 1 is supported");
            }
            if (metadata.has("environment") && metadata.get("environment").getAsString().equals("client")) {
                blockers.add("Client-only mod cannot load into a dedicated server");
            }
            for (String key : List.of("mixins", "accessWidener", "classTweaker", "jars", "languageAdapters")) {
                if (metadata.has(key) && !metadata.get(key).isJsonNull()
                        && !metadata.get(key).toString().equals("[]")
                        && !metadata.get(key).toString().equals("{}")) {
                    blockers.add("P0 does not support runtime " + key);
                }
            }
            if (metadata.has("entrypoints")) {
                for (String key : metadata.getAsJsonObject("entrypoints").keySet()) {
                    if (!List.of("main", "server").contains(key)) {
                        blockers.add("Unadapted lifecycle entrypoint: " + key);
                    }
                }
            }
            return new JarInspection(path, sha256(path), JarInspection.Kind.FABRIC, id,
                    version, "", raw, classes, blockers);
        }
    }

    private static String read(JarFile jar, String name) throws IOException {
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name))) {
            byte[] bytes = stream.readNBytes(MAX_METADATA + 1);
            if (bytes.length > MAX_METADATA) {
                throw new IllegalArgumentException("Descriptor exceeds 64 KiB");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid descriptor field: " + name);
        }
        return value;
    }

    private static String stringField(JsonObject metadata, String name) {
        if (!metadata.has(name) || !metadata.get(name).isJsonPrimitive()
                || !metadata.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("Missing or non-string descriptor field: " + name);
        }
        return required(metadata.get(name).getAsString(), name);
    }

    private static void validateId(String id) {
        if (!id.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Invalid module/mod id: " + id);
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", exception);
        }
    }
}
