package com.ramiart.admin.financeimport.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.financeimport.application.FinancialImportRepository;
import com.ramiart.admin.financeimport.application.FinancialImportRepository.ExpiringFile;
import com.ramiart.admin.financeimport.application.FinancialImportStorage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinancialImportExpiryJobTest {
    private final FinancialImportRepository repository=mock(FinancialImportRepository.class);
    private final FinancialImportStorage storage=mock(FinancialImportStorage.class);
    private final FinancialImportExpiryJob job=new FinancialImportExpiryJob(repository,storage);

    @Test void removesObjectBeforeClearingDatabaseReference(){
        UUID batchId=UUID.randomUUID();
        when(repository.expiringFiles(50)).thenReturn(List.of(new ExpiringFile(batchId,"financial-imports/"+batchId+".csv")));

        job.expireFiles();

        org.mockito.InOrder order=org.mockito.Mockito.inOrder(storage,repository);
        order.verify(storage).delete("financial-imports/"+batchId+".csv");
        order.verify(repository).completeFileExpiry(batchId,"financial-imports/"+batchId+".csv");
    }

    @Test void keepsDatabaseReferenceWhenObjectDeletionFails(){
        UUID batchId=UUID.randomUUID();String key="financial-imports/"+batchId+".csv";
        when(repository.expiringFiles(50)).thenReturn(List.of(new ExpiringFile(batchId,key)));
        org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(key);

        job.expireFiles();

        verify(repository,never()).completeFileExpiry(batchId,key);
    }
}
