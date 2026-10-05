package com.ramiart.admin.notification.application;

public final class NotificationException extends RuntimeException {
    private final String code;
    public NotificationException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
