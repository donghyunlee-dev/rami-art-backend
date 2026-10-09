package com.ramiart.admin.notification.infrastructure;

import com.ramiart.admin.notification.application.NotificationProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class ResendNotificationProvider implements NotificationProvider {
    private final RestClient client;
    private final String apiKey;
    private final String from;
    private final ObjectMapper errors = new ObjectMapper();

    public ResendNotificationProvider(RestClient client, String apiKey, String from) {
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()
                || apiKey.contains("\n") || apiKey.contains("\r") || from.contains("\n") || from.contains("\r")) {
            throw new IllegalArgumentException("Resend credentials and sender must be configured");
        }
        this.client = client;
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override public String channel() { return "EMAIL"; }

    @Override public Delivery send(UUID messageId, String recipient, String subject, String body) {
        try {
            Response response = client.post().uri("https://api.resend.com/emails")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .header("Idempotency-Key", "notification/" + messageId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(from, List.of(recipient),
                            subject == null || subject.isBlank() ? "라미아트 안내" : subject, body))
                    .retrieve().body(Response.class);
            if (response == null || response.id() == null || response.id().isBlank() || response.id().length() > 200) {
                throw new DeliveryException("PROVIDER_INVALID_RESPONSE", true);
            }
            return new Delivery("RESEND", response.id());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 429 || status >= 500;
            if (status == 409) {
                try {
                    var error = errors.readTree(exception.getResponseBodyAsString());
                    String name = error == null ? "" : error.path("name").asText();
                    retryable = "concurrent_idempotent_requests".equals(name) || "resource_locked".equals(name);
                } catch (java.io.IOException ignored) {
                    // An unknown conflict cannot safely be classified as transient.
                }
            }
            throw new DeliveryException("PROVIDER_HTTP_" + status, retryable);
        } catch (RestClientException exception) {
            throw new DeliveryException("PROVIDER_CONNECTION_FAILED", true);
        }
    }

    private record Request(String from, List<String> to, String subject, String text) {}
    private record Response(String id) {}
}
