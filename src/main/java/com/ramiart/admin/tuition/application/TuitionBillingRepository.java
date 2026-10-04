package com.ramiart.admin.tuition.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.YearMonth;
import java.time.OffsetDateTime;

public interface TuitionBillingRepository {
    record BillingRow(UUID billingId, UUID studentId, String studentName, String yearMonth,
            String policyLabel, String assignmentLabel, long baseAmount, long adjustmentAmount,
            long paidAmount, long refundedAmount, LocalDate dueDate, String paymentStatus,
            java.time.OffsetDateTime issuedAt, long version) {
        public long chargeAmount() { return baseAmount + adjustmentAmount; }
        public long netPaidAmount() { return paidAmount - refundedAmount; }
        public long balance() { return chargeAmount() - netPaidAmount(); }
    }

    record BillingDetail(BillingRow row, String studentStatus, UUID policyItemId,
            long monthlyAmount, int defaultDueDay, UUID assignmentId, Long overrideAmount,
            String overrideReason, long assignmentVersion, UUID issuerId, String issuerName,
            UUID batchId) {}
    record PreviewStudent(UUID studentId, String studentName, int assignmentCount, UUID assignmentId,
            long assignmentVersion, UUID policyItemId, String policyLabel, String policyStatus, Long amount, Integer defaultDueDay,
            boolean alreadyIssued) {}
    record PreviewState(String requestHash, byte[] encryptedResponse, OffsetDateTime expiresAt) {}

    List<BillingRow> list(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today, int limit, long offset);
    Map<String, Object> summarize(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today);
    long count(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today);
    Optional<BillingDetail> detail(UUID billingId);
    List<PreviewStudent> preview(YearMonth yearMonth);
    void savePreview(String scope, UUID token, String requestHash, byte[] encryptedResponse, OffsetDateTime expiresAt);
    Optional<PreviewState> findPreview(String scope, UUID token, OffsetDateTime now);
}
