package com.ramiart.admin.datatransfer.infrastructure;
import com.ramiart.admin.datatransfer.application.DataTransferService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(prefix="admin.data-transfer",name="worker-enabled",havingValue="true",matchIfMissing=true)
public final class DataTransferQueueWorker {
    private final DataTransferService service;
    public DataTransferQueueWorker(DataTransferService service){this.service=service;}
    @Scheduled(fixedDelayString="${admin.data-transfer.worker-delay-ms:5000}",initialDelayString="${admin.data-transfer.worker-initial-delay-ms:15000}")
    public void poll(){service.processNext();}
    @Scheduled(fixedDelayString="${admin.data-transfer.cleanup-delay-ms:3600000}",initialDelayString="${admin.data-transfer.cleanup-initial-delay-ms:60000}")
    public void cleanup(){service.cleanupExpired();}
}
