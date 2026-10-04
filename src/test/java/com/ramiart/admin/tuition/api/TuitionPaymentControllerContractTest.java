package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TuitionPaymentControllerContractTest {
    @Test
    @DisplayName("MGT-TUITION-PAYMENT-RECORD maps list, create, and cancel routes")
    void tuitionPaymentRoutesMatchTheDocumentedContract() throws Exception {
        Class<?> controller = Class.forName("com.ramiart.admin.tuition.api.TuitionPaymentController");
        assertThat(controller.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/admin");
        assertThat(Arrays.stream(controller.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())).toList())
                .containsExactly("/tuition-billings/{billingId}/payments");
        assertThat(Arrays.stream(controller.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())).toList())
                .containsExactlyInAnyOrder("/tuition-billings/{billingId}/payments",
                        "/tuition-payments/{paymentId}/cancellations");
    }
}
