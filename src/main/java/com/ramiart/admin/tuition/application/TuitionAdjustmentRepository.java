package com.ramiart.admin.tuition.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TuitionAdjustmentRepository {
    record Billing(UUID id, UUID studentId, String studentName, String yearMonth, long baseAmount,
            long adjustmentAmount, long paymentAmount, long refundAmount, LocalDate dueDate,
            String paymentStatus, long version) {
        public long charge() { return baseAmount+adjustmentAmount; }
        public long balance() { return charge()-paymentAmount+refundAmount; }
        public long refundable() { return Math.max(-balance(),0); }
    }
    record Adjustment(UUID id, UUID billingId, String type, long signedAmount, String reason,
            String status, UUID createdBy, OffsetDateTime createdAt, UUID cancelledBy,
            OffsetDateTime cancelledAt, String cancelReason, long version) {}
    record Refund(UUID id, UUID billingId, UUID paymentId, LocalDate refundedOn, long amount,
            String method, String reason, String status, UUID entryId, UUID createdBy,
            OffsetDateTime createdAt, UUID cancelledBy, OffsetDateTime cancelledAt,
            String cancelReason, long version) {}
    record Claim(boolean created, String requestHash, String state, UUID resourceId) {}

    Optional<Billing> findBilling(UUID id);
    Optional<Billing> lockBilling(UUID id);
    List<Adjustment> adjustments(UUID billingId);
    List<Refund> refunds(UUID billingId);
    Optional<Adjustment> lockAdjustment(UUID id);
    Optional<Refund> lockRefund(UUID id);
    Optional<Long> paymentVersion(UUID paymentId,UUID billingId);
    long paymentRefundable(UUID paymentId);
    void insertAdjustment(UUID id,UUID billingId,String type,long amount,String reason,UUID actor,long expectedVersion,
            long nextAdjustmentAmount,long nextPaymentAmount,long nextRefundAmount,String nextStatus);
    void cancelAdjustment(UUID id,UUID actor,String reason,long expectedTargetVersion,long expectedBillingVersion,
            long nextAdjustmentAmount,long nextPaymentAmount,long nextRefundAmount,String nextStatus);
    void insertRefund(UUID id,UUID entryId,UUID billingId,UUID paymentId,LocalDate date,long amount,String method,
            String reason,UUID actor,long expectedBillingVersion,long nextRefundAmount,long nextPaymentAmount,
            String nextStatus);
    void cancelRefund(UUID id,UUID actor,String reason,long expectedTargetVersion,long expectedBillingVersion,
            long nextRefundAmount,long nextPaymentAmount,String nextStatus);
    Claim claim(String scope,UUID key,String requestHash);
    void complete(String scope,UUID key,UUID resourceId,int status);
}
