package com.ramiart.admin.makeup.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MakeupModels {
    private MakeupModels() {}
    public record CaseRow(UUID id, UUID studentId, String studentName, UUID originSessionId, LocalDate originDate,
                          UUID classGroupId, String className, String status, LocalDate expiresOn,
                          UUID reservedSessionId, int attemptCount, long version, OffsetDateTime createdAt) {}
    public record CasePage(List<CaseRow> items, int page, int size, long total) {}
    public record Detail(CaseRow makeupCase, UUID originAttendanceId, String originStatus, String originReason,
                         UUID completedAttendanceId, List<Attempt> history) {}
    public record Attempt(UUID sessionId, LocalDate date, String className, String status,
                          UUID attendanceId, String attendanceStatus, OffsetDateTime updatedAt) {}
    public record Candidate(UUID sessionId, OffsetDateTime startsAt, UUID classGroupId, String className,
                            boolean compatible, int regularCount, int makeupReservedCount, int capacity,
                            int availableSeats, boolean studentConflict, boolean selectable, List<String> reasons,
                            long sessionVersion) {}
    public record CandidatePage(List<Candidate> items, LocalDate from, LocalDate to) {}
    public record Reservation(UUID sessionId, long caseVersion, long sessionVersion) {}
    public record ReservationResult(Detail detail, boolean created) {}
    public record VersionedReason(long version, String reason) {}
    public record Extension(LocalDate newExpiresOn, String reason, String reauthToken, long version) {}
    public record Updated(CaseRow makeupCase) {}
}
