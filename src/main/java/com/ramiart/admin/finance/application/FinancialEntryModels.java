package com.ramiart.admin.finance.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FinancialEntryModels {
    private FinancialEntryModels() {}

    public record Account(UUID accountId, String name, boolean active, String type, int displayOrder) {}
    public record Category(String code, String name, String type, boolean active, int displayOrder) {}
    public record Options(List<Account> accounts, List<Category> categories, List<Account> filterAccounts, List<Category> filterCategories) {}
    public record Creator(UUID adminUserId, String displayName) {}
    public record Actions(boolean canCancel) {}
    public record Entry(UUID entryId, LocalDate transactionDate, String type, Account account, Category category,
            String description, long amount, String status, String sourceType, UUID sourceId, String externalId,
            Creator createdBy, OffsetDateTime createdAt, OffsetDateTime cancelledAt, String cancelReason,
            long version, Actions actions) {}
    public record Totals(long income, long expense, long net, long count) {}
    public record Page(int number, int size, long totalElements, long totalPages) {}
    public record Applied(LocalDate from, LocalDate to, List<String> statuses) {}
    public record EntryPage(List<Entry> items, Page page, Totals totals, Applied applied) {}
    public record CreateRequest(LocalDate transactionDate, String type, UUID accountId, String categoryCode,
            String description, long amount) {}
    public record CancelRequest(String reason, long version) {}
    public record WriteResult(Entry entry, boolean created) {}
}
