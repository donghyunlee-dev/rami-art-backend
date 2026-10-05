package com.ramiart.admin.makeup.application;

import static com.ramiart.admin.makeup.application.MakeupModels.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MakeupRepository {
    record LockedCase(UUID id, UUID studentId, String status, LocalDate expiresOn, UUID originSessionId,
                      UUID classGroupId, UUID courseId, long version, UUID reservedSessionId) {}
    record ExpiredCase(UUID id, UUID updatedBy) {}
    record LockedSession(UUID id, UUID classGroupId, UUID courseId, String className, LocalDate date, java.time.OffsetDateTime startsAt,
                         java.time.OffsetDateTime endsAt, String status, long version, int capacity) {}
    CasePage list(String status, LocalDate from, LocalDate to, UUID studentId, int page, int size);
    Optional<Detail> detail(UUID id);
    List<Candidate> candidates(UUID caseId, LocalDate from, LocalDate to);
    Optional<LockedCase> findCase(UUID id);
    Optional<LockedCase> lockCase(UUID id);
    Optional<LockedSession> lockSession(UUID id);
    int reserve(UUID caseId, LockedCase makeupCase, LockedSession session, UUID actorId);
    int cancelReservation(UUID caseId, LockedCase makeupCase, UUID actorId);
    int waive(UUID caseId, long version, String reason, UUID actorId);
    int extend(UUID caseId, long version, LocalDate expiresOn, String reason, UUID actorId);
    List<ExpiredCase> expireAvailableCases(LocalDate today, int limit);
    boolean claim(String scope, UUID key, String hash);
    Optional<String> hash(String scope, UUID key);
    void complete(String scope, UUID key, UUID resourceId, int responseStatus);
}
