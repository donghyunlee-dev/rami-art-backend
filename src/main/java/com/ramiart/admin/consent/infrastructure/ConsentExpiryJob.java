package com.ramiart.admin.consent.infrastructure;

import com.ramiart.admin.consent.application.ConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConsentExpiryJob {
    private static final Logger LOG=LoggerFactory.getLogger(ConsentExpiryJob.class);
    private final ConsentService service;
    public ConsentExpiryJob(ConsentService service){this.service=service;}

    @Scheduled(fixedDelayString="${admin.consent.expiry-delay-ms:3600000}",initialDelayString="${admin.consent.expiry-initial-delay-ms:3600000}")
    public void expire(){try{service.expire(500);}catch(RuntimeException exception){LOG.error("Consent expiry job failed");}}
}
