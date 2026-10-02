package com.ramiart.admin.student.infrastructure;

import com.ramiart.admin.student.application.StudentDataProtector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmStudentDataProtector implements StudentDataProtector {
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmStudentDataProtector(
            @Value("${admin.security.student-data-key:${ADMIN_STUDENT_DATA_KEY:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}}") String encodedKey) {
        byte[] key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 32) throw new IllegalStateException("student protection key must decode to 32 bytes");
        encryptionKey = new SecretKeySpec(key, "AES");
        try {
            hashKey = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(ByteBuffer.allocate(key.length + 19).put(key)
                            .put("rami-student-pii-v1".getBytes(StandardCharsets.UTF_8)).array()), "HmacSHA256");
        } catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }

    public ProtectedValue protect(String value) { return new ProtectedValue(encrypt(value), hash(value)); }
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("PII hash failed", exception); }
    }
    public String reveal(byte[] value) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value);
            if (buffer.get() != 1) throw new IllegalStateException("unsupported ciphertext");
            byte[] iv = new byte[IV_LENGTH]; buffer.get(iv); byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("PII reveal failed", exception); }
    }
    private byte[] encrypt(String value) {
        try {
            byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + iv.length + encrypted.length).put((byte) 1).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("PII protection failed", exception); }
    }
}
