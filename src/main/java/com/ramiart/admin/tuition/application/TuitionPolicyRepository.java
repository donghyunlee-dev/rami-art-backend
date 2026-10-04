package com.ramiart.admin.tuition.application;

import com.ramiart.admin.tuition.application.TuitionPolicyModels.*;
import java.util.Optional;
import java.util.UUID;

public interface TuitionPolicyRepository {
    Optional<Policy> byYearStatus(int year, String status);
    Optional<Policy> byId(UUID id);
    UUID create(int year, int revision, UUID basedOn, int dueDay, UUID actor);
    int update(UUID id, long version, int dueDay, java.util.List<Item> items);
    void publish(UUID id, long version, UUID actor);
    record Claim(boolean claimed, UUID resourceId) {}
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int status);
}
