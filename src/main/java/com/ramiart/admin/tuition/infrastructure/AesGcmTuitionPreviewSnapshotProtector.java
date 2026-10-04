package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.TuitionPreviewSnapshotProtector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmTuitionPreviewSnapshotProtector implements TuitionPreviewSnapshotProtector {
    private static final byte VERSION=1;
    private static final int IV_LENGTH=12;
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();

    public AesGcmTuitionPreviewSnapshotProtector(
            @Value("${ADMIN_TUITION_PREVIEW_KEY:${ADMIN_STUDENT_DATA_KEY:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}}") String encodedKey) {
        byte[] source=Base64.getDecoder().decode(encodedKey);
        if(source.length!=32) throw new IllegalStateException("tuition preview key must decode to 32 bytes");
        try {
            byte[] context="rami-tuition-preview-snapshot-v1".getBytes(StandardCharsets.UTF_8);
            byte[] derived=MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(source.length+context.length)
                    .put(source).put(context).array());
            key=new SecretKeySpec(derived,"AES");
        } catch(GeneralSecurityException exception) { throw new IllegalStateException("tuition preview key unavailable",exception); }
    }

    @Override public byte[] encrypt(String value) {
        try {
            byte[] iv=new byte[IV_LENGTH]; random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1+iv.length+ciphertext.length).put(VERSION).put(iv).put(ciphertext).array();
        } catch(GeneralSecurityException exception) { throw new IllegalStateException("tuition preview snapshot encryption failed",exception); }
    }

    @Override public String decrypt(byte[] value) {
        try {
            ByteBuffer buffer=ByteBuffer.wrap(value);
            if(buffer.get()!=VERSION) throw new IllegalStateException("unsupported tuition preview snapshot");
            byte[] iv=new byte[IV_LENGTH]; buffer.get(iv); byte[] ciphertext=new byte[buffer.remaining()]; buffer.get(ciphertext);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
        } catch(GeneralSecurityException|java.nio.BufferUnderflowException exception) {
            throw new IllegalStateException("tuition preview snapshot decryption failed",exception);
        }
    }
}
