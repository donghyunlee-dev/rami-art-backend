package com.ramiart.admin.financeimport.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import com.ramiart.admin.financeimport.application.FinancialImportRepository.Claim;
import com.ramiart.admin.financeimport.infrastructure.FinancialImportParserWorker;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class FinancialImportServiceTest {
    @Mock FinancialImportRepository repository;
    @Mock FinancialImportStorage storage;
    @Mock AuditRecorder audit;
    @Mock FinancialImportParserWorker parserWorker;
    @Mock FinancialImportRowImporter rowImporter;
    @Mock PlatformTransactionManager transactions;
    private FinancialImportService service;
    private UUID batchId;
    private UUID rowId;
    private UUID actorId;
    private UUID idempotencyKey;
    private TestingAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        service = new FinancialImportService(repository, storage, audit, parserWorker, rowImporter,
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC), transactions, "test-key");
        batchId = UUID.randomUUID();
        rowId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID();
        authentication = new TestingAuthenticationToken(actorId.toString(), "", "FINANCE_IMPORT");
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void MGT_FINANCE_IMPORT_non_unique_database_failure_is_not_classified_as_duplicate() {
        givenConfirmableBatch();
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(rowImporter).importRow(eq(batchId), eq(rowId), eq(actorId), any());

        service.confirm(batchId, new ConfirmationRequest(List.of(rowId), 1), idempotencyKey, authentication, metadata());

        verify(repository).failRow(rowId, "IMPORT_SAVE_FAILED");
    }

    @Test
    void MGT_FINANCE_IMPORT_unique_conflict_is_classified_as_duplicate() {
        givenConfirmableBatch();
        doThrow(new DuplicateKeyException("duplicate key"))
                .when(rowImporter).importRow(eq(batchId), eq(rowId), eq(actorId), any());

        service.confirm(batchId, new ConfirmationRequest(List.of(rowId), 1), idempotencyKey, authentication, metadata());

        verify(repository).failRow(rowId, "IMPORT_DUPLICATE_DETECTED");
    }

    private void givenConfirmableBatch() {
        Batch batch = new Batch(batchId, "READY", "source.csv", new Account(UUID.randomUUID(), "운영 계좌"),
                new Counts(1, 1, 0, 0, 0, 0, 0), new Totals(0, 0, 0),
                new DuplicateFileWarning(false, null, null), OffsetDateTime.parse("2026-10-12T00:00:00Z"),
                null, OffsetDateTime.parse("2026-11-11T00:00:00Z"), 1,
                new Actions(true, false));
        Row row = new Row(rowId, 2, LocalDate.parse("2026-10-10"), "INCOME", 1000L, "입금", "TUITION",
                "bank-001", "VALID", List.of(), null, true, null, null);
        when(repository.claim(eq(batchId), eq(1L), anyString(), eq(idempotencyKey), anyString()))
                .thenReturn(new Claim(true, false, batch));
        when(repository.selectedRows(batchId, List.of(rowId))).thenReturn(List.of(row));
        when(repository.validRowsForConfirmation(batchId, 5000)).thenReturn(List.of(rowId));
        when(repository.finishConfirmation(batchId)).thenReturn(new Confirmation(batchId, "PARTIAL", 1,
                List.of(), List.of(new Failed(rowId, "IMPORT_SAVE_FAILED")), new Totals(0, 0, 0), 3));
    }

    private static FinancialImportService.Metadata metadata() {
        return new FinancialImportService.Metadata("request-import-43", "127.0.0.1", "unit-test");
    }
}
