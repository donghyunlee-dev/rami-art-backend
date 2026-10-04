package com.ramiart.admin.tuition.application;

import com.ramiart.admin.tuition.application.TuitionBillingService.PreviewCandidateState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TuitionBillingBatchRepository {
    record Claim(UUID batchId,String status,String requestHash,boolean created) {}
    record Result(UUID studentId,String status,UUID billingId,Long amount,String errorCode) {}
    record Batch(UUID batchId,String yearMonth,String status,int requested,int created,int existing,int failed,
            long createdAmount,List<Result> createdItems,List<Result> existingItems,List<Result> failedItems) {}
    Claim claim(String scope,UUID key,String hash,String yearMonth,int requested,UUID actorId);
    Optional<Claim> findClaim(String scope,UUID key);
    Result issueOne(UUID batchId,String yearMonth,UUID actorId,PreviewCandidateState expected,
            String requestId,String ipAddress,String userAgent);
    void recordFailure(UUID batchId,UUID studentId,String errorCode);
    void complete(UUID batchId);
    Optional<Batch> find(UUID batchId);
}
