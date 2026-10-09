package com.ramiart.admin.consent.infrastructure;
import com.ramiart.admin.consent.application.ConsentEvidenceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(prefix="admin.consent",name="worker-enabled",havingValue="true",matchIfMissing=true)
public final class ConsentEvidenceCleanupJob {
    private final ConsentEvidenceService service;
    public ConsentEvidenceCleanupJob(ConsentEvidenceService service){this.service=service;}
    @Scheduled(fixedDelayString="${admin.consent.evidence-cleanup-delay-ms:3600000}",initialDelayString="${admin.consent.evidence-cleanup-initial-delay-ms:60000}")
    public void cleanup(){service.cleanup();}
}
