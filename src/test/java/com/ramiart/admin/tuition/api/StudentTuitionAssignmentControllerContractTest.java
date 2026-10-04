package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class StudentTuitionAssignmentControllerContractTest {
    @Test void assignmentRoutesMatchContract() throws Exception {
        Class<?> controller=Class.forName("com.ramiart.admin.tuition.api.StudentTuitionAssignmentController");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m->m.getAnnotation(GetMapping.class)).filter(Objects::nonNull).flatMap(m->Arrays.stream(m.value())).toList())
                .containsExactlyInAnyOrder("/api/admin/students/{studentId}/tuition-assignments","/api/admin/students/{studentId}/tuition-assignment-candidates");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m->m.getAnnotation(PostMapping.class)).filter(Objects::nonNull).flatMap(m->Arrays.stream(m.value())).toList())
                .containsExactly("/api/admin/students/{studentId}/tuition-assignments");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m->m.getAnnotation(PutMapping.class)).filter(Objects::nonNull).flatMap(m->Arrays.stream(m.value())).toList())
                .containsExactly("/api/admin/student-tuition-assignments/{assignmentId}");
    }
}
