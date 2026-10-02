package com.ramiart.admin.staff.infrastructure;

import com.ramiart.admin.staff.application.StaffPhoneProtector;
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
public final class AesGcmStaffPhoneProtector implements StaffPhoneProtector {

    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] HASH_CONTEXT = "rami-staff-phone-hmac-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmStaffPhoneProtector(@Value("${admin.security.staff-phone-key}") String encodedKey) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("admin.security.staff-phone-key must be valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("admin.security.staff-phone-key must decode to 32 bytes");
        }
        this.encryptionKey = new SecretKeySpec(key, "AES");
        this.hashKey = new SecretKeySpec(deriveHashKey(key), "HmacSHA256");
    }

    @Override
    public ProtectedPhone protect(String normalizedPhone) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = ByteBuffer.allocate(1 + iv.length + encrypted.length)
                    .put(FORMAT_VERSION).put(iv).put(encrypted).array();
            return new ProtectedPhone(envelope, hash(normalizedPhone), normalizedPhone.substring(normalizedPhone.length() - 4));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Staff phone protection failed", exception);
        }
    }

    @Override
    public String reveal(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= 1 + IV_LENGTH || ciphertext[0] != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported staff phone ciphertext");
        }
        try {
            byte[] iv = java.util.Arrays.copyOfRange(ciphertext, 1, 1 + IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(ciphertext, 1 + IV_LENGTH, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Staff phone reveal failed", exception);
        }
    }

    private String hash(String normalizedPhone) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hashKey);
        return HexFormat.of().formatHex(mac.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] deriveHashKey(byte[] key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key);
            return digest.digest(HASH_CONTEXT);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
