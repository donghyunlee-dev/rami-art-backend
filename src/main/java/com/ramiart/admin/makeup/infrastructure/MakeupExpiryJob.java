package com.ramiart.admin.makeup.infrastructure;

import com.ramiart.admin.makeup.application.MakeupService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MakeupExpiryJob {
    private static final Logger LOGGER=LoggerFactory.getLogger(MakeupExpiryJob.class);
    private final MakeupService service;
    public MakeupExpiryJob(MakeupService service){this.service=service;}
    @Scheduled(cron="${admin.makeup.expiry-cron:0 10 0 * * *}",zone="Asia/Seoul")
    public void expireAvailableCases(){
        int total=0,batch;
        do { batch=service.expireAvailableCases(LocalDate.now(ZoneId.of("Asia/Seoul"))); total+=batch; } while(batch==500);
        if(total>0)LOGGER.info("Expired makeup cases: count={}",total);
    }
}
