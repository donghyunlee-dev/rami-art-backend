package com.ramiart.admin.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.finance.application.FinancialEntryModels.EntryPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class FinancialEntryServiceTest {
    private final FinancialEntryRepository repository = mock(FinancialEntryRepository.class);
    private final FinancialEntryService service = new FinancialEntryService(repository, mock(AuditRecorder.class),
            Clock.fixed(Instant.parse("2026-10-05T02:00:00Z"), ZoneId.of("UTC")));

    @Test
    void listRequiresFinanceReadAndUsesStudioMonthDefaults() {
        var reader = auth("FINANCE_READ");
        EntryPage expected = new EntryPage(List.of(), new FinancialEntryModels.Page(0,20,0,0),
                new FinancialEntryModels.Totals(0,0,0,0), new FinancialEntryModels.Applied(
                        java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-31"),List.of("CONFIRMED")));
        when(repository.list(expected.applied().from(),expected.applied().to(),List.of(),List.of(),List.of(),List.of("CONFIRMED"),null,null,0,20)).thenReturn(expected);
        assertThat(service.list(null,null,null,null,null,null,null,null,0,20,reader)).isSameAs(expected);
        verify(repository).list(expected.applied().from(),expected.applied().to(),List.of(),List.of(),List.of(),List.of("CONFIRMED"),null,null,0,20);
        assertThatThrownBy(() -> service.list(null,null,null,null,null,null,null,null,0,20,auth("STUDENT_READ")))
                .isInstanceOf(FinancialEntryService.FinancialEntryException.class)
                .hasMessage("FINANCE_READ_DENIED");
    }

    @Test
    void listRejectsInvalidStatusPageAndKeywordBeforeQuery() {
        var reader = auth("FINANCE_READ");
        assertThatThrownBy(() -> service.list(null,null,null,null,null,List.of("UNKNOWN"),null,null,0,20,reader))
                .hasMessage("FINANCIAL_ENTRY_QUERY_INVALID");
        assertThatThrownBy(() -> service.list(null,null,null,null,null,null,"a",null,0,20,reader))
                .hasMessage("FINANCIAL_ENTRY_QUERY_INVALID");
        assertThatThrownBy(() -> service.list(null,null,null,null,null,null,null,null,0,30,reader))
                .hasMessage("FINANCIAL_ENTRY_QUERY_INVALID");
    }

    @Test
    void listCanNarrowToAnExactSourceTransaction() {
        var reader = auth("FINANCE_READ");
        UUID entryId = UUID.randomUUID();
        EntryPage expected = new EntryPage(List.of(), new FinancialEntryModels.Page(0,20,0,0),
                new FinancialEntryModels.Totals(0,0,0,0), new FinancialEntryModels.Applied(
                        java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-31"),List.of("CONFIRMED")));
        when(repository.list(expected.applied().from(),expected.applied().to(),List.of(),List.of(),List.of(),
                List.of("CONFIRMED"),null,entryId,0,20)).thenReturn(expected);

        assertThat(service.list(null,null,null,null,null,null,null,entryId,0,20,reader)).isSameAs(expected);
        verify(repository).list(expected.applied().from(),expected.applied().to(),List.of(),List.of(),List.of(),
                List.of("CONFIRMED"),null,entryId,0,20);
    }

    private static TestingAuthenticationToken auth(String permission) {
        return new TestingAuthenticationToken(UUID.randomUUID().toString(), "", permission);
    }
}
