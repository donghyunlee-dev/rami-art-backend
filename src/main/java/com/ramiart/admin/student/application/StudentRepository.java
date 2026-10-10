package com.ramiart.admin.student.application;

import com.ramiart.admin.student.application.StudentModels.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentRepository {
    record StudentRecord(UUID id, String name, String school, LocalDate birthday, String status,
            LocalDate joinedAt, UUID createdBy, long version, Instant lastChangedAt, String lastReason) {}
    record GuardianRecord(UUID id, UUID studentId, String name, String relationship, String relationshipDetail,
            byte[] phoneCiphertext, byte[] emailCiphertext, String emailDomain, String preferredChannel,
            boolean primaryContact, int displayOrder) {}
    record NoteRecord(UUID id, UUID studentId, byte[] contentCiphertext, UUID createdBy, String createdByName,
            Instant createdAt, Instant updatedAt, long version) {}
    record Claim(boolean claimed, UUID resourceId) {}

    StudentPage findStudents(String keyword, String className, List<String> statuses, LocalDate joinedFrom, LocalDate joinedTo,
            String birthdayFrom, String birthdayTo, int page, int size, String sort);
    Optional<StudentRecord> findStudent(UUID id);
    int lessonCount(UUID studentId);
    List<GuardianRecord> findGuardians(UUID studentId);
    boolean guardianReferenced(UUID guardianId);
    List<StudentSummary> findDuplicateCandidates(String nameSearch, LocalDate birthday, List<String> phoneHashes);
    void insertStudent(UUID id, StudentCreate command, String nameSearch, UUID actor);
    void insertGuardian(UUID studentId, GuardianWrite guardian, UUID id, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, byte[] emailCiphertext, String emailHash, String emailDomain);
    int updateStudent(UUID id, StudentUpdate command, String nameSearch, UUID actor);
    void replaceGuardians(UUID studentId, List<StoredGuardian> guardians);
    int updateStatus(UUID studentId, long version, String toStatus, UUID actor);
    UUID insertStatusHistory(UUID studentId, String from, String to, LocalDate date, String reason,
            UUID actor, long newVersion);
    Optional<StatusChangeRecord> findStatusChange(UUID historyId);
    Impacts impacts(UUID studentId);
    Claim claim(String scope, UUID key, String requestHash);
    void complete(String scope, UUID key, UUID resourceId, int status);
    boolean studentExists(UUID id);
    List<NoteRecord> findNotes(UUID studentId, Instant beforeAt, UUID beforeId, int limit);
    Optional<NoteRecord> findNote(UUID noteId);
    void insertNote(UUID id, UUID studentId, byte[] ciphertext, UUID actor);
    int updateNote(UUID id, long version, byte[] ciphertext, UUID actor);
    int hideNote(UUID id, long version, String reason, UUID actor);

    record StoredGuardian(GuardianWrite value, UUID id, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, byte[] emailCiphertext, String emailHash, String emailDomain) {}
    record StatusChangeRecord(UUID id, String fromStatus, String toStatus, LocalDate effectiveDate,
            Instant changedAt, long version) {}
}
