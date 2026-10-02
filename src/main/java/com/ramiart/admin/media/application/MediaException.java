package com.ramiart.admin.media.application;

public final class MediaException extends RuntimeException {
    private final String code;

    public MediaException(String code) {
        super(code);
        this.code = code;
    }

    public MediaException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

