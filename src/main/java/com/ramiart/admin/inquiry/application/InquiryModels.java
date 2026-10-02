package com.ramiart.admin.inquiry.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InquiryModels {
    private InquiryModels() {}

    public record PublicSubmission(String name, String phone, UUID interestedCourseId, String message,
            Boolean privacyConsent, String consentPolicyVersion, String company) {}
    public record Accepted(boolean accepted) {}
    public record CourseView(UUID courseId, String name, boolean active) {}
    public record InquiryQuery(String keyword, List<UUID> courseIds, List<String> statuses,
            Instant fromInclusive, Instant toExclusive, String readState, int page, int size) {}
    public record CourseOption(UUID courseId, String name, boolean active) {}
    public record InquirySummary(UUID inquiryId, String name, String maskedPhone, CourseView interestedCourse,
            String status, boolean read, Instant receivedAt, Instant lastActivityAt, boolean stale) {}
    public record Summary(long unreadCount, long staleCount) {}
    public record InquiryPage(int page, int size, long totalElements, int totalPages,
            Summary summary, List<InquirySummary> items) {}
    public record AdminView(UUID adminUserId, String displayName) {}
    public record ConsentView(String policyVersion, Instant consentedAt) {}
    public record NotificationView(String status, Instant attemptedAt) {}
    public record ActivityView(UUID activityId, String fromStatus, String toStatus, String note,
            AdminView createdBy, Instant createdAt) {}
    public record InquiryDetail(UUID inquiryId, long version, String name, String phone, String displayPhone,
            CourseView interestedCourse, String message, String status, boolean read, Instant readAt,
            AdminView readBy, ConsentView consent, NotificationView notification,
            List<String> allowedTransitions, List<ActivityView> activities) {}
    public record ReadReceiptWrite(long inquiryVersion) {}
    public record ReadReceipt(long version, boolean read, Instant readAt, AdminView readBy) {}
    public record ActivityWrite(String toStatus, String note, long inquiryVersion) {}
    public record InquiryState(UUID inquiryId, long version, String status, boolean read,
            Instant lastActivityAt, List<String> allowedTransitions) {}
    public record ActivityCreated(InquiryState inquiry, ActivityView activity) {}
}
