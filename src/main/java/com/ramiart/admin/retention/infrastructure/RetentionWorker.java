package com.ramiart.admin.retention.infrastructure;

import com.ramiart.admin.retention.application.RetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="admin.retention",name="worker-enabled",havingValue="true",matchIfMissing=true)
public final class RetentionWorker {
    private final RetentionService service;
    public RetentionWorker(RetentionService service){this.service=service;}
    @Scheduled(fixedDelayString="${admin.retention.worker-delay-ms:5000}",initialDelayString="${admin.retention.worker-initial-delay-ms:15000}")
    public void poll(){service.processNext();}
}
