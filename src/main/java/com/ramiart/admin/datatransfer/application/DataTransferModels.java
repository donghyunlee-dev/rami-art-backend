package com.ramiart.admin.datatransfer.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DataTransferModels {
    private DataTransferModels() {}
    public record Job(UUID id, String direction, String domain, String status, String templateVersion,
            int totalCount, int validCount, int invalidCount, int duplicateCount, int confirmedCount, int failedCount,
            int progressPercent, long version, OffsetDateTime expiresAt, boolean downloadable) {}
    public record FieldError(String field, String code) {}
    public record DuplicateCandidate(UUID id, String maskedSummary) {}
    public record Row(UUID id, int rowNumber, String status, String maskedSummary, List<FieldError> fieldErrors,
            DuplicateCandidate duplicateCandidate, UUID resultTargetId, String errorCode) {}
    public record RowPage(List<Row> items, int size, String nextCursor, boolean hasNext) {}
    public record JobRecord(UUID id, String direction, String domain, String status, String templateVersion,
            int totalCount, int validCount, int invalidCount, int duplicateCount, int confirmedCount, int failedCount,
            long version, OffsetDateTime expiresAt) {}
    public record RowRecord(UUID id, int rowNumber, String status, String maskedSummary, String fieldErrorsJson,
            UUID duplicateTargetId, UUID resultTargetId, String errorCode) {}
    public record ImportedRow(UUID id, int rowNumber, String status, byte[] payloadCiphertext, String dedupHash,
            String maskedSummary, String fieldErrorsJson, UUID duplicateTargetId, String errorCode) {}
    public record ConfirmRow(UUID id, int rowNumber, String status, byte[] payloadCiphertext) {}
    public record ConfirmJob(UUID id, String domain, int version, int validCount, int confirmedCount, int failedCount,
            int invalidCount, int duplicateCount, String status) {}
    public record ConfirmResult(UUID rowId, String status, UUID resultTargetId, String errorCode) {}
    public record ConfirmResponse(Job job, List<ConfirmResult> results) {}
}
