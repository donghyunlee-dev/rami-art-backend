package com.ramiart.admin.finance.application;

import com.ramiart.admin.finance.application.FinancialEntryModels.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialEntryRepository {
    EntryPage list(LocalDate from, LocalDate to, List<String> types, List<UUID> accountIds,
            List<String> categoryCodes, List<String> statuses, String keyword, UUID entryId, int page, int size);
    Options options();
    boolean validAccount(UUID id);
    boolean validCategory(String code, String type);
    Claim claim(String scope, UUID key, String requestHash);
    UUID insert(CreateRequest request, UUID actor);
    Optional<Entry> find(UUID id);
    int cancel(UUID id, long version, UUID actor, String reason);
    void complete(String scope, UUID key, UUID id, int responseStatus);

    record Claim(boolean claimed, UUID resourceId) {}
}
