package com.ramiart.admin.media.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.media.application.MediaModels.MediaAsset;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import com.ramiart.admin.media.application.MediaModels.StoredAsset;
import com.ramiart.admin.media.application.MediaModels.ValidatedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class MediaService {
    private static final DateTimeFormatter PATH_MONTH = DateTimeFormatter.ofPattern("uuuu/MM").withZone(ZoneOffset.UTC);
    private final MediaRepository repository;
    private final MediaStorage storage;
    private final ImageValidator validator;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String publicBaseUrl;

    public MediaService(MediaRepository repository, MediaStorage storage, ImageValidator validator,
            AuditRecorder audit, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${admin.media.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.repository = repository;
        this.storage = storage;
        this.validator = validator;
        this.audit = audit;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public MediaAsset upload(byte[] bytes, String fileName, String contentType, UUID actorId,
            UUID idempotencyKey, RequestMetadata metadata) {
        ValidatedImage image = validator.validate(bytes, fileName, contentType);
        String scope = actorId + ":POST:/admin/media-assets";
        String requestHash = hash("POST:/admin/media-assets:" + image.sha256());
        UUID assetId = UUID.randomUUID();
        String suffix = extension(image.mimeType());
        String storageKey = "public-media/" + PATH_MONTH.format(clock.instant()) + "/" + assetId + suffix;
        String publicPath = "/media/" + PATH_MONTH.format(clock.instant()) + "/" + assetId + suffix;
        // PostgreSQL timestamps retain microseconds; first and replay responses must match.
        Instant expiresAt = clock.instant().plusSeconds(7 * 24 * 60 * 60L).truncatedTo(ChronoUnit.MICROS);
        StoredAsset candidate = new StoredAsset(assetId, storageKey, publicPath, image.fileName(), image.sha256(),
                image.mimeType(), image.bytes().length, image.width(), image.height(), expiresAt);
        boolean[] objectWritten = {false};
        try {
            StoredAsset stored = transactions.execute(status -> {
                MediaRepository.IdempotencyClaim claim = repository.claim(scope, idempotencyKey, requestHash);
                if (!claim.claimed()) return repository.find(claim.resourceId())
                        .orElseThrow(() -> new MediaException("MEDIA_ASSET_NOT_FOUND"));
                storage.store(storageKey, image.bytes());
                objectWritten[0] = true;
                repository.insert(candidate, actorId);
                recordAudit(actorId, metadata, "MEDIA_ASSET_UPLOADED", assetId,
                        Map.of("mimeType", image.mimeType(), "fileSize", image.bytes().length,
                                "width", image.width(), "height", image.height()));
                repository.complete(scope, idempotencyKey, assetId, 201);
                return candidate;
            });
            if (stored == null) throw new MediaException("MEDIA_STORAGE_FAILED");
            return response(stored);
        } catch (MediaException exception) {
            if (objectWritten[0]) compensate(storageKey);
            throw exception;
        } catch (RuntimeException exception) {
            if (objectWritten[0]) compensate(storageKey);
            throw new MediaException("MEDIA_STORAGE_FAILED", exception);
        }
    }

    public void delete(UUID assetId, UUID actorId, UUID idempotencyKey, RequestMetadata metadata) {
        String scope = actorId + ":DELETE:/admin/media-assets/{assetId}";
        String requestHash = hash("DELETE:/admin/media-assets/" + assetId);
        transactions.executeWithoutResult(status -> {
            MediaRepository.IdempotencyClaim claim = repository.claim(scope, idempotencyKey, requestHash);
            if (!claim.claimed()) return;
            if (repository.find(assetId).isEmpty()) throw new MediaException("MEDIA_ASSET_NOT_FOUND");
            MediaRepository.DeletionCandidate candidate = repository.lockUnreferenced(assetId)
                    .orElseThrow(() -> new MediaException("MEDIA_ASSET_IN_USE"));
            storage.delete(candidate.storageKey());
            if (repository.deleteLocked(assetId) != 1) throw new MediaException("MEDIA_ASSET_IN_USE");
            recordAudit(actorId, metadata, "MEDIA_ASSET_DELETED", assetId, Map.of());
            repository.complete(scope, idempotencyKey, assetId, 204);
        });
    }

    public int cleanupExpired(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        int removed = 0;
        for (UUID id : repository.findExpiredCandidateIds(clock.instant(), limit)) {
            try {
                Boolean deleted = transactions.execute(status -> {
                    MediaRepository.DeletionCandidate candidate = repository.lockUnreferenced(id).orElse(null);
                    if (candidate == null) return false;
                    storage.delete(candidate.storageKey());
                    return repository.deleteLocked(id) == 1;
                });
                if (Boolean.TRUE.equals(deleted)) removed++;
            } catch (MediaException ignored) {
                // Keep the row so the next cleanup run can retry object deletion.
            }
        }
        return removed;
    }

    private MediaAsset response(StoredAsset asset) {
        return new MediaAsset(asset.id(), publicBaseUrl + asset.publicPath(), asset.originalFileName(),
                asset.mimeType(), asset.fileSize(), asset.width(), asset.height(), "READY", asset.expiresAt());
    }

    private void recordAudit(UUID actorId, RequestMetadata metadata, String action, UUID targetId,
            Map<String, Object> details) {
        audit.record(new Event(clock.instant(), metadata.requestId(), "MGT-MEDIA-UPLOAD", "OPERATION",
                "ADMIN", actorId, null, action, "MEDIA_ASSET", targetId, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
    }

    private void compensate(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException ignored) {
            // The cleanup process removes orphaned objects; never reveal an internal key in the error.
        }
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new MediaException("MEDIA_TYPE_NOT_SUPPORTED");
        };
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
