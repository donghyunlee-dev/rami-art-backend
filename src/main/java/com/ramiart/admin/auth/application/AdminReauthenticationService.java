package com.ramiart.admin.auth.application;

import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.auth.application.PasswordChangeRepository.PasswordAccount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReauthenticationService {
    private static final Duration TOKEN_LIFETIME=Duration.ofMinutes(5);
    private static final Set<String> PURPOSES=Set.of("MAKEUP_EXTENSION","RETENTION_EXECUTION","GALLERY_PUBLISH_EXEMPTION");
    private final PasswordChangeRepository accounts; private final AdminReauthenticationRepository tokens;
    private final PasswordVerifier verifier; private final AuthRateLimiter limiter; private final AuditRecorder audit;
    private final Clock clock; private final SecureRandom random=new SecureRandom();
    public AdminReauthenticationService(PasswordChangeRepository accounts,AdminReauthenticationRepository tokens,
            PasswordVerifier verifier,AuthRateLimiter limiter,AuditRecorder audit,Clock clock){this.accounts=accounts;this.tokens=tokens;this.verifier=verifier;this.limiter=limiter;this.audit=audit;this.clock=clock;}

    @Transactional
    public Issued issue(String sessionCookie,String password,String purpose,Authentication authentication,Metadata meta){
        UUID actor=actor(authentication);
        if(sessionCookie==null||sessionCookie.isBlank()||password==null||password.isBlank()||password.length()>128||!PURPOSES.contains(purpose))throw new ReauthenticationException("VALIDATION_ERROR");
        limiter.check("reauth-user",actor.toString(),5);limiter.check("reauth-ip",meta.ipAddress(),20);
        PasswordAccount account=accounts.findBySessionForUpdate(sha256(sessionCookie)).filter(value->value.userId().equals(actor))
                .orElseThrow(()->new ReauthenticationException("REAUTHENTICATION_FAILED"));
        Instant now=clock.instant();
        if(!"ACTIVE".equals(account.status())||account.revokedAt()!=null||!account.expiresAt().isAfter(now)||!account.idleExpiresAt().isAfter(now)
                ||!verifier.matches(password,account.passwordHash()))throw new ReauthenticationException("REAUTHENTICATION_FAILED");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.issue(UUID.randomUUID(),actor,account.sessionId(),purpose,sha256(raw),now.plus(TOKEN_LIFETIME));
        audit.record(new Event(now,meta.requestId(),"MGT-AUTH-REAUTHENTICATE","SECURITY","ADMIN",actor,null,"ADMIN_REAUTHENTICATED","ADMIN_SESSION",account.sessionId(),"SUCCESS",null,meta.ipAddress(),meta.userAgent(),java.util.Map.of("purpose",purpose)));
        return new Issued(raw,now.plus(TOKEN_LIFETIME));
    }

    @Transactional
    public void consume(String rawToken,String sessionCookie,String purpose,Authentication authentication){
        UUID actor=actor(authentication);if(rawToken==null||rawToken.isBlank()||sessionCookie==null||sessionCookie.isBlank())throw new ReauthenticationException("REAUTHENTICATION_REQUIRED");
        PasswordAccount account=accounts.findBySessionForUpdate(sha256(sessionCookie)).filter(value->value.userId().equals(actor))
                .orElseThrow(()->new ReauthenticationException("REAUTHENTICATION_REQUIRED"));
        Instant now=clock.instant();
        if(account.revokedAt()!=null||!account.expiresAt().isAfter(now)||!account.idleExpiresAt().isAfter(now)
                ||!tokens.consume(sha256(rawToken),actor,account.sessionId(),purpose,now))throw new ReauthenticationException("REAUTHENTICATION_REQUIRED");
    }
    private static UUID actor(Authentication a){try{return UUID.fromString(a.getName());}catch(RuntimeException e){throw new ReauthenticationException("SESSION_REQUIRED");}}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    public record Metadata(String requestId,String ipAddress,String userAgent){}
    public record Issued(String reauthToken,Instant expiresAt){}
    public static final class ReauthenticationException extends RuntimeException{private final String code;public ReauthenticationException(String code){super(code);this.code=code;}public String code(){return code;}}
}
