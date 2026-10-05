package com.ramiart.admin.financeimport.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialImportRowImporter {
    private final FinancialImportRepository repository;private final AuditRecorder audit;private final Clock clock;
    public FinancialImportRowImporter(FinancialImportRepository repository,AuditRecorder audit,Clock clock){this.repository=repository;this.audit=audit;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void importRow(UUID batchId,UUID rowId,UUID actor,FinancialImportService.Metadata metadata){
        repository.importRow(batchId,rowId,actor);
        audit.record(new AuditRecorder.Event(clock.instant(),metadata.requestId(),"MGT-FINANCE-IMPORT","FINANCE","ADMIN",actor,null,
                "FINANCIAL_IMPORT_ROW_CREATED","FINANCIAL_IMPORT_ROW",rowId,"SUCCESS","FINANCIAL_IMPORT_CONFIRM",metadata.ipAddress(),metadata.userAgent(),Map.of("batchId",batchId.toString())));
    }
}
