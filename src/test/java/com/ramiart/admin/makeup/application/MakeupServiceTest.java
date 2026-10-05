package com.ramiart.admin.makeup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuditRecorder;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class MakeupServiceTest {
    private final MakeupRepository repository = mock(MakeupRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final MakeupService service = new MakeupService(repository, audit, mock(com.ramiart.admin.auth.application.AdminReauthenticationService.class));

    @Test
    void listRequiresMakeupReadPermissionAndUsesValidatedFilters() {
        UUID studentId = UUID.randomUUID();
        var expected = new MakeupModels.CasePage(List.of(), 0, 20, 0);
        when(repository.list("AVAILABLE", null, null, studentId, 0, 20)).thenReturn(expected);
        var reader = new TestingAuthenticationToken(UUID.randomUUID().toString(), "", "MAKEUP_READ");
        var actual = service.list("AVAILABLE", null, null, studentId, 0, 20, reader);
        assertThat(actual).isSameAs(expected);
        verify(repository).list("AVAILABLE", null, null, studentId, 0, 20);
        assertThatThrownBy(() -> service.list("UNKNOWN", null, null, null, 0, 20, reader))
                .isInstanceOf(MakeupService.MakeupException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.list(null, null, null, null, 0, 20,
                new TestingAuthenticationToken(UUID.randomUUID().toString(), "", "STUDENT_READ")))
                .isInstanceOf(MakeupService.MakeupException.class).extracting("code").isEqualTo("MAKEUP_READ_DENIED");
    }

    @Test
    void candidateRangeCannotExceedTheContractWindow() {
        UUID caseId = UUID.randomUUID();
        var reader = new TestingAuthenticationToken(UUID.randomUUID().toString(), "", "MAKEUP_READ");
        assertThatThrownBy(() -> service.candidates(caseId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(32), reader))
                .isInstanceOf(MakeupService.MakeupException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
    }
}
