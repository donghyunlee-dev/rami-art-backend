package com.ramiart.admin.lessonlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class LessonLogControllerContractTest {
    @Test
    void exposesTheDocumentedLessonLogRoutes() throws Exception {
        Class<?> controller = Class.forName("com.ramiart.admin.lessonlog.api.LessonLogController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/lesson-logs");

        assertThat(mapping(controller, GetMapping.class)).contains("/session/{sessionId}");
        assertThat(mapping(controller, PostMapping.class)).contains(
                "/session/{sessionId}/draft", "/{logId}/finalize", "/{logId}/amendments");
        assertThat(mapping(controller, PutMapping.class)).containsExactly("/{logId}");
    }

    private static java.util.List<String> mapping(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotationType) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(annotationType))
                .map(LessonLogControllerContractTest::path)
                .toList();
    }

    private static String path(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) return method.getAnnotation(GetMapping.class).value()[0];
        if (method.isAnnotationPresent(PostMapping.class)) return method.getAnnotation(PostMapping.class).value()[0];
        return method.getAnnotation(PutMapping.class).value()[0];
    }
}
