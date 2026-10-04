package com.ramiart.admin.tuition.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TuitionReceiptRepository {
    record Source(UUID paymentId,UUID billingId,String yearMonth,String studentName,LocalDate paidOn,long amount,String method,
            String paymentStatus,long paymentVersion,long billingVersion,long refundAmount,String studioName) {}
    record Receipt(UUID id,UUID paymentId,String receiptNumber,int currentVersion,String status) {}
    record Version(UUID id,UUID receiptId,int version,String status,Map<String,Object> snapshot,long refundAmount,
            String storageKey,String sha256,Long fileSize,String issueReason,UUID issuedBy,Instant issuedAt,Instant completedAt) {}
    record Claim(boolean created,String requestHash,String state,UUID resourceId) {}
    Optional<Source> lockSource(UUID paymentId);
    Optional<Source> source(UUID paymentId);
    Optional<Receipt> byPayment(UUID paymentId);
    Optional<Receipt> lockReceipt(UUID receiptId);
    List<Version> versions(UUID receiptId);
    Optional<Version> version(UUID receiptId,int version);
    String nextReceiptNumber(String yearMonth);
    void create(UUID receiptId,UUID paymentId,String receiptNumber,UUID actor,UUID versionId,int version,
            Map<String,Object> snapshot,long refundAmount,String reason);
    void beginReissue(UUID receiptId,int expectedVersion,int nextVersion,UUID versionId,UUID actor,
            Map<String,Object> snapshot,long refundAmount,String reason);
    void markReady(UUID versionId,String storageKey,String sha256,long size);
    void markFailed(UUID versionId);
    Claim claim(String scope,UUID key,String hash);
    void complete(String scope,UUID key,UUID resourceId,int status);
    void voidForPayment(UUID paymentId);
}
