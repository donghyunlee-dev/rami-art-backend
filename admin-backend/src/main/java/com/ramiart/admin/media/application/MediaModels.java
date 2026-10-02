package com.ramiart.admin.media.application;

import java.time.Instant;
import java.util.UUID;

public final class MediaModels {
    private MediaModels() {
    }

    public record ValidatedImage(byte[] bytes, String fileName, String mimeType, int width, int height,
            String sha256) {
    }

    public record MediaAsset(UUID id, String publicUrl, String fileName, String mimeType, long fileSize,
            int width, int height, String status, Instant expiresAt) {
    }

    public record StoredAsset(UUID id, String storageKey, String publicPath, String originalFileName,
            String sha256, String mimeType, long fileSize, int width, int height, Instant expiresAt) {
    }

    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {
    }
}

