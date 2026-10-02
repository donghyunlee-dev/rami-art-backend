package com.ramiart.admin.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionTokenTest {

    @Test
    void generatesAtLeast256BitsAndStoresOnlyAHash() {
        String first = SessionToken.generate();
        String second = SessionToken.generate();

        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(SessionToken.sha256(first)).hasSize(64).doesNotContain(first);
    }
}
