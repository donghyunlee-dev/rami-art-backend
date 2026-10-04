package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;

class TuitionBillingControllerContractTest {
    @Test
    @DisplayName("MGT-TUITION-BILLING-GENERATE preview, list, and detail routes")
    void tuitionBillingControllerIsPresentForTheDocumentedAdminApi() {
        Class<?> controller;
        try {
            controller = Class.forName("com.ramiart.admin.tuition.api.TuitionBillingController");
        } catch (ClassNotFoundException exception) {
            controller = null;
        }
        assertThat(controller).as("MGT-TUITION-BILLING-GENERATE controller").isNotNull();
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition-billings");
        assertThat(java.util.Arrays.stream(controller.getDeclaredMethods()).map(method -> method.getAnnotation(GetMapping.class))
                .filter(java.util.Objects::nonNull).map(mapping -> mapping.value().length == 0 ? "" : mapping.value()[0]).toList())
                .contains("", "/{billingId}");
    }

    @Test
    @DisplayName("MGT-TUITION-BILLING-GENERATE month preview route")
    void tuitionBillingPreviewControllerUsesTheDocumentedRoute() throws Exception {
        Class<?> controller=Class.forName("com.ramiart.admin.tuition.api.TuitionBillingPreviewController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition-billing-previews");
        assertThat(java.util.Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(GetMapping.class))
                .filter(java.util.Objects::nonNull).flatMap(mapping->java.util.Arrays.stream(mapping.value())).toList())
                .containsExactly("/{yearMonth}");
    }

    @Test
    @DisplayName("MGT-TUITION-BILLING-GENERATE batch issue route")
    void tuitionBillingBatchControllerIsPresent() throws Exception {
        Class<?> controller=Class.forName("com.ramiart.admin.tuition.api.TuitionBillingBatchController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition-billings");
        assertThat(java.util.Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull).flatMap(mapping->java.util.Arrays.stream(mapping.value())).toList())
                .containsExactly("/batches");
    }
}
