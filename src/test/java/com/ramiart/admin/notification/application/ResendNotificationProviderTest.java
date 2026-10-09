package com.ramiart.admin.notification.application;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.ramiart.admin.notification.infrastructure.ResendNotificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.UUID;

class ResendNotificationProviderTest {
    @Test void MGT_NOTIFICATION_email_uses_stable_provider_key_and_plain_text(){
        RestClient.Builder builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var provider=new ResendNotificationProvider(builder.build(),"test-only-key","Studio <studio@example.test>");
        UUID id=UUID.randomUUID();server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Bearer test-only-key"))
                .andExpect(header("Idempotency-Key","notification/"+id))
                .andExpect(content().json("{\"from\":\"Studio <studio@example.test>\",\"to\":[\"guardian@example.test\"],\"subject\":\"Lesson\",\"text\":\"<p>literal</p>\"}"))
                .andRespond(withSuccess("{\"id\":\"provider-id\"}",MediaType.APPLICATION_JSON));
        assertThat(provider.send(id,"guardian@example.test","Lesson","<p>literal</p>"))
                .isEqualTo(new NotificationProvider.Delivery("RESEND","provider-id"));server.verify();
    }
    @Test void MGT_NOTIFICATION_rate_limit_retries_but_credentials_failure_does_not(){
        for(HttpStatus status:new HttpStatus[]{HttpStatus.TOO_MANY_REQUESTS,HttpStatus.UNAUTHORIZED}){
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            var provider=new ResendNotificationProvider(builder.build(),"test-only-key","studio@example.test");
            server.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(status));
            assertThatThrownBy(()->provider.send(UUID.randomUUID(),"guardian@example.test","Lesson","body"))
                    .isInstanceOfSatisfying(NotificationProvider.DeliveryException.class,e->assertThat(e.retryable()).isEqualTo(status==HttpStatus.TOO_MANY_REQUESTS));server.verify();
        }
    }
    @Test void MGT_NOTIFICATION_invalid_success_response_never_marks_delivery_sent(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var provider=new ResendNotificationProvider(builder.build(),"test-only-key","studio@example.test");
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->provider.send(UUID.randomUUID(),"guardian@example.test","Lesson","body"))
                .isInstanceOf(NotificationProvider.DeliveryException.class);server.verify();
    }
    @Test void MGT_NOTIFICATION_concurrent_provider_request_retries_but_changed_payload_does_not(){
        for(String name:new String[]{"concurrent_idempotent_requests","invalid_idempotent_request"}){
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            var provider=new ResendNotificationProvider(builder.build(),"test-only-key","studio@example.test");
            server.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"name\":\""+name+"\"}"));
            assertThatThrownBy(()->provider.send(UUID.randomUUID(),"guardian@example.test","Lesson","body"))
                    .isInstanceOfSatisfying(NotificationProvider.DeliveryException.class,e->assertThat(e.retryable()).isEqualTo(name.equals("concurrent_idempotent_requests")));
            server.verify();
        }
    }
}
