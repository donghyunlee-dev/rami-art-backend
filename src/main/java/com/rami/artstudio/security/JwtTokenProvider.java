package com.rami.artstudio.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.common.config.AppSecurityProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;

@Component
public class JwtTokenProvider {

    private final AppSecurityProperties securityProperties;

    public JwtTokenProvider(AppSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public String createAccessToken(AdminUser adminUser) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(securityProperties.getAccessTokenExpirationSeconds());

        return Jwts.builder()
                .subject(adminUser.getId().toString())
                .claim("email", adminUser.getEmail())
                .claim("role", adminUser.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        UUID adminUserId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String role = claims.get("role", String.class);
        return new AuthPrincipal(adminUserId, email, role);
    }

    public long accessTokenExpiresIn() {
        return securityProperties.getAccessTokenExpirationSeconds();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
