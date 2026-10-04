package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TuitionAdjustmentControllerContractTest {
    @Test
    @DisplayName("MGT-TUITION-ADJUSTMENT maps history, preview, adjust, cancel, and refund routes")
    void adjustmentRoutesMatchContract() throws Exception {
        Class<?> controller=Class.forName("com.ramiart.admin.tuition.api.TuitionAdjustmentController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(GetMapping.class))
                .filter(Objects::nonNull).flatMap(mapping->Arrays.stream(mapping.value())).toList())
                .containsExactly("/billings/{billingId}/adjustments");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(PostMapping.class))
                .filter(Objects::nonNull).flatMap(mapping->Arrays.stream(mapping.value())).toList())
                .containsExactlyInAnyOrder("/billings/{billingId}/adjustment-preview",
                        "/billings/{billingId}/adjustments","/adjustments/{adjustmentId}/cancellation",
                        "/billings/{billingId}/refunds","/refunds/{refundId}/cancellation");
    }
}
