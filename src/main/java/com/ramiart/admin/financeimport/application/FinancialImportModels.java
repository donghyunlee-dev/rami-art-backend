package com.ramiart.admin.financeimport.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FinancialImportModels {
    private FinancialImportModels(){}
    public record Account(UUID accountId,String name){}
    public record Counts(int total,int valid,int error,int duplicate,int selected,int imported,int failed){}
    public record Actions(boolean canConfirm,boolean canDownloadResult){}
    public record Batch(UUID batchId,String status,String fileName,Account account,Counts counts,Totals resultTotals,
            DuplicateFileWarning duplicateFileWarning,OffsetDateTime createdAt,OffsetDateTime confirmedAt,OffsetDateTime expiresAt,
            long version,Actions actions){}
    public record DuplicateFileWarning(boolean exists,UUID recentBatchId,OffsetDateTime uploadedAt){}
    public record Totals(long income,long expense,long net){}
    public record Issue(String field,String code){}
    public record DuplicateEntry(UUID entryId,LocalDate transactionDate,long amount,String description){}
    public record Row(UUID rowId,int rowNumber,LocalDate transactionDate,String type,Long amount,String description,String categoryCode,
            String externalId,String status,List<Issue> issues,DuplicateEntry duplicateEntry,boolean selectable,UUID financialEntryId,String financialEntryStatus){}
    public record RowPage(List<Row> items,RowPageInfo page){}
    public record RowPageInfo(int size,String nextCursor,boolean hasNext){}
    public record RowInput(UUID id,int rowNumber,LocalDate transactionDate,String type,Long amount,String description,String categoryCode,
            String externalId,String dedupHash,String status,List<Issue> issues,UUID duplicateEntryId,UUID duplicateRowId){}
    public record ConfirmationRequest(List<UUID> rowIds,long batchVersion){}
    public record Imported(UUID rowId,UUID entryId){}
    public record Failed(UUID rowId,String code){}
    public record Confirmation(UUID batchId,String status,int selectedCount,List<Imported> imported,List<Failed> failed,Totals totals,long version){}
}
