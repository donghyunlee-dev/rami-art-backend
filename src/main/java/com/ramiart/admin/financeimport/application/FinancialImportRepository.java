package com.ramiart.admin.financeimport.application;

import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialImportRepository {
    boolean activeAccount(UUID accountId);
    UUID create(String fileName,String sha256,UUID accountId,UUID actor);
    void setStorageKey(UUID batchId,String key);
    DuplicateFileWarning duplicateWarning(UUID accountId,String sha256,UUID excludeBatch);
    void parseRows(UUID batchId,List<RowInput> rows);
    Optional<Batch> batch(UUID id);
    Optional<Batch> lockBatch(UUID id);
    Optional<String> storageKey(UUID id);
    void failParsing(UUID id);
    List<UUID> confirmingBatches(int limit);
    List<ExpiringFile> expiringFiles(int limit);
    void completeFileExpiry(UUID batchId,String storageKey);
    List<UUID> validRowsForConfirmation(UUID batchId,int limit);
    Optional<UUID> confirmationActor(UUID batchId);
    RowPage rows(UUID batchId,List<String> statuses,int size,String cursorRow,String cursorId);
    List<Row> selectedRows(UUID batchId,List<UUID> ids);
    void prepareConfirmation(UUID batchId,int selected,long version,String scope,UUID key,String hash);
    List<UUID> validRowsExcept(UUID batchId,List<UUID> selected);
    void excludeRows(List<UUID> rowIds);
    void importRow(UUID batchId,UUID rowId,UUID actor);
    void failRow(UUID rowId,String code);
    Confirmation finishConfirmation(UUID batchId);
    Confirmation confirmation(UUID batchId);
    String resultCsv(UUID batchId);
    boolean activeCategory(String code,String type);
    boolean duplicateExternalId(UUID accountId,String externalId);
    boolean duplicateHash(UUID accountId,String dedupHash);
    Optional<DuplicateEntry> duplicateEntry(UUID accountId,String externalId,String dedupHash);
    Optional<UUID> duplicateRow(UUID batchId,String externalId,String dedupHash);
    Claim claim(UUID batchId,long version,String scope,UUID key,String hash);
    record Claim(boolean fresh,boolean replay,Batch batch){}
    record ExpiringFile(UUID batchId,String storageKey){}
}
