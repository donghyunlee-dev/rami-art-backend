package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TuitionReceiptControllerContractTest {
    @Test
    @DisplayName("MGT-TUITION-RECEIPT maps receipt summary, issue, reissue, and signed download routes")
    void receiptRoutesMatchContract() throws Exception {
        Class<?> controller=Class.forName("com.ramiart.admin.tuition.api.TuitionReceiptController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(GetMapping.class))
                .filter(Objects::nonNull).flatMap(mapping->Arrays.stream(mapping.value())).toList())
                .containsExactlyInAnyOrder("/payments/{paymentId}/receipt",
                        "/receipts/{receiptId}/versions/{version}/download-url");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(PostMapping.class))
                .filter(Objects::nonNull).flatMap(mapping->Arrays.stream(mapping.value())).toList())
                .containsExactlyInAnyOrder("/payments/{paymentId}/receipt","/receipts/{receiptId}/versions");
    }
}
