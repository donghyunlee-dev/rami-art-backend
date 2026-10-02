package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.PasswordVerifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class EncodedPasswordVerifier implements PasswordVerifier {

    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public EncodedPasswordVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("timing-only-password");
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.length() > 128) return false;
        if ((encodedPassword.startsWith("$2") || encodedPassword.startsWith("{bcrypt}"))
                && rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) return false;
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public String dummyHash() {
        return dummyHash;
    }
}
