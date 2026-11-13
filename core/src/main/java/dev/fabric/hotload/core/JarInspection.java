package dev.fabric.hotload.core;

import java.nio.file.Path;
import java.util.List;

public record JarInspection(Path path, String sha256, Kind kind, String id,
                            String version, String entrypoint, String metadata,
                            List<String> classes, List<String> blockers) {
    public enum Kind { MODULE, FABRIC }

    public JarInspection {
        classes = List.copyOf(classes);
        blockers = List.copyOf(blockers);
    }
}
