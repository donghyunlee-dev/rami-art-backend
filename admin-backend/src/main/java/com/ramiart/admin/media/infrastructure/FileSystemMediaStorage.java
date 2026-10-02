package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class FileSystemMediaStorage implements MediaStorage {
    private final Path root;

    public FileSystemMediaStorage(@Value("${admin.media.storage-root:build/media-storage}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, byte[] content) {
        Path target = resolve(key);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            quietlyDelete(temporary);
            throw new MediaException("MEDIA_STORAGE_FAILED", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new MediaException("MEDIA_STORAGE_FAILED", exception);
        }
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new MediaException("MEDIA_STORAGE_FAILED");
        return resolved;
    }

    private static void quietlyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A cleanup job can remove a leftover quarantine file.
        }
    }
}

