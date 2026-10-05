package com.ramiart.admin.notification.application;

import java.util.UUID;

public interface NotificationProvider {
    String channel();
    Delivery send(UUID messageId,String recipient,String subject,String body);
    record Delivery(String provider,String providerMessageId){}
    final class DeliveryException extends RuntimeException {
        private final String safeCode;
        private final boolean retryable;
        public DeliveryException(String safeCode,boolean retryable){super("notification delivery failed");this.safeCode=safeCode;this.retryable=retryable;}
        public String safeCode(){return safeCode;}
        public boolean retryable(){return retryable;}
    }
}
