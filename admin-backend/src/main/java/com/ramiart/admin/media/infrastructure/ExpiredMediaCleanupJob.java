package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.MediaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ExpiredMediaCleanupJob {
    private final MediaService service;

    public ExpiredMediaCleanupJob(MediaService service) {
        this.service = service;
    }

    @Scheduled(cron = "${admin.media.cleanup-cron:0 20 3 * * *}", zone = "UTC")
    public void cleanup() {
        service.cleanupExpired(100);
    }
}

