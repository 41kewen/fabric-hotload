package dev.fabric.hotload.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class OperationJournal {
    private static final Gson GSON = new Gson();
    private final Path path;

    public OperationJournal(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        this.path = path;
    }

    public synchronized void record(String operation, String id, String hash, String state, String detail)
            throws IOException {
        String line = GSON.toJson(new Record(Instant.now().toString(), operation, id, hash, state, detail));
        Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private record Record(String time, String operation, String id, String sha256, String state, String detail) {
    }
}
