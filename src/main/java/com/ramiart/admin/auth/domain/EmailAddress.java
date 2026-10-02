package com.ramiart.admin.auth.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmailAddress(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public EmailAddress {
        Objects.requireNonNull(value, "email must not be null");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("email format is invalid");
        }
    }

    public static EmailAddress of(String value) {
        return new EmailAddress(value);
    }
}
