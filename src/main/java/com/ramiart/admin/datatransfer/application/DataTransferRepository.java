package com.ramiart.admin.datatransfer.application;

import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataTransferRepository {
    Optional<JobRecord> findJob(UUID id, UUID createdBy);
    List<RowRecord> findRows(UUID jobId, List<String> statuses, int afterRowNumber, int limit);
}
