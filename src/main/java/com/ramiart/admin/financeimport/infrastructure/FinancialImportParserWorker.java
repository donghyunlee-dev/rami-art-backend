package com.ramiart.admin.financeimport.infrastructure;

import com.ramiart.admin.financeimport.application.FinancialImportService;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class FinancialImportParserWorker {
    private final FinancialImportService service;
    public FinancialImportParserWorker(FinancialImportService service){this.service=service;}
    @Async("financialImportExecutor") public void parse(UUID batchId){try{service.parse(batchId);}catch(RuntimeException e){service.markParseFailed(batchId);}}
}
