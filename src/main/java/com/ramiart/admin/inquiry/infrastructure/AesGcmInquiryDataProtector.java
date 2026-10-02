package com.ramiart.admin.inquiry.infrastructure;

import com.ramiart.admin.inquiry.application.InquiryDataProtector;
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
public final class AesGcmInquiryDataProtector implements InquiryDataProtector {
    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmInquiryDataProtector(@Value("${admin.security.inquiry-data-key:${ADMIN_INQUIRY_DATA_KEY:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}}") String encodedKey) {
        byte[] key;
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("Inquiry data key must be Base64", exception); }
        if (key.length != 32) throw new IllegalStateException("Inquiry data key must decode to 32 bytes");
        encryptionKey = new SecretKeySpec(key, "AES");
        hashKey = new SecretKeySpec(derive(key), "HmacSHA256");
    }

    @Override public byte[] protect(String value) {
        try {
            byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length).put(VERSION).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Inquiry data protection failed", exception); }
    }

    @Override public String reveal(byte[] value) {
        if (value == null || value.length <= 1 + IV_LENGTH || value[0] != VERSION) throw new IllegalStateException("Unsupported inquiry ciphertext");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(128, java.util.Arrays.copyOfRange(value, 1, 1 + IV_LENGTH)));
            return new String(cipher.doFinal(java.util.Arrays.copyOfRange(value, 1 + IV_LENGTH, value.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Inquiry data reveal failed", exception); }
    }

    @Override public String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Inquiry hash failed", exception); }
    }

    private static byte[] derive(byte[] key) {
        try { return MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(key.length + 20)
                .put(key).put("rami-inquiry-hmac-v1".getBytes(StandardCharsets.UTF_8)).array()); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
}
