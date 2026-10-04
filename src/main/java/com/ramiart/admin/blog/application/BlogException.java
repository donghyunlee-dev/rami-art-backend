package com.ramiart.admin.blog.application;

public final class BlogException extends RuntimeException {
    private final String code;

    public BlogException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
