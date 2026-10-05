package com.ramiart.admin.financeimport.infrastructure;

import com.ramiart.admin.financeimport.application.FinancialImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FinancialImportRecoveryJob {
    private static final Logger LOG=LoggerFactory.getLogger(FinancialImportRecoveryJob.class);
    private final FinancialImportService service;
    public FinancialImportRecoveryJob(FinancialImportService service){this.service=service;}
    @Scheduled(fixedDelayString="${admin.financial-import.recovery-delay-ms:60000}")
    public void recover(){try{service.recoverConfirmations();}catch(RuntimeException e){LOG.error("Financial import recovery failed");}}
}
