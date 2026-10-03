package com.ramiart.admin.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rami.artstudio.RamiArtBackendApplication;
import com.rami.artstudio.auth.dto.AuthDtos;
import com.ramiart.admin.auth.application.AuthSessionService.CurrentContext;
import com.ramiart.admin.auth.application.AuthSessionService.SessionContext;
import com.ramiart.admin.auth.application.AuthSessionService.UserContext;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

@JsonTest(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
@ContextConfiguration(classes = RamiArtBackendApplication.class)
@Import(AdminJacksonConfiguration.class)
class AdminJacksonConfigurationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managementApiRecordsUseCamelCaseUnderGlobalSnakeCasePolicy() throws Exception {
        var context = new CurrentContext(
                new UserContext(UUID.randomUUID(), "Studio Admin", "OWNER", List.of("DASHBOARD_READ"), true),
                new SessionContext(Instant.parse("2030-01-01T00:00:00Z"),
                        Instant.parse("2029-12-31T23:00:00Z"), Instant.parse("2029-12-31T22:55:00Z")),
                "/admin/settings/password?required=true");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(context));

        assertThat(json.has("firstAllowedPath")).isTrue();
        assertThat(json.has("first_allowed_path")).isFalse();
        assertThat(json.path("user").has("displayName")).isTrue();
        assertThat(json.path("user").has("passwordMustChange")).isTrue();
        assertThat(json.path("session").has("idleExpiresAt")).isTrue();
    }

    @Test
    void legacyApiRecordsKeepGlobalSnakeCasePolicy() throws Exception {
        var response = new AuthDtos.LoginResponse(
                "access-token", "refresh-token", 300,
                new AuthDtos.AdminSummary("admin-id", "Studio Admin", "OWNER", "owner@example.test"));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

        assertThat(json.has("access_token")).isTrue();
        assertThat(json.has("refresh_token")).isTrue();
        assertThat(json.path("admin").has("email")).isTrue();
        assertThat(json.has("accessToken")).isFalse();
    }

    @Test
    void managementRequestsAndValidationDetailsKeepCamelCase() throws Exception {
        StaffWrite staff = objectMapper.readValue("""
                {"staffCode":"TEACHER_JSON","displayName":"Local Teacher","hiredOn":"2026-09-01"}
                """, StaffWrite.class);
        assertThat(staff.staffCode()).isEqualTo("TEACHER_JSON");
        assertThat(staff.displayName()).isEqualTo("Local Teacher");
        assertThat(staff.hiredOn()).hasToString("2026-09-01");
        var error = ApiEnvelope.failure("VALIDATION_ERROR", "Invalid input",
                List.of(new ApiEnvelope.FieldError("staffCode", "REQUIRED", "Required")), "req_json_test");
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(error));
        assertThat(json.path("requestId").asText()).isEqualTo("req_json_test");
        assertThat(json.path("error").path("fieldErrors").isArray()).isTrue();
    }
}
