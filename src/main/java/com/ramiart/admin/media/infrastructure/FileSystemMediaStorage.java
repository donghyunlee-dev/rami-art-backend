package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.storage.supabase-url:}' == '' or '${app.storage.service-key:}' == ''")
public final class FileSystemMediaStorage implements MediaStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemMediaStorage.class);
    private final Path root;

    public FileSystemMediaStorage(@Value("${admin.media.storage-root:build/media-storage}") String root,
            @Value("${admin.media.require-durable-storage:false}") boolean requireDurableStorage) {
        if (requireDurableStorage) {
            throw new IllegalStateException("Persistent media storage is required but Supabase storage is not configured");
        }
        this.root = Path.of(root).toAbsolutePath().normalize();
        LOGGER.info("media_storage_backend=filesystem");
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
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

    @Override
    public String publicUrl(String key) {
        String prefix = "public-media/";
        if (!key.startsWith(prefix)) throw new MediaException("MEDIA_STORAGE_FAILED");
        return "/media/" + key.substring(prefix.length());
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

