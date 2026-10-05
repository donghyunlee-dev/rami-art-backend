package com.ramiart.admin.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationTemplateRendererTest {
    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    @Test
    void rendersOnlySupportedVariables() {
        assertThat(renderer.render("{{studentName}}님, {{billingMonth}} 안내", Map.of("studentName", "민지", "billingMonth", "10월")))
                .isEqualTo("민지님, 10월 안내");
    }

    @Test
    void rejectsUnknownOrUnboundVariables() {
        assertThatThrownBy(() -> renderer.render("{{phone}}", Map.of()))
                .isInstanceOf(NotificationException.class)
                .hasMessage("NOTIFICATION_TEMPLATE_INVALID");
    }

    @Test
    void rejectsOversizedRenderedText() {
        assertThatThrownBy(() -> renderer.render("x".repeat(4001), Map.of()))
                .isInstanceOf(NotificationException.class)
                .hasMessage("NOTIFICATION_TEMPLATE_INVALID");
    }
}
