package com.ramiart.admin.datatransfer.application;

import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface DataTransferRepository {
    Optional<JobRecord> findJob(UUID id, UUID createdBy);
    List<RowRecord> findRows(UUID jobId, List<String> statuses, int afterRowNumber, int limit);
    boolean hasActiveImport(String domain, String sha256);
    void createImport(UUID id, String domain, String version, String fileName, String storageKey,
            String sha256, long fileSize, UUID actor);
    void insertRows(UUID jobId, Collection<ImportedRow> rows);
    void markImportReady(UUID jobId, int total, int valid, int invalid, int duplicates);
}
