package com.ramiart.admin.student.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmStudentDataProtectorTest {
    private final AesGcmStudentDataProtector protector = new AesGcmStudentDataProtector(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithRandomIvAndDecryptsPhoneAndEmail() {
        var first = protector.protect("+821012345678");
        var second = protector.protect("+821012345678");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.hash()).isEqualTo(second.hash()).hasSize(64);
        assertThat(protector.reveal(first.ciphertext())).isEqualTo("+821012345678");
    }

    @Test
    void separatesDifferentNormalizedValues() {
        assertThat(protector.hash("guardian@example.com"))
                .isNotEqualTo(protector.hash("other@example.com"));
    }
}
