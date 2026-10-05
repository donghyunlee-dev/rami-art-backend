package com.ramiart.admin.financeimport.infrastructure;

import com.ramiart.admin.financeimport.application.FinancialImportRepository;
import com.ramiart.admin.financeimport.application.FinancialImportStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FinancialImportExpiryJob {
    private static final Logger LOG=LoggerFactory.getLogger(FinancialImportExpiryJob.class);
    private final FinancialImportRepository repository;
    private final FinancialImportStorage storage;

    public FinancialImportExpiryJob(FinancialImportRepository repository,FinancialImportStorage storage){
        this.repository=repository;
        this.storage=storage;
    }

    @Scheduled(fixedDelayString="${admin.financial-import.expiry-delay-ms:3600000}")
    public void expireFiles(){
        for(FinancialImportRepository.ExpiringFile file:repository.expiringFiles(50)){
            try{
                storage.delete(file.storageKey());
                repository.completeFileExpiry(file.batchId(),file.storageKey());
            }catch(RuntimeException e){
                LOG.error("Financial import file expiry failed for batch {}",file.batchId());
            }
        }
    }
}
