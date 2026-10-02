package com.ramiart.admin.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    void normalizesWhitespaceAndCase() {
        assertThat(EmailAddress.of("  Owner@Rami.Art ").value()).isEqualTo("owner@rami.art");
    }

    @Test
    void rejectsInvalidAddress() {
        assertThatThrownBy(() -> EmailAddress.of("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
