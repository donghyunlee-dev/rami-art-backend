package com.ramiart.admin.dashboard.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface DashboardRepository {
    record AttendanceMetrics(int scheduledCount, int completedCount) {}
    record TuitionMetrics(int overdueStudentCount, int overdueBillingCount, long outstandingAmount) {}
    record InquiryMetrics(int unreadCount, int unansweredCount, int staleCount) {}
    record Birthday(UUID studentId, String studentName, String birthdayMonthDay, int daysUntil) {}
    record AuditActivity(UUID auditLogId, OffsetDateTime occurredAt, String taskId, String action,
                         String targetType, UUID targetId, String targetDisplay, String actorDisplay) {}

    AttendanceMetrics attendance(LocalDate date);
    TuitionMetrics overdueTuition(LocalDate today);
    InquiryMetrics inquiries(LocalDate today, ZoneId studioZone);
    List<Birthday> birthdays(LocalDate today, LocalDate rangeEnd, int limit);
    List<AuditActivity> recentActivities(int limit);
}
