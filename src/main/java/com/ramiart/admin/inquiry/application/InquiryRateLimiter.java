package com.ramiart.admin.inquiry.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InquiryRateLimiter {
    private final InquiryRepository repository;
    private final InquiryDataProtector protector;
    private final Clock clock;

    public InquiryRateLimiter(InquiryRepository repository, InquiryDataProtector protector, Clock clock) {
        this.repository=repository; this.protector=protector; this.clock=clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String remoteAddress, String normalizedPhone) {
        Instant now=clock.instant();
        Instant hour=now.truncatedTo(ChronoUnit.HOURS);
        Instant day=now.truncatedTo(ChronoUnit.DAYS);
        int ipCount=repository.incrementRateLimit("IP_HOUR",protector.hash("ip:"+safe(remoteAddress)),hour);
        int phoneCount=repository.incrementRateLimit("PHONE_DAY",protector.hash("phone:"+normalizedPhone),day);
        return ipCount<=20 && phoneCount<=5;
    }

    private static String safe(String value){ return value==null?"unknown":value; }
}
