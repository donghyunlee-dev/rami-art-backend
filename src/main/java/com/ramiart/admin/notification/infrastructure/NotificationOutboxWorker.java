package com.ramiart.admin.notification.infrastructure;

import com.ramiart.admin.notification.application.NotificationProvider;
import com.ramiart.admin.notification.application.NotificationRepository;
import com.ramiart.admin.notification.application.NotificationRepository.Dispatch;
import com.ramiart.admin.student.application.StudentDataProtector;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class NotificationOutboxWorker {
    private static final Pattern SAFE_CODE=Pattern.compile("[A-Z0-9_]{1,80}");
    private final NotificationRepository repository;
    private final StudentDataProtector protector;
    private final List<NotificationProvider> providers;
    private final Clock clock;

    public NotificationOutboxWorker(NotificationRepository repository,StudentDataProtector protector,
            List<NotificationProvider> providers,Clock clock){this.repository=repository;this.protector=protector;this.providers=List.copyOf(providers);this.clock=clock;}

    @Scheduled(fixedDelayString="${admin.notification.worker-delay-ms:5000}",initialDelayString="${admin.notification.worker-initial-delay-ms:15000}")
    public void poll(){
        for(NotificationProvider provider:providers){
            if(!List.of("EMAIL","SMS","KAKAO").contains(provider.channel()))continue;
            for(int i=0;i<10;i++){
                var candidate=repository.claimNext(provider.channel(),OffsetDateTime.now(clock));if(candidate.isEmpty())break;
                Dispatch dispatch=candidate.get();String safeCode=null;boolean retryable=false;String providerName=null;String providerMessageId=null;
                try{
                    String recipient=protector.reveal(dispatch.recipient());String subject=dispatch.subject()==null?null:protector.reveal(dispatch.subject());String body=protector.reveal(dispatch.body());
                    NotificationProvider.Delivery delivery=provider.send(dispatch.id(),recipient,subject,body);
                    if(delivery==null||!safe(delivery.provider(),50)||delivery.providerMessageId()==null||delivery.providerMessageId().isBlank()||delivery.providerMessageId().length()>200)throw new NotificationProvider.DeliveryException("PROVIDER_RESPONSE_INVALID",false);
                    providerName=delivery.provider();providerMessageId=delivery.providerMessageId();
                }catch(NotificationProvider.DeliveryException exception){safeCode=safe(exception.safeCode(),80)?exception.safeCode():"PROVIDER_FAILURE";retryable=exception.retryable();}
                catch(RuntimeException exception){safeCode="PROVIDER_UNAVAILABLE";retryable=true;}
                OffsetDateTime completed=OffsetDateTime.now(clock);OffsetDateTime next=retryable&&dispatch.attemptNumber()<5?completed.plus(backoff(dispatch.attemptNumber())):null;
                repository.finish(dispatch,providerName,providerMessageId,safeCode,retryable,completed,next);
            }
        }
    }
    private static java.time.Duration backoff(int attempt){return switch(attempt){case 1->java.time.Duration.ofMinutes(1);case 2->java.time.Duration.ofMinutes(5);case 3->java.time.Duration.ofMinutes(15);default->java.time.Duration.ofMinutes(30);};}
    private static boolean safe(String value,int max){return value!=null&&value.length()<=max&&SAFE_CODE.matcher(value).matches();}
}
