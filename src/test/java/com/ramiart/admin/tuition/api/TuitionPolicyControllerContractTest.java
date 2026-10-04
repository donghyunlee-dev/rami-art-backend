package com.ramiart.admin.tuition.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TuitionPolicyControllerContractTest {
    @Test void policyRoutesMatchManagementContract() throws Exception {
        Class<?> controller = Class.forName("com.ramiart.admin.tuition.api.TuitionPolicyController");
        assertThat(controller.getAnnotation(RequestMapping.class).value()).containsExactly("/api/admin/tuition-policies");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m -> m.getAnnotation(GetMapping.class)).filter(Objects::nonNull).flatMap(m -> Arrays.stream(m.value())).toList())
                .containsExactlyInAnyOrder("/{year}", "/{year}/assignment-options");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m -> m.getAnnotation(PostMapping.class)).filter(Objects::nonNull).flatMap(m -> Arrays.stream(m.value())).toList())
                .containsExactlyInAnyOrder("/{year}/drafts", "/{year}/publications");
        assertThat(Arrays.stream(controller.getDeclaredMethods()).map(m -> m.getAnnotation(PutMapping.class)).filter(Objects::nonNull).flatMap(m -> Arrays.stream(m.value())).toList())
                .containsExactly("/{year}/drafts/{draftId}");
    }
}
