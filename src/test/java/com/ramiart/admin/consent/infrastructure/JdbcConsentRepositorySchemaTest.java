package com.ramiart.admin.consent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcConsentRepositorySchemaTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void optionalTableChecksUseTheConsolidatedSchema() {
        NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(namedJdbc.getJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(namedJdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

        new JdbcConsentRepository(namedJdbc).studentConsents(UUID.randomUUID());

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(sql.capture(), eq(Boolean.class));
        assertThat(sql.getAllValues()).containsExactly(
                "select to_regclass('rami_art_studio.gallery_artwork') is not null",
                "select to_regclass('rami_art_studio.notification_message') is not null");
    }
}
