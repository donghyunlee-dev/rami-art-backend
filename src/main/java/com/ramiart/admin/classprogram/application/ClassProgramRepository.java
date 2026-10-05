package com.ramiart.admin.classprogram.application;

import static com.ramiart.admin.classprogram.application.ClassProgramModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClassProgramRepository {
    record Claim(boolean claimed, UUID resourceId) {}
    List<CoursePrograms> findAll();
    Optional<Stored> find(UUID id);
    Optional<Stored> findCourseStatus(UUID courseId, String status);
    int nextRevision(UUID courseId);
    boolean courseExists(UUID courseId);
    UUID createDraft(UUID courseId, int revision, UUID actor, UUID basedOn);
    int save(UUID id, ProgramWrite write);
    void saveReferences(UUID id, UUID assetId);
    void publish(UUID id, long version, UUID actor);
    List<PublicProgram> publicPrograms();
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resource, int status);
    String mediaUrl(UUID assetId);
}
