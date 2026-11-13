package dev.fabric.hotload.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CandidateStore {
    private final Path directory;

    public CandidateStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
    }

    public Path stage(JarInspection inspection) throws IOException {
        Path destination = directory.resolve(inspection.sha256() + ".jar");
        if (Files.exists(destination)) {
            if (!JarInspector.sha256(destination).equals(inspection.sha256())) {
                throw new IOException("Stored candidate hash mismatch");
            }
            return destination;
        }
        Path temporary = Files.createTempFile(directory, "candidate-", ".tmp");
        try {
            Files.copy(inspection.path(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!JarInspector.sha256(temporary).equals(inspection.sha256())) {
                throw new IOException("Candidate changed after inspection");
            }
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
