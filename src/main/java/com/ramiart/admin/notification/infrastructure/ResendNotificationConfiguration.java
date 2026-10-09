package com.ramiart.admin.notification.infrastructure;

import com.ramiart.admin.notification.application.NotificationProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(prefix = "admin.notification.resend", name = "enabled", havingValue = "true")
public class ResendNotificationConfiguration {
    @Bean NotificationProvider resendNotificationProvider(
            RestClient.Builder builder,
            @Value("${admin.notification.resend.api-key:${RESEND_API_KEY:}}") String apiKey,
            @Value("${admin.notification.resend.from:${ADMIN_NOTIFICATION_FROM_EMAIL:}}") String from) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new ResendNotificationProvider(builder.clone().requestFactory(factory).build(), apiKey, from);
    }
}
