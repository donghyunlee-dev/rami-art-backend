package com.ramiart.admin.lessonplan.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class LessonPlanControllerContractTest {
    @Test
    void lessonPlanRoutesMatchManagementContract() {
        Class<?> controller = LessonPlanController.class;
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/lesson-plans");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method -> method.getAnnotation(GetMapping.class))
                .filter(Objects::nonNull).flatMap(mapping -> Arrays.stream(mapping.value())).toList()).isEmpty();
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method -> method.getAnnotation(PostMapping.class))
                .filter(Objects::nonNull).flatMap(mapping -> Arrays.stream(mapping.value())).toList())
                .containsExactlyInAnyOrder("/draft", "/preview", "/draft/{id}/publish");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(method -> method.getAnnotation(PutMapping.class))
                .filter(Objects::nonNull).flatMap(mapping -> Arrays.stream(mapping.value())).toList())
                .containsExactly("/draft/{id}");
    }
}
