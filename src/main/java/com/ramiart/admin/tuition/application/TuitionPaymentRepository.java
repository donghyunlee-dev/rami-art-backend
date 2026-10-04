package com.ramiart.admin.tuition.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TuitionPaymentRepository {
    record Payment(UUID id, UUID billingId, LocalDate paidOn, long amount, String method, String memo,
            String status, UUID createdBy, String createdByName, java.time.OffsetDateTime createdAt,
            UUID cancelledBy, String cancelledByName, java.time.OffsetDateTime cancelledAt,
            String cancelReason, long version, long refundedAmount, UUID entryId) {}
    record Billing(UUID id, UUID studentId, String studentName, String yearMonth, long billedAmount,
            long adjustmentAmount, long paidAmount, long refundedAmount, LocalDate dueDate,
            String paymentStatus, long version) {
        public long balance() { return billedAmount + adjustmentAmount - paidAmount + refundedAmount; }
    }
    record Claim(boolean created, String status, UUID resourceId, String requestHash) {}

    Optional<Billing> lockBilling(UUID billingId);
    Optional<Billing> findBilling(UUID billingId);
    Optional<Payment> lockPayment(UUID paymentId);
    List<Payment> list(UUID billingId, java.time.OffsetDateTime beforeCreatedAt, UUID beforeId, int size);
    Optional<Payment> find(UUID paymentId);
    void insert(UUID billingId, UUID paymentId, UUID entryId, UUID actorId,
            LocalDate paidOn, long amount, String method, String memo, long nextPaidAmount,
            String nextPaymentStatus, long expectedBillingVersion);
    void cancel(UUID paymentId, UUID entryId, UUID actorId, String reason,
            long nextPaidAmount, String nextPaymentStatus, long paymentVersion,
            long billingVersion);
    Claim claim(String scope, UUID key, String requestHash);
    void complete(String scope, UUID key, UUID paymentId, int responseStatus);
    Optional<UUID> findEntry(UUID paymentId);
}
