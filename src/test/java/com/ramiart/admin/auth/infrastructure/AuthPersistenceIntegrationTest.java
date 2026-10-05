package com.ramiart.admin.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.rami.artstudio.RamiArtBackendApplication;
import com.rami.artstudio.support.LegacyFlywayTestBootstrap;
import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.AuthSessionService;
import com.ramiart.admin.auth.application.AuthSessionService.IssuedSession;
import com.ramiart.admin.auth.application.PasswordChangeService;
import com.ramiart.admin.auth.application.PasswordVerifier;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.datatransfer.application.DataTransferStorage;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = RamiArtBackendApplication.class,
        properties = {"spring.flyway.enabled=true", "spring.jackson.property-naming-strategy=SNAKE_CASE"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthPersistenceIntegrationTest {

    @MockitoBean
    private DataTransferStorage dataTransferStorage;

    private static final String OWNER_EMAIL = "owner@rami.local";
    private static final String TEMPORARY_PASSWORD = "LocalOnly!Change123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private PasswordChangeService passwordChangeService;

    @Autowired
    private PasswordVerifier passwordVerifier;

    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    @Test
    void httpSecurityEnforcesSessionOriginPasswordChangeAndLogout() throws Exception {
        assertThat(http("GET", "/api/admin/operations/probe", null, null, null).statusCode()).isEqualTo(401);
        var deniedOrigin = http("POST", "/api/admin/auth/sessions", null, "https://untrusted.example", "{}");
        assertThat(deniedOrigin.statusCode()).isEqualTo(403);
        assertThat(deniedOrigin.body()).contains("ORIGIN_NOT_ALLOWED");

        var loginResponse = http("POST", "/api/admin/auth/sessions", null, "http://localhost:3000",
                "{\"email\":\"owner@rami.local\",\"password\":\"LocalOnly!Change123\"}");
        assertThat(loginResponse.statusCode()).isEqualTo(201);
        String setCookie = loginResponse.headers().firstValue("set-cookie").orElseThrow();
        assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Expires=")
                .doesNotContain("Domain=");
        String cookie = setCookie.split(";", 2)[0];
        assertThat(http("GET", "/api/admin/auth/sessions/current", cookie, null, null).statusCode()).isEqualTo(200);
        var forced = http("GET", "/api/admin/operations/probe", cookie, null, null);
        assertThat(forced.statusCode()).isEqualTo(403);
        assertThat(forced.body()).contains("PASSWORD_CHANGE_REQUIRED");

        var changed = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"ChangedPassword!456\"}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        var unregistered = http("GET", "/api/admin/operations/probe", cookie, null, null);
        assertThat(unregistered.statusCode()).isEqualTo(403);
        assertThat(unregistered.body()).contains("ADMIN_ACCESS_DENIED");
        // Authentication must not leak from the previous request or issue a servlet session.
        assertThat(http("GET", "/api/admin/operations/probe", null, null, null).statusCode()).isEqualTo(401);
        var logout = http("DELETE", "/api/admin/auth/sessions/current", cookie, "http://localhost:3000", null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("set-cookie").orElseThrow()).contains("Max-Age=0");
        var revoked = http("GET", "/api/admin/auth/sessions/current", cookie, null, null);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(revoked.body()).contains("SESSION_REVOKED");
    }

    private HttpResponse<String> http(String method, String path, String cookie, String origin, String body)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) builder.header("Cookie", cookie);
        if (origin != null) builder.header("Origin", origin);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AuthPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach
    void resetDatabase() throws SQLException, IOException {
        jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("drop schema if exists public cascade");
            statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");
            statement.execute("create schema extensions");
        }
        try (var migrations = Files.list(projectRoot().resolve("supabase/migrations"))) {
            for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, migration);
            }
        }
        PostgresScriptRunner.execute(dataSource, projectRoot().resolve("supabase/seed.sql"));
    }

    @AfterAll
    void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Test
    void loginTransactionsPersistSessionsAuditsAndFailedAttempts() {
        IssuedSession issued = login("req_login_success");

        assertThat(count("admin_session", "revoked_at is null")).isOne();
        assertThat(count("audit_log", "action = 'ADMIN_LOGIN' and result = 'SUCCESS'")).isOne();
        assertThat(issued.passwordMustChange()).isTrue();
        assertThat(authSessionService.current(issued.rawToken()).user().permissions())
                .contains("DASHBOARD_READ", "ADMIN_ACCOUNT_WRITE", "AUDIT_READ");

        assertThatThrownBy(() -> authSessionService.login(
                OWNER_EMAIL,
                "incorrect-password",
                null,
                metadata("req_login_failure")))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("AUTHENTICATION_FAILED");

        assertThat(jdbcTemplate.queryForObject(
                "select failed_login_count from admin_user where email = ?",
                Integer.class,
                OWNER_EMAIL)).isEqualTo(1);
        assertThat(count("audit_log", "action = 'ADMIN_LOGIN' and result = 'FAILURE'")).isOne();
    }

    @Test
    void seededRolesExposeOnlyTheirAssignedPermissions() {
        Map<String, RoleExpectation> expectations = Map.of(
                "OPERATOR", new RoleExpectation(
                        List.of("STUDENT_WRITE", "ATTENDANCE_CLOSE", "INQUIRY_WRITE"),
                        List.of("FINANCE_READ", "GALLERY_WRITE", "ADMIN_ACCOUNT_READ")),
                "CONTENT", new RoleExpectation(
                        List.of("CONTENT_PROFILE_WRITE", "GALLERY_PUBLISH", "BLOG_PUBLISH"),
                        List.of("STUDENT_WRITE", "FINANCE_READ", "ADMIN_ACCOUNT_READ")),
                "FINANCE", new RoleExpectation(
                        List.of("TUITION_PAYMENT_WRITE", "FINANCE_WRITE", "DATA_TRANSFER_EXPORT"),
                        List.of("STUDENT_WRITE", "GALLERY_WRITE", "ADMIN_ACCOUNT_READ")));

        expectations.forEach((role, expectation) -> {
            String email = role.toLowerCase() + "@rami.local";
            createRoleUser(role, email);
            IssuedSession issued = authSessionService.login(
                    email, TEMPORARY_PASSWORD, null, metadata("req_role_" + role.toLowerCase()));
            var context = authSessionService.current(issued.rawToken());

            assertThat(context.user().role()).isEqualTo(role);
            assertThat(context.user().permissions()).containsAll(expectation.allowed());
            assertThat(context.user().permissions()).doesNotContainAnyElementsOf(expectation.denied());
            assertThat(context.firstAllowedPath()).isEqualTo("/admin/dashboard");
        });
    }

    @Test
    void courseHttpEndpointsEnforceReadAndWritePermissions() throws Exception {
        createRoleUser("OPERATOR", "operator@rami.local");
        createRoleUser("CONTENT", "content@rami.local");
        String operatorCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "operator@rami.local", TEMPORARY_PASSWORD, null, metadata("req_operator_course")).rawToken();
        String contentCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "content@rami.local", TEMPORARY_PASSWORD, null, metadata("req_content_course")).rawToken();

        assertThat(http("GET", "/api/admin/courses", operatorCookie, null, null).statusCode()).isEqualTo(200);
        var created = httpWithIdempotency("POST", "/api/admin/courses", operatorCookie, """
                {"code":"PAINTING","name":"회화","displayOrder":20,"active":true}
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("PAINTING", "회화");
        assertThat(http("GET", "/api/admin/courses", contentCookie, null, null).statusCode()).isEqualTo(403);
        assertThat(httpWithIdempotency("POST", "/api/admin/courses", contentCookie, """
                {"code":"DENIED","name":"차단","displayOrder":21,"active":true}
                """).statusCode()).isEqualTo(403);
        String missingGroup = "/api/admin/class-groups/30000000-0000-0000-0000-000000000099?version=0";
        assertThat(httpWithIdempotency("DELETE", missingGroup, operatorCookie, "").statusCode()).isEqualTo(404);
        assertThat(httpWithIdempotency("DELETE", missingGroup, contentCookie, "").statusCode()).isEqualTo(403);
    }

    @Test
    void makeupHttpEndpointsEnforceSessionAndRolePermissions() throws Exception {
        createRoleUser("OPERATOR", "makeup-operator@rami.local");
        createRoleUser("CONTENT", "makeup-content@rami.local");
        String operatorCookie="__Host-rami_admin_session="+authSessionService.login("makeup-operator@rami.local",TEMPORARY_PASSWORD,null,metadata("req_makeup_operator")).rawToken();
        String contentCookie="__Host-rami_admin_session="+authSessionService.login("makeup-content@rami.local",TEMPORARY_PASSWORD,null,metadata("req_makeup_content")).rawToken();

        var list=http("GET","/api/admin/makeups?status=AVAILABLE",operatorCookie,null,null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        var denied=httpWithIdempotency("POST","/api/admin/makeups/00000000-0000-0000-0000-000000000099/reservations",contentCookie,
                "{\"sessionId\":\"00000000-0000-0000-0000-000000000100\",\"caseVersion\":0,\"sessionVersion\":0}");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("MAKEUP_WRITE_DENIED");
        assertThat(http("GET","/api/admin/makeups",contentCookie,null,null).body()).contains("MAKEUP_READ_DENIED");
        var badReauthentication=http("POST","/api/admin/auth/reauthentication",operatorCookie,"http://localhost:3000",
                "{\"password\":\"wrong-password\",\"purpose\":\"MAKEUP_EXTENSION\"}");
        assertThat(badReauthentication.statusCode()).isEqualTo(403);
        assertThat(badReauthentication.body()).contains("REAUTHENTICATION_FAILED").doesNotContain("reauthToken");
        assertThat(http("GET","/api/admin/makeups",null,null,null).statusCode()).isEqualTo(401);
    }

    @Test
    void financialEntryHttpEndpointsFilterWriteReplayCancelAndEnforcePermissions() throws Exception {
        createRoleUser("FINANCE", "finance-ledger@rami.local");
        createRoleUser("CONTENT", "content-ledger@rami.local");
        String financeCookie="__Host-rami_admin_session="+authSessionService.login("finance-ledger@rami.local",TEMPORARY_PASSWORD,null,metadata("req_finance_ledger")).rawToken();
        String contentCookie="__Host-rami_admin_session="+authSessionService.login("content-ledger@rami.local",TEMPORARY_PASSWORD,null,metadata("req_content_ledger")).rawToken();
        var options=http("GET","/api/admin/finance-ledger-options",financeCookie,null,null);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(options.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(options.body()).contains("기본 은행","OTHER_INCOME");
        assertThat(http("GET","/api/admin/finance-ledger-options",contentCookie,null,null).statusCode()).isEqualTo(403);
        assertThat(http("GET","/api/admin/finance-settlements/options",financeCookie,null,null).statusCode()).isEqualTo(200);
        assertThat(http("GET","/api/admin/financial-entries",null,null,null).statusCode()).isEqualTo(401);

        UUID account=jdbcTemplate.queryForObject("select id from finance_account where active order by display_order limit 1",UUID.class);
        String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        String body="{\"transactionDate\":\""+today+"\",\"type\":\"INCOME\",\"accountId\":\""+account+"\",\"categoryCode\":\"OTHER_INCOME\",\"description\":\"통합 원장 검증\",\"amount\":12000}";
        UUID createKey=UUID.randomUUID();
        var created=httpWithKey("POST","/api/admin/financial-entries",financeCookie,body,createKey);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("MANUAL","통합 원장 검증");
        var settlement=http("GET","/api/admin/finance-settlements?from="+today+"&to="+today,financeCookie,null,null);
        assertThat(settlement.statusCode()).isEqualTo(200);
        assertThat(settlement.body()).contains("\"income\":12000","\"entryCount\":1","statuses=CONFIRMED");
        var replay=httpWithKey("POST","/api/admin/financial-entries",financeCookie,body,createKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("통합 원장 검증");
        UUID entry=jdbcTemplate.queryForObject("select id from financial_entry where description='통합 원장 검증'",UUID.class);
        String cancelBody="{\"reason\":\"통합 테스트 정리\",\"version\":0}";
        UUID cancelKey=UUID.randomUUID();
        var cancelled=httpWithKey("POST","/api/admin/financial-entries/"+entry+"/cancellations",financeCookie,cancelBody,cancelKey);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select status from financial_entry where id=?",String.class,entry)).isEqualTo("CANCELLED");
        assertThat(httpWithKey("POST","/api/admin/financial-entries",contentCookie,body,UUID.randomUUID()).statusCode()).isEqualTo(403);
    }

    @Test
    void financialImportHttpEndpointsRequireReadAndImportPermissions() throws Exception {
        createRoleUser("FINANCE","finance-import@rami.local");
        createRoleUser("CONTENT","content-import@rami.local");
        String financeCookie="__Host-rami_admin_session="+authSessionService.login("finance-import@rami.local",TEMPORARY_PASSWORD,null,metadata("req_finance_import")).rawToken();
        String contentCookie="__Host-rami_admin_session="+authSessionService.login("content-import@rami.local",TEMPORARY_PASSWORD,null,metadata("req_content_import")).rawToken();
        String batch="00000000-0000-0000-0000-000000000099";
        assertThat(http("GET","/api/admin/financial-imports/"+batch,financeCookie,null,null).statusCode()).isEqualTo(404);
        assertThat(http("GET","/api/admin/financial-imports/"+batch,contentCookie,null,null).statusCode()).isEqualTo(403);
        assertThat(http("GET","/api/admin/financial-imports/"+batch,null,null,null).statusCode()).isEqualTo(401);
        assertThat(httpWithIdempotency("POST","/api/admin/financial-imports",contentCookie,"{}")
                .statusCode()).isEqualTo(403);
    }

    @Test
    void consentHttpEndpointsRequireReadAndWritePermissions() throws Exception {
        assertThat(jdbcTemplate.queryForObject("select to_regclass('public.consent_policy')::text",String.class)).isEqualTo("consent_policy");
        createRoleUser("OWNER","consent-owner@rami.local");
        createRoleUser("CONTENT","consent-content@rami.local");
        String ownerCookie="__Host-rami_admin_session="+authSessionService.login("consent-owner@rami.local",TEMPORARY_PASSWORD,null,metadata("req_consent_owner")).rawToken();
        String contentCookie="__Host-rami_admin_session="+authSessionService.login("consent-content@rami.local",TEMPORARY_PASSWORD,null,metadata("req_consent_content")).rawToken();
        HttpResponse<String> policies=http("GET","/api/admin/consent-policies",ownerCookie,null,null);
        assertThat(policies.statusCode()).as(policies.body()).isEqualTo(200);
        assertThat(http("GET","/api/admin/consent-policies",ownerCookie,null,null).headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(http("GET","/api/admin/consent-policies",contentCookie,null,null).statusCode()).isEqualTo(403);
        assertThat(http("GET","/api/admin/consent-policies",null,null,null).statusCode()).isEqualTo(401);
        assertThat(httpWithIdempotency("POST","/api/admin/consent-policies/OPTIONAL_NOTIFICATION/draft",contentCookie,"{}").statusCode()).isEqualTo(403);
    }

    @Test
    void consentPolicyDraftUpdateAndPublishPreserveRevisionState() throws Exception {
        createRoleUser("OWNER","consent-policy-owner@rami.local");
        String cookie="__Host-rami_admin_session="+authSessionService.login("consent-policy-owner@rami.local",TEMPORARY_PASSWORD,null,metadata("req_consent_policy_owner")).rawToken();
        String draftBody="{\"title\":\"선택 안내 수신 동의\",\"body\":\"선택 안내 수신에 동의합니다.\",\"required\":false,\"valid_days\":365,\"evidence_required\":false}";
        HttpResponse<String> draft=httpWithIdempotency("POST","/api/admin/consent-policies/OPTIONAL_NOTIFICATION/draft",cookie,draftBody);
        assertThat(draft.statusCode()).isEqualTo(201);
        String id=jdbcTemplate.queryForObject("select id::text from consent_policy where type='OPTIONAL_NOTIFICATION' and status='DRAFT'",String.class);
        String updateBody="{\"title\":\"선택 안내 수신 동의 v2\",\"body\":\"선택 안내 수신에 동의합니다.\",\"required\":false,\"valid_days\":365,\"evidence_required\":false,\"version\":0}";
        assertThat(http("PUT","/api/admin/consent-policies/draft/"+id,cookie,"http://localhost:3000",updateBody).statusCode()).isEqualTo(200);
        assertThat(http("POST","/api/admin/consent-policies/draft/"+id+"/publish",cookie,"http://localhost:3000","{\"version\":1}").statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("select status,title,revision from consent_policy where id=?::uuid",id))
                .containsEntry("status","PUBLISHED").containsEntry("title","선택 안내 수신 동의 v2").containsEntry("revision",1);
        assertThat(http("GET","/api/admin/consent-policies?type=OPTIONAL_NOTIFICATION",cookie,null,null).body()).contains("PUBLISHED");
    }

    @Test
    void consentCollectionAndRevocationAreVersionedAndHideChoices() throws Exception {
        createRoleUser("OWNER","consent-flow-owner@rami.local");
        String cookie="__Host-rami_admin_session="+authSessionService.login("consent-flow-owner@rami.local",TEMPORARY_PASSWORD,null,metadata("req_consent_flow_owner")).rawToken();
        UUID actor=jdbcTemplate.queryForObject("select id from admin_user where email='consent-flow-owner@rami.local'",UUID.class);
        UUID student=UUID.randomUUID(),guardian=UUID.randomUUID();
        jdbcTemplate.update("insert into student(id,student_name,student_name_search,status,joined_at,created_by,updated_by) values(?, '동의 검증 원생','consentstudent','ACTIVE',current_date,?,?)",student,actor,actor);
        jdbcTemplate.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,primary_contact) values(?,?, '보호자','MOTHER',decode('01','hex'),repeat('a',64),'1234',true)",guardian,student);
        HttpResponse<String> policy=httpWithIdempotency("POST","/api/admin/consent-policies/OPTIONAL_NOTIFICATION/draft",cookie,
                "{\"title\":\"선택 안내\",\"body\":\"선택 안내 수신에 동의합니다.\",\"required\":false,\"valid_days\":30,\"evidence_required\":false}");
        assertThat(policy.statusCode()).isEqualTo(201);
        String policyId=jdbcTemplate.queryForObject("select id::text from consent_policy where type='OPTIONAL_NOTIFICATION' and status='DRAFT'",String.class);
        assertThat(http("POST","/api/admin/consent-policies/draft/"+policyId+"/publish",cookie,"http://localhost:3000","{\"version\":0}").statusCode()).isEqualTo(200);
        String payload="{\"policyId\":\""+policyId+"\",\"guardianContactId\":\""+guardian+"\",\"method\":\"DIGITAL\"}";
        HttpResponse<String> collected=httpWithIdempotency("POST","/api/admin/students/"+student+"/consents",cookie,payload);
        assertThat(collected.statusCode()).as(collected.body()).isEqualTo(201);
        UUID consent=UUID.fromString(jdbcTemplate.queryForObject("select id::text from student_consent where student_id=?",String.class,student));
        assertThat(http("GET","/api/admin/students/"+student+"/consents",cookie,null,null).body()).contains("ACTIVE","queuedOptionalNotificationCount");
        assertThat(httpWithIdempotency("POST","/api/admin/student-consents/"+consent+"/revocation",cookie,"{\"version\":0,\"reason\":\"보호자 요청\"}").statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select status from student_consent where id=?",String.class,consent)).isEqualTo("REVOKED");
    }

    @Test
    void notificationRoutesRequirePermissionsAndManualQueueStoresEncryptedSnapshot() throws Exception {
        assertThat(jdbcTemplate.queryForObject("select to_regclass('public.notification_message')::text",String.class)).isEqualTo("notification_message");
        createRoleUser("OWNER","notification-owner@rami.local");
        createRoleUser("CONTENT","notification-content@rami.local");
        String ownerCookie="__Host-rami_admin_session="+authSessionService.login("notification-owner@rami.local",TEMPORARY_PASSWORD,null,metadata("req_notification_owner")).rawToken();
        String contentCookie="__Host-rami_admin_session="+authSessionService.login("notification-content@rami.local",TEMPORARY_PASSWORD,null,metadata("req_notification_content")).rawToken();
        assertThat(http("GET","/api/admin/notifications",ownerCookie,null,null).statusCode()).isEqualTo(200);
        assertThat(http("GET","/api/admin/notifications",ownerCookie,null,null).headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(http("GET","/api/admin/notifications",contentCookie,null,null).statusCode()).isEqualTo(403);
        assertThat(http("GET","/api/admin/notifications",null,null,null).statusCode()).isEqualTo(401);
        String unsupported="{\"type\":\"GENERAL\",\"channel\":\"SMS\",\"recipientFilter\":{},\"bodyTemplate\":\"안내\",\"scheduledAt\":\""+OffsetDateTime.now().plusMinutes(5).withNano(0)+"\"}";
        assertThat(http("POST","/api/admin/notifications/preview",ownerCookie,"http://localhost:3000",unsupported).statusCode()).isEqualTo(422);
        assertThat(http("POST","/api/admin/notifications/preview",contentCookie,"http://localhost:3000",unsupported).statusCode()).isEqualTo(403);

        UUID actor=jdbcTemplate.queryForObject("select id from admin_user where email='notification-owner@rami.local'",UUID.class);
        UUID student=UUID.randomUUID(),guardian=UUID.randomUUID();
        jdbcTemplate.update("insert into student(id,student_name,student_name_search,status,joined_at,created_by,updated_by) values(?, '알림 통합시험 원생','notificationstudent','ACTIVE',current_date,?,?)",student,actor,actor);
        jdbcTemplate.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,primary_contact) values(?,?, '보호자','MOTHER',decode('01','hex'),repeat('b',64),'9876',true)",guardian,student);
        String scheduled=OffsetDateTime.now().plusMinutes(10).withNano(0).toString();
        String draft="{\"type\":\"GENERAL\",\"channel\":\"MANUAL\",\"recipientFilter\":{\"studentIds\":[\""+student+"\"],\"classGroupIds\":[]},\"bodyTemplate\":\"{{studentName}}님 안내\",\"variables\":{},\"scheduledAt\":\""+scheduled+"\",\"optionalNotice\":false}";
        HttpResponse<String> preview=http("POST","/api/admin/notifications/preview",ownerCookie,"http://localhost:3000",draft);
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(preview.body()).contains("\"eligibleCount\":1","알••••");
        String token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview.body()).path("data").path("previewToken").asText();
        String queued=draft.substring(0,draft.length()-1)+",\"previewToken\":\""+token+"\"}";
        UUID key=UUID.randomUUID();HttpResponse<String> result=httpWithKey("POST","/api/admin/notifications",ownerCookie,queued,key);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        UUID batch=UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.body()).path("data").path("batchKey").asText());
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification_message where batch_key=? and status='QUEUED'",Integer.class,batch)).isOne();
        byte[] ciphertext=jdbcTemplate.queryForObject("select body_ciphertext from notification_message where batch_key=?",byte[].class,batch);
        assertThat(new String(ciphertext,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("알림 통합시험 원생");
        assertThat(httpWithKey("POST","/api/admin/notifications",ownerCookie,queued,key).body()).contains(batch.toString());
        UUID message=jdbcTemplate.queryForObject("select id from notification_message where batch_key=?",UUID.class,batch);
        jdbcTemplate.update("update notification_message set status='FAILED',attempt_count=1,last_error_code='PROVIDER_TEMPORARY',version=version+1 where id=?",message);
        UUID retryKey=UUID.randomUUID();HttpResponse<String> retry=httpWithKey("POST","/api/admin/notifications/"+message+"/retry",ownerCookie,"",retryKey);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("QUEUED");
        assertThat(httpWithKey("POST","/api/admin/notifications/"+message+"/retry",ownerCookie,"",retryKey).body()).contains("QUEUED");
        assertThat(http("POST","/api/admin/notifications/"+message+"/cancellation",ownerCookie,"http://localhost:3000","{\"version\":2,\"reason\":\"발송 내용 변경\"}").statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("CANCELLED");
    }

    @Test
    void studioProfileRevisionRoutesPublishOneConsistentPublicRevision() throws Exception {
        createRoleUser("OWNER", "studio-profile-owner@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "studio-profile-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_studio_profile_owner")).rawToken();
        UUID draftKey = UUID.randomUUID();
        HttpResponse<String> created = httpWithKey("POST", "/api/admin/studio-profile/drafts", cookie, "", draftKey);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID draftId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body())
                .path("data").path("draftId").asText());

        String content = """
                {"version":0,"studioName":"라미아트 테스트","phone":"+821012345678",
                 "email":"hello@rami-art.example","address":"서울시 강남구 예술로 10",
                 "addressDetail":"","latitude":37.123456,"longitude":127.123456,
                 "businessHours":[{"day":1,"closed":false,"open":"10:00","close":"19:00"},
                                   {"day":7,"closed":true,"open":null,"close":null}],
                 "closedDays":"","transitGuide":"2번 출구 도보 5분","parkingGuide":""}
                """;
        HttpResponse<String> saved = httpWithKey("PUT", "/api/admin/studio-profile/drafts/" + draftId,
                cookie, content, UUID.randomUUID());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).contains("\"version\":1", "\"addressDetail\":null", "\"closedDays\":null");
        HttpResponse<String> stalePreview = http("GET", "/api/admin/studio-profile/drafts/" + draftId + "/preview?version=0",
                cookie, null, null);
        assertThat(stalePreview.statusCode()).isEqualTo(409);
        HttpResponse<String> duplicateDay = httpWithKey("PUT", "/api/admin/studio-profile/drafts/" + draftId,
                cookie, content.replace("\"day\":7", "\"day\":1"), UUID.randomUUID());
        assertThat(duplicateDay.statusCode()).isEqualTo(422);
        HttpResponse<String> imageField = httpWithKey("PUT", "/api/admin/studio-profile/drafts/" + draftId,
                cookie, content.replace("\"parkingGuide\":\"\"", "\"parkingGuide\":\"\",\"imageAssetId\":\"" + UUID.randomUUID() + "\""),
                UUID.randomUUID());
        assertThat(imageField.statusCode()).isEqualTo(400);
        assertThat(http("GET", "/api/admin/studio-profile/drafts/" + draftId + "/preview?version=1",
                cookie, null, null).body()).contains("HOME", "CONTACT", "FOOTER");

        UUID publishKey = UUID.randomUUID();
        String publish = "{\"draftId\":\"" + draftId + "\",\"draftVersion\":1}";
        HttpResponse<String> published = httpWithKey("POST", "/api/admin/studio-profile/publications",
                cookie, publish, publishKey);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        HttpResponse<String> replay = httpWithKey("POST", "/api/admin/studio-profile/publications", cookie, publish, publishKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains(draftId.toString());
        HttpResponse<String> publicProfile = http("GET", "/api/public/studio-profile", null, null, null);
        assertThat(publicProfile.statusCode()).isEqualTo(200);
        assertThat(publicProfile.headers().firstValue("cache-control").orElseThrow()).contains("max-age=60", "public");
        assertThat(publicProfile.body()).contains("라미아트 테스트")
                .doesNotContain("DRAFT", "version", "createdBy", "draftId", "profileId");
    }

    @Test
    void directorProfileRevisionRoutesKeepHiddenCareersOutOfPublicRead() throws Exception {
        createRoleUser("OWNER", "director-profile-owner@rami.local");
        createRoleUser("OPERATOR", "director-profile-content@rami.local");
        String ownerCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "director-profile-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_director_profile_owner")).rawToken();
        String contentCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "director-profile-content@rami.local", TEMPORARY_PASSWORD, null, metadata("req_director_profile_content")).rawToken();
        assertThat(http("GET", "/api/admin/director-profile?mode=DRAFT", contentCookie, null, null).statusCode()).isEqualTo(403);
        HttpResponse<String> created = httpWithKey("POST", "/api/admin/director-profile/drafts", ownerCookie, "", UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID draftId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body())
                .path("data").path("draftId").asText());
        UUID visibleCareer = UUID.randomUUID();
        UUID hiddenCareer = UUID.randomUUID();
        String write = """
                {"version":0,"name":"이원장","title":"라미아트 원장",
                 "introduction":"아이의 시선을 존중합니다.","careers":[
                   {"id":"%s","period":"2018~현재","title":"아동미술 교육","displayOrder":0,"hidden":false},
                   {"id":"%s","period":null,"title":"비공개 경력","displayOrder":1,"hidden":true}]}
                """.formatted(visibleCareer, hiddenCareer);
        HttpResponse<String> saved = httpWithKey("PUT", "/api/admin/director-profile/drafts/" + draftId,
                ownerCookie, write, UUID.randomUUID());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).contains("\"version\":1", "비공개 경력");
        assertThat(http("GET", "/api/admin/director-profile/drafts/" + draftId + "/preview?version=1",
                ownerCookie, null, null).body()).contains("adminPreview", "publicProfile", "비공개 경력");
        String publication = "{\"draftId\":\"" + draftId + "\",\"draftVersion\":1}";
        UUID key = UUID.randomUUID();
        HttpResponse<String> published = httpWithKey("POST", "/api/admin/director-profile/publications",
                ownerCookie, publication, key);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(httpWithKey("POST", "/api/admin/director-profile/publications", ownerCookie, publication, key).statusCode())
                .isEqualTo(200);
        HttpResponse<String> publicProfile = http("GET", "/api/public/director-profile", null, null, null);
        assertThat(publicProfile.statusCode()).isEqualTo(200);
        assertThat(publicProfile.body()).contains("이원장", "아동미술 교육")
                .doesNotContain("비공개 경력", "hidden", "profileId", "draftId", "version");
    }

    @Test
    void classProgramRevisionRoutesPublishOnlyVisibleReadyMediaCards() throws Exception {
        createRoleUser("OWNER", "class-program-owner@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "class-program-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_class_program_owner")).rawToken();
        UUID actor = UUID.nameUUIDFromBytes("test-admin-OWNER".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID course = UUID.randomUUID();
        jdbcTemplate.update("insert into course(id,code,name,display_order,active,created_by,updated_by) values(?,?,?,0,true,?,?)",
                course, "ART_HTTP", "HTTP 미술 과정", actor, actor);
        HttpResponse<String> list = http("GET", "/api/admin/content/class-programs", cookie, null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        HttpResponse<String> created = httpWithKey("POST", "/api/admin/content/class-programs/" + course + "/drafts",
                cookie, "", UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID draft = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body()).path("data").path("draft").path("id").asText());
        UUID asset = UUID.fromString("" + jdbcTemplate.queryForObject("select id from media_asset where storage_key='seed/generic-brand-share.png'", UUID.class));
        String emptyDraft = "{\"version\":0,\"audienceLabel\":\"\",\"title\":\"\",\"description\":\"\",\"activities\":[\"\"],\"mediaAssetId\":null,\"altText\":\"\",\"visible\":false,\"displayOrder\":0}";
        HttpResponse<String> incomplete = httpWithKey("PUT", "/api/admin/content/class-programs/" + course + "/drafts/" + draft,
                cookie, emptyDraft, UUID.randomUUID());
        assertThat(incomplete.statusCode()).as(incomplete.body()).isEqualTo(200);
        assertThat(incomplete.body()).contains("\"version\":1");
        String save = "{\"version\":0,\"audienceLabel\":\"초등\",\"title\":\"색과 형태\",\"description\":\"표현 활동\",\"activities\":[\"관찰\",\"그리기\"],\"mediaAssetId\":\"" + asset + "\",\"altText\":\"작품 이미지\",\"visible\":true,\"displayOrder\":0}";
        save = save.replace("\"version\":0", "\"version\":1");
        HttpResponse<String> saved = httpWithKey("PUT", "/api/admin/content/class-programs/" + course + "/drafts/" + draft,
                cookie, save, UUID.randomUUID());
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select count(*) from media_asset_reference where owner_type='CLASS_PROGRAM' and owner_id=? and reference_state='DRAFT'", Integer.class, draft)).isEqualTo(1);
        String publication = "{\"draftId\":\"" + draft + "\",\"version\":2,\"changeSummary\":\"첫 발행\"}";
        UUID key = UUID.randomUUID();
        HttpResponse<String> published = httpWithKey("POST", "/api/admin/content/class-programs/" + course + "/publications", cookie, publication, key);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(httpWithKey("POST", "/api/admin/content/class-programs/" + course + "/publications", cookie, publication, key).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select reference_state from media_asset_reference where owner_type='CLASS_PROGRAM' and owner_id=?", String.class, draft)).isEqualTo("PUBLISHED");
        HttpResponse<String> publicRead = http("GET", "/api/public/class-programs", null, null, null);
        assertThat(publicRead.statusCode()).isEqualTo(200);
        assertThat(publicRead.body()).contains("색과 형태", "작품 이미지", "ART_HTTP").doesNotContain("DRAFT", "mediaAssetId", "version");
    }

    @Test
    void galleryArtworkPublicationRequiresActiveConsentAndRevocationHidesImmutableRevision() throws Exception {
        createRoleUser("OWNER", "gallery-owner@rami.local");
        createRoleUser("OPERATOR", "gallery-operator@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "gallery-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_gallery_owner")).rawToken();
        String operator = "__Host-rami_admin_session=" + authSessionService.login(
                "gallery-operator@rami.local", TEMPORARY_PASSWORD, null, metadata("req_gallery_operator")).rawToken();
        assertThat(http("GET", "/api/admin/gallery-artworks?page=0&size=20", operator, null, null).statusCode()).isEqualTo(403);
        UUID actor = jdbcTemplate.queryForObject("select id from admin_user where email='gallery-owner@rami.local'", UUID.class);
        UUID course = UUID.randomUUID(), student = UUID.randomUUID(), guardian = UUID.randomUUID(), policy = UUID.randomUUID(), consent = UUID.randomUUID();
        jdbcTemplate.update("insert into course(id,code,name,display_order,active,created_by,updated_by) values(?,?,?,0,true,?,?)",course,"ART_GAL","갤러리 과정",actor,actor);
        jdbcTemplate.update("insert into student(id,student_name,student_name_search,status,joined_at,created_by,updated_by) values(?, '김가람','김가람','ACTIVE',current_date,?,?)",student,actor,actor);
        jdbcTemplate.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,primary_contact) values(?,?, '보호자','MOTHER',decode('01','hex'),repeat('b',64),'5678',true)",guardian,student);
        jdbcTemplate.update("insert into consent_policy(id,type,revision,status,title,body,required,valid_days,evidence_required,version,created_by,published_by,published_at) values(?,'MEDIA_PUBLICATION',1,'PUBLISHED','작품 공개 동의','작품 공개를 동의합니다.',false,null,false,1,?,?,statement_timestamp())",policy,actor,actor);
        jdbcTemplate.update("insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,created_by) values(?,?,?,'MEDIA_PUBLICATION',?,'DIGITAL','ACTIVE',statement_timestamp(),?)",consent,student,policy,guardian,actor);
        UUID asset = jdbcTemplate.queryForObject("select id from media_asset where storage_key='seed/generic-brand-share.png'", UUID.class);
        String createBody = "{\"title\":\"푸른 물결\",\"courseId\":\""+course+"\",\"audienceLabel\":\"초등\",\"medium\":\"수채화\",\"description\":\"색의 흐름을 탐색한 작품입니다.\",\"mediaAssetId\":\""+asset+"\",\"altText\":\"푸른색 수채화 작품\",\"studentConsentId\":\""+consent+"\",\"consentExemptionReason\":null,\"visible\":true,\"featured\":false,\"featuredOrder\":null}";
        UUID createKey = UUID.randomUUID();
        HttpResponse<String> created = httpWithKey("POST", "/api/admin/gallery-artworks", cookie, createBody, createKey);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body());
        UUID artwork = UUID.fromString(json.path("data").path("artworkId").asText());
        UUID draft = UUID.fromString(json.path("data").path("draft").path("id").asText());
        String publishBody="{\"draftId\":\""+draft+"\",\"draftVersion\":0}";
        UUID publishKey=UUID.randomUUID();
        HttpResponse<String> published = httpWithKey("POST", "/api/admin/gallery-artworks/"+artwork+"/publications", cookie,
                publishBody, publishKey);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(201);
        assertThat(httpWithKey("POST", "/api/admin/gallery-artworks/"+artwork+"/publications", cookie,publishBody,publishKey).statusCode()).isEqualTo(200);
        HttpResponse<String> copiedDraft = httpWithKey("POST", "/api/admin/gallery-artworks/"+artwork+"/drafts", cookie, "{}", UUID.randomUUID());
        assertThat(copiedDraft.statusCode()).isEqualTo(201);
        HttpResponse<String> detail = http("GET", "/api/admin/gallery-artworks/"+artwork+"?mode=published", cookie, null, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"sourceStatus\":\"DRAFT\"", "\"draft\":{", "\"published\":{", "푸른 물결");
        assertThat(http("GET", "/api/admin/gallery-artworks?page=0&size=20&states=PUBLISHED_VISIBLE", cookie, null, null).body()).contains(artwork.toString(),"푸른 물결");
        HttpResponse<String> publicRead = http("GET", "/api/public/gallery-artworks?page=1&size=24", null, null, null);
        assertThat(publicRead.statusCode()).isEqualTo(200);
        assertThat(publicRead.body()).contains("푸른 물결", "ART_GAL").doesNotContain("studentConsentId", "studentLabel", "김가람");
        assertThat(http("GET", "/api/admin/student-consents?type=MEDIA_PUBLICATION&status=ACTIVE&keyword=김가람&page=0&size=10", cookie, null, null).body())
                .contains("김**", consent.toString()).doesNotContain("김가람");
        HttpResponse<String> revoked = httpWithIdempotency("POST", "/api/admin/student-consents/"+consent+"/revocation", cookie,
                "{\"version\":0,\"reason\":\"보호자 철회 요청\"}");
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select visible from gallery_artwork where id=?",Boolean.class,draft)).isTrue();
        assertThat(http("GET", "/api/public/gallery-artworks?page=1&size=24", null, null, null).body()).doesNotContain(artwork.toString(), "푸른 물결");
    }

    @Test
    void monthlyDashboardRequiresSessionAndReturnsMonthScopedReadOnlySummary() throws Exception {
        createRoleUser("OWNER", "monthly-dashboard-owner@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "monthly-dashboard-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_monthly_dashboard")).rawToken();
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).toString();

        HttpResponse<String> anonymous = http("GET", "/api/admin/dashboard/monthly?month=" + month, null, null, null);
        HttpResponse<String> authorized = http("GET", "/api/admin/dashboard/monthly?month=" + month, cookie, null, null);
        HttpResponse<String> invalidMonth = http("GET", "/api/admin/dashboard/monthly?month=not-a-month", cookie, null, null);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(authorized.statusCode()).as(authorized.body()).isEqualTo(200);
        assertThat(authorized.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(authorized.body()).contains("\"month\":\"" + month + "\"", "\"tuition\"", "\"attendance\"", "\"lessons\"", "\"enrollment\"", "\"capacity\"");
        assertThat(invalidMonth.statusCode()).isEqualTo(400);
        assertThat(invalidMonth.body()).contains("DASHBOARD_MONTH_NOT_SUPPORTED");
    }

    @Test
    void dataTransferTemplateIsVersionedCsvAndRequiresImportPermission() throws Exception {
        createRoleUser("OPERATOR", "data-transfer-template-operator@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-template-operator@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_template")).rawToken();

        HttpResponse<String> anonymous = http("GET", "/api/admin/data-transfer/templates/STUDENT", null, null, null);
        HttpResponse<String> allowed = http("GET", "/api/admin/data-transfer/templates/STUDENT", cookie, null, null);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Content-Type").orElse("")).contains("text/csv");
        assertThat(allowed.headers().firstValue("X-Template-Version").orElse("")).isEqualTo("STUDENT_V1");
        assertThat(allowed.body()).contains("studentName", "guardianPhone").doesNotContain("example@example.com");
    }

    @Test
    void dataTransferJobReadIsCreatorScopedAndDoesNotExposeFileKey() throws Exception {
        createRoleUser("OPERATOR", "data-transfer-job-owner@rami.local");
        createRoleUser("OWNER", "data-transfer-job-other@rami.local");
        UUID ownerId = jdbcTemplate.queryForObject("select id from admin_user where email=?", UUID.class,
                "data-transfer-job-owner@rami.local");
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into data_transfer_job(id,direction,domain,status,template_version,source_file_name,storage_key,sha256,file_size,
                    expires_at,created_by)
                values(?,'IMPORT','STUDENT','READY','STUDENT_V1','students.csv','private/test.csv',repeat('a',64),12,
                    statement_timestamp()+interval '1 day',?)
                """, jobId, ownerId);
        String ownerCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-job-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_job_owner")).rawToken();
        String otherCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-job-other@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_job_other")).rawToken();

        HttpResponse<String> own = http("GET", "/api/admin/data-transfer/jobs/" + jobId, ownerCookie, null, null);
        HttpResponse<String> other = http("GET", "/api/admin/data-transfer/jobs/" + jobId, otherCookie, null, null);

        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
        assertThat(own.body()).contains(jobId.toString(), "READY", "STUDENT_V1").doesNotContain("private/test.csv");
        assertThat(other.statusCode()).isEqualTo(404);
    }

    @Test
    void sessionPolicyHistoryIsReadableToOwnerAndNeverCached() throws Exception {
        createRoleUser("OWNER", "session-policy-reader@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "session-policy-reader@rami.local", TEMPORARY_PASSWORD, null, metadata("req_policy_read")).rawToken();

        HttpResponse<String> anonymous = http("GET", "/api/admin/session-policies?size=20", null, null, null);
        HttpResponse<String> allowed = http("GET", "/api/admin/session-policies?size=20", cookie, null, null);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(allowed.body()).contains("current", "history", "maxFailedAttempts", "absoluteTimeoutMinutes")
                .doesNotContain("password_hash", "token_hash");
    }

    @Test
    void sessionPolicyChangeIsIdempotentAndDoesNotRewriteExistingSessionPolicy() throws Exception {
        createRoleUser("OWNER", "session-policy-writer@rami.local");
        UUID actor = jdbcTemplate.queryForObject("select id from admin_user where email=?", UUID.class,
                "session-policy-writer@rami.local");
        IssuedSession session = authSessionService.login("session-policy-writer@rami.local", TEMPORARY_PASSWORD,
                null, metadata("req_policy_write_login"));
        UUID previousId = jdbcTemplate.queryForObject("select policy_id from admin_session where admin_user_id=? order by issued_at desc limit 1",
                UUID.class, actor);
        UUID key = UUID.randomUUID();
        UUID currentId = jdbcTemplate.queryForObject("select id from admin_session_policy where effective_to is null", UUID.class);
        String body = """
                {"currentPolicyId":"%s","maxFailedAttempts":6,"lockDurationMinutes":45,
                 "idleTimeoutMinutes":75,"absoluteTimeoutMinutes":720,"expiryWarningMinutes":6,
                 "changeReason":"운영 접근 정책 조정"}
                """.formatted(currentId);
        String cookie = "__Host-rami_admin_session=" + session.rawToken();

        HttpResponse<String> first = httpWithKey("POST", "/api/admin/session-policies", cookie, body, key);
        HttpResponse<String> replay = httpWithKey("POST", "/api/admin/session-policies", cookie, body, key);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_session_policy", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_log where action='SESSION_POLICY_CHANGED'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select policy_id from admin_session where admin_user_id=? order by issued_at desc limit 1",
                UUID.class, actor)).isEqualTo(previousId);
    }

    @Test
    void dataTransferStudentUploadStoresEncryptedPreviewAndReturnsNoRawData() throws Exception {
        createRoleUser("OPERATOR", "data-transfer-upload@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-upload@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_upload_login")).rawToken();
        String csv = "studentName,birthday,joinedAt,guardianName,relationship,guardianPhone,guardianEmail,courseCode,classGroupCode\r\n"
                + "Minji Park,2015-05-01,2023-03-01,Guardian,MOTHER,01012345678,parent@example.com,ART,CLASS-A\r\n";

        HttpResponse<String> response = multipart("/api/admin/data-transfer/imports", cookie,
                "STUDENT", "STUDENT_V1", "students.csv", csv);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).contains("READY", "totalCount", "validCount").doesNotContain("Minji", "01012345678", "private/");
        assertThat(jdbcTemplate.queryForObject("select count(*) from data_transfer_job where domain='STUDENT' and created_by=(select id from admin_user where email=?)",
                Integer.class, "data-transfer-upload@rami.local")).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from data_transfer_row r join data_transfer_job j on j.id=r.job_id where j.domain='STUDENT' and r.status='VALID' and r.payload_ciphertext is not null",
                Integer.class)).isOne();
    }

    @Test
    void dataTransferRejectsAnActiveFileHashDuplicateBeforeStorageUpload() throws Exception {
        createRoleUser("OPERATOR", "data-transfer-duplicate@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-duplicate@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_duplicate_login")).rawToken();
        String csv = "attendanceSessionId,studentId,attendanceStatus\r\n"
                + "2d430adb-80c5-4c53-a770-60ed72cc3158,3d0b6d0a-a76e-4053-a6d4-f08470d156e1,PRESENT\r\n";

        HttpResponse<String> first = multipart("/api/admin/data-transfer/imports", cookie,
                "ATTENDANCE", "ATTENDANCE_V1", "attendance.csv", csv);
        HttpResponse<String> duplicate = multipart("/api/admin/data-transfer/imports", cookie,
                "ATTENDANCE", "ATTENDANCE_V1", "attendance.csv", csv);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select count(*) from data_transfer_job where domain='ATTENDANCE'", Integer.class)).isOne();
        verify(dataTransferStorage).upload(org.mockito.ArgumentMatchers.startsWith("data-transfers/"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dataTransferConfirmCreatesStudentAndConsumesEncryptedRowPayload() throws Exception {
        createRoleUser("OPERATOR", "data-transfer-confirm@rami.local");
        String cookie = "__Host-rami_admin_session=" + authSessionService.login(
                "data-transfer-confirm@rami.local", TEMPORARY_PASSWORD, null, metadata("req_transfer_confirm_login")).rawToken();
        String csv = "studentName,birthday,joinedAt,guardianName,relationship,guardianPhone,guardianEmail,courseCode,classGroupCode\r\n"
                + "Transfer Learner,2015-05-01,2023-03-01,Transfer Guardian,MOTHER,01033334444,,,\r\n";
        HttpResponse<String> upload = multipart("/api/admin/data-transfer/imports", cookie,
                "STUDENT", "STUDENT_V1", "confirm.csv", csv);
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(201);
        UUID jobId = jdbcTemplate.queryForObject("select id from data_transfer_job where domain='STUDENT' and source_file_name='confirm.csv'",
                UUID.class);
        UUID rowId = jdbcTemplate.queryForObject("select id from data_transfer_row where job_id=?", UUID.class, jobId);
        UUID key = UUID.randomUUID();

        HttpResponse<String> confirmed = httpWithKey("POST", "/api/admin/data-transfer/jobs/" + jobId + "/confirm", cookie,
                "{\"jobVersion\":1,\"rowIds\":[\"" + rowId + "\"],\"duplicateActions\":{}}", key);
        HttpResponse<String> replay = httpWithKey("POST", "/api/admin/data-transfer/jobs/" + jobId + "/confirm", cookie,
                "{\"jobVersion\":1,\"rowIds\":[\"" + rowId + "\"],\"duplicateActions\":{}}", key);

        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select count(*) from student where student_name_search='transferlearner'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select status from data_transfer_row where id=?", String.class, rowId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select payload_ciphertext from data_transfer_row where id=?", byte[].class, rowId)).isNull();
        assertThat(jdbcTemplate.queryForObject("select status from data_transfer_job where id=?", String.class, jobId)).isEqualTo("COMPLETED");

        String duplicateCsv = "studentName,birthday,joinedAt,guardianName,relationship,guardianPhone,guardianEmail,courseCode,classGroupCode\r\n"
                + "Transfer Learner,2015-05-01,2023-03-01,Transfer Guardian,MOTHER,01033334444,other@example.com,,\r\n";
        HttpResponse<String> duplicateUpload = multipart("/api/admin/data-transfer/imports", cookie,
                "STUDENT", "STUDENT_V1", "duplicate-confirm.csv", duplicateCsv);
        assertThat(duplicateUpload.statusCode()).as(duplicateUpload.body()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select status from data_transfer_row where job_id=(select id from data_transfer_job where source_file_name='duplicate-confirm.csv')",
                String.class)).isEqualTo("DUPLICATE");
        assertThat(jdbcTemplate.queryForObject("select duplicate_target_id from data_transfer_row where job_id=(select id from data_transfer_job where source_file_name='duplicate-confirm.csv')",
                UUID.class)).isEqualTo(jdbcTemplate.queryForObject("select id from student where student_name_search='transferlearner'", UUID.class));
    }

    private HttpResponse<String> httpWithKey(String method,String path,String cookie,String body,UUID key)
            throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .header("Cookie",cookie).header("Origin","http://localhost:3000").header("Idempotency-Key",key.toString())
                .method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        try(var client=HttpClient.newHttpClient()) { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
    }

    private HttpResponse<String> multipart(String path, String cookie, String domain, String version, String filename, String csv)
            throws IOException, InterruptedException {
        String boundary = "rami-boundary-7f3c";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"domain\"\r\n\r\n" + domain + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"templateVersion\"\r\n\r\n" + version + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/csv; charset=utf-8\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Cookie", cookie)
                .header("Origin", "http://localhost:3000").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    }

    @Test
    void staffHttpEndpointsSeparateReadAndWritePermissions() throws Exception {
        createRoleUser("OWNER", "staff-owner@rami.local");
        createRoleUser("OPERATOR", "staff-operator@rami.local");
        createRoleUser("CONTENT", "staff-content@rami.local");
        String ownerCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "staff-owner@rami.local", TEMPORARY_PASSWORD, null, metadata("req_owner_staff")).rawToken();
        String operatorCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "staff-operator@rami.local", TEMPORARY_PASSWORD, null, metadata("req_operator_staff")).rawToken();
        String contentCookie = "__Host-rami_admin_session=" + authSessionService.login(
                "staff-content@rami.local", TEMPORARY_PASSWORD, null, metadata("req_content_staff")).rawToken();
        String body = """
                {"staffCode":"TEACHER_HTTP","name":"HTTP 강사","displayName":"HTTP 선생님",
                 "jobTitle":"TEACHER","phone":"010-9090-8080","hiredOn":"2026-09-01","status":"ACTIVE"}
                """;

        assertThat(http("GET", "/api/admin/staff?page=0&size=20", operatorCookie, null, null).statusCode())
                .isEqualTo(200);
        assertThat(httpWithIdempotency("POST", "/api/admin/staff", operatorCookie, body).statusCode())
                .isEqualTo(403);
        assertThat(http("GET", "/api/admin/staff?page=0&size=20", contentCookie, null, null).statusCode())
                .isEqualTo(403);
        var created = httpWithIdempotency("POST", "/api/admin/staff", ownerCookie, body);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("TEACHER_HTTP", "+821090908080");
    }

    private HttpResponse<String> httpWithIdempotency(String method, String path, String cookie, String body)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .header("Origin", "http://localhost:3000")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void passwordChangeRollsBackEveryWriteWhenAuditPersistenceFails() {
        IssuedSession current = login("req_first_session");
        login("req_second_session");
        String originalHash = passwordHash();

        assertThatThrownBy(() -> passwordChangeService.change(
                current.rawToken(),
                TEMPORARY_PASSWORD,
                "ChangedPassword!456",
                metadata("x".repeat(101))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(passwordHash()).isEqualTo(originalHash);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_password_history", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select password_must_change from admin_user where email = ?",
                Boolean.class,
                OWNER_EMAIL)).isTrue();
        assertThat(count("admin_session", "revoked_at is null")).isEqualTo(2);
        assertThat(count("audit_log", "action = 'ADMIN_PASSWORD_CHANGED'")).isZero();
    }

    @Test
    void passwordChangeCommitsHashSessionRevocationAndAuditDetailsTogether() {
        IssuedSession current = login("req_current_session");
        login("req_other_session");

        PasswordChangeService.Result result = passwordChangeService.change(
                current.rawToken(),
                TEMPORARY_PASSWORD,
                "ChangedPassword!456",
                metadata("req_password_change"));

        assertThat(result.revokedSessionCount()).isOne();
        assertThat(passwordVerifier.matches("ChangedPassword!456", passwordHash())).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                        select password_must_change, temporary_password_expires_at,
                               failed_login_count, version
                        from admin_user where email = ?
                        """, OWNER_EMAIL))
                .containsEntry("password_must_change", false)
                .containsEntry("temporary_password_expires_at", null)
                .containsEntry("failed_login_count", 0);
        assertThat(count("admin_session", "revoked_at is null")).isOne();
        assertThat(count("admin_session", "revoke_reason = 'PASSWORD_CHANGED'")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_password_history", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                        select details ->> 'revokedSessionCount'
                        from audit_log where action = 'ADMIN_PASSWORD_CHANGED'
                """, String.class)).isEqualTo("1");
    }

    @Test
    void passwordPolicyRejectsCompromisedAndRecentlyUsedPasswords() throws Exception {
        IssuedSession current = login("req_password_policy");

        String cookie = "__Host-rami_admin_session=" + current.rawToken();
        var compromised = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"password1234\"}");
        assertThat(compromised.statusCode()).isEqualTo(422);
        assertThat(compromised.body()).contains("PASSWORD_COMPROMISED").doesNotContain("password1234");

        passwordChangeService.change(
                current.rawToken(), TEMPORARY_PASSWORD, "FirstUnique!456", metadata("req_first_unique"));
        passwordChangeService.change(
                current.rawToken(), "FirstUnique!456", "SecondUnique!789", metadata("req_second_unique"));

        assertThatThrownBy(() -> passwordChangeService.change(
                current.rawToken(), "SecondUnique!789", TEMPORARY_PASSWORD, metadata("req_reused")))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_REUSED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_password_history", Integer.class)).isEqualTo(2);
        assertThat(passwordVerifier.matches("SecondUnique!789", passwordHash())).isTrue();
    }

    private IssuedSession login(String requestId) {
        return authSessionService.login(
                OWNER_EMAIL,
                TEMPORARY_PASSWORD,
                "/admin/dashboard",
                metadata(requestId));
    }

    @Test
    void unicodePasswordAtMaximumLengthRoundTripsThroughHttp() throws Exception {
        var current = login("req_unicode");
        String cookie = "__Host-rami_admin_session=" + current.rawToken();
        String password = "가".repeat(126) + "😀";
        var tooShort = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"12345678901\"}");
        assertThat(tooShort.statusCode()).isEqualTo(422);
        var tooLong = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"" + password + "x\"}");
        assertThat(tooLong.statusCode()).isEqualTo(422);
        var change = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"" + password + "\"}");
        assertThat(change.statusCode()).isEqualTo(200);
        assertThat(passwordHash()).startsWith("{pbkdf2-sha256-600k}");
        var authenticated = http("POST", "/api/admin/auth/sessions", null, "http://localhost:3000",
                "{\"email\":\"owner@rami.local\",\"password\":\"" + password + "\"}");
        assertThat(authenticated.statusCode()).isEqualTo(201);
        assertThat(passwordVerifier.matches("가".repeat(125) + "나😀", passwordHash())).isFalse();
        assertThat(passwordVerifier.matches(TEMPORARY_PASSWORD, passwordHash())).isFalse();
    }

    @Test
    void passwordFailuresRemainRateLimitedAcrossRolledBackTransactions() throws Exception {
        String cookie = "__Host-rami_admin_session=" + login("req_limit").rawToken();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                    "{\"currentPassword\":\"incorrect-password\",\"newPassword\":\"ChangedPassword!456\"}")
                    .statusCode()).isEqualTo(401);
        }
        var limited = http("PUT", "/api/admin/users/me/password", cookie, "http://localhost:3000",
                "{\"currentPassword\":\"LocalOnly!Change123\",\"newPassword\":\"ChangedPassword!456\"}");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("PASSWORD_CHANGE_RATE_LIMITED").doesNotContain(TEMPORARY_PASSWORD);
        assertThat(Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow())).isBetween(1L, 900L);
        assertThat(jdbcTemplate.queryForObject("select failed_login_count from admin_user", Integer.class)).isZero();
        assertThat(http("GET", "/api/admin/auth/sessions/current", cookie, null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void loginEmailLimitAppliesToUnknownAccounts() throws Exception {
        String body = "{\"email\":\"unknown@rami.local\",\"password\":\"incorrect-password\"}";
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(http("POST", "/api/admin/auth/sessions", null, "http://localhost:3000", body)
                    .statusCode()).isEqualTo(401);
        }
        assertThat(http("POST", "/api/admin/auth/sessions", null, "http://localhost:3000", body)
                .statusCode()).isEqualTo(429);
    }

    @Autowired
    private JdbcAuthRateLimitStore rateLimitStore;

    @Test
    void passwordEncodingPreservesWhitespaceAndRejectsOversizedLegacyBcryptInputs() {
        String spaced = "  password with spaces  ";
        String hash = passwordVerifier.encode(spaced);
        assertThat(passwordVerifier.matches(spaced, hash)).isTrue();
        assertThat(passwordVerifier.matches(spaced.trim(), hash)).isFalse();
        assertThat(passwordVerifier.encode(spaced)).isNotEqualTo(hash);
        var bcrypt = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        String legacy = bcrypt.encode("a".repeat(72));
        assertThat(passwordVerifier.matches("a".repeat(72), legacy)).isTrue();
        assertThat(passwordVerifier.matches("a".repeat(72) + "b", legacy)).isFalse();
        assertThat(passwordVerifier.matches("가".repeat(25), legacy)).isFalse();
    }

    @Test
    void concurrentRateLimitsAreAtomicAndExpiredWindowsReset() throws Exception {
        String key = "a".repeat(64);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .<java.util.concurrent.Callable<Long>>mapToObj(index -> () -> rateLimitStore.consume(key, 10)).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get() == 0) accepted++;
            }
            assertThat(accepted).isEqualTo(10);
        }
        jdbcTemplate.update("update admin_auth_rate_limit set expires_at = now() - interval '1 second'");
        assertThat(rateLimitStore.consume(key, 10)).isZero();
    }

    @Test
    void seedIsIdempotentAndPreservesChangedCredentials() {
        IssuedSession current = login("req_seed");
        passwordChangeService.change(current.rawToken(), TEMPORARY_PASSWORD,
                "ChangedPassword!456", metadata("req_seed_password"));
        String changedHash = passwordHash();
        var before = jdbcTemplate.queryForList("""
                select code from admin_permission order by code
                """);
        Long grants = jdbcTemplate.queryForObject("select count(*) from admin_role_permission", Long.class);
        ResourceDatabasePopulator seed = new ResourceDatabasePopulator(
                new FileSystemResource(projectRoot().resolve("supabase/seed.sql")));
        seed.execute(dataSource);
        seed.execute(dataSource);
        assertThat(passwordHash()).isEqualTo(changedHash);
        assertThat(jdbcTemplate.queryForList("select code from admin_permission order by code")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_role_permission", Long.class)).isEqualTo(grants);
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_role", Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_user", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_session_policy", Integer.class)).isOne();
        assertThat(authSessionService.current(current.rawToken()).user().passwordMustChange()).isFalse();
    }

    @Test
    void publicRolesHaveNoTablePrivilegesAndRlsDeniesEvenAccidentalSelectGrants() throws SQLException {
        var tables = jdbcTemplate.queryForList("""
                select tablename from pg_tables where schemaname = 'public' order by tablename
                """, String.class);
        assertThat(tables).contains(
                "admin_auth_rate_limit", "admin_compromised_password", "admin_password_history",
                "admin_permission", "admin_role", "admin_role_permission", "admin_session",
                "admin_session_policy", "admin_user", "admin_user_role", "audit_log");
        for (String role : new String[] {"anon", "authenticated"}) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    for (String table : tables) {
                        assertThat(jdbcTemplate.queryForObject(
                                "select has_table_privilege(?, ?, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",
                                Boolean.class, role, "public." + table)).isFalse();
                        assertThat(jdbcTemplate.queryForObject(
                                "select rowsecurity from pg_tables where schemaname = 'public' and tablename = ?",
                                Boolean.class, table)).isTrue();
                    }
                    statement.execute("grant usage on schema public to " + role);
                    statement.execute("grant select on all tables in schema public to " + role);
                    statement.execute("set local role " + role);
                    for (String table : tables) {
                        try (var rows = statement.executeQuery("select count(*) from public." + table)) {
                            rows.next();
                            assertThat(rows.getLong(1)).as(role + ": " + table).isZero();
                        }
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void databaseRejectsInvalidAccountPolicySessionAndAuditStates() {
        login("req_constraints");
        for (String invalidSql : new String[] {
                "update admin_user set email = 'UPPER@EXAMPLE.COM'",
                "update admin_user set status = 'LOCKED', locked_until = null",
                "update admin_user set failed_login_count = 11",
                "update admin_user set temporary_password_expires_at = null",
                "update admin_session_policy set idle_timeout_minutes = absolute_timeout_minutes",
                "update admin_session set token_hash = 'invalid'",
                "update admin_session set idle_expires_at = expires_at + interval '1 minute'",
                "update admin_session set revoked_at = now(), revoke_reason = null",
                "update audit_log set details = '[]'::jsonb",
                "update admin_user_role set admin_role_id = '00000000-0000-0000-0000-000000000000'",
                "update admin_compromised_password set password_sha256 = 'invalid'"
        }) {
            assertThatThrownBy(() -> jdbcTemplate.update(invalidSql))
                    .as(invalidSql).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(authSessionService.current(login("req_constraints_after").rawToken()).user().role())
                .isEqualTo("OWNER");
    }

    private RequestMetadata metadata(String requestId) {
        return new RequestMetadata(requestId, "127.0.0.1", "integration-test");
    }

    private void createRoleUser(String role, String email) {
        UUID userId = UUID.nameUUIDFromBytes(("test-admin-" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbcTemplate.update("""
                insert into admin_user (
                    id, email, password_hash, display_name, password_must_change,
                    temporary_password_expires_at, password_changed_at
                )
                select ?, ?, password_hash, ?, false, null, now()
                from admin_user where email = ?
                """, userId, email, role + " 관리자", OWNER_EMAIL);
        jdbcTemplate.update("""
                insert into admin_user_role (admin_user_id, admin_role_id)
                select ?, id from admin_role where code = ?
                """, userId, role);
    }

    private record RoleExpectation(List<String> allowed, List<String> denied) {
    }

    private String passwordHash() {
        return jdbcTemplate.queryForObject(
                "select password_hash from admin_user where email = ?",
                String.class,
                OWNER_EMAIL);
    }

    private long count(String table, String condition) {
        Map<String, String> supportedQueries = Map.of(
                "admin_session|revoked_at is null",
                "select count(*) from admin_session where revoked_at is null",
                "admin_session|revoke_reason = 'PASSWORD_CHANGED'",
                "select count(*) from admin_session where revoke_reason = 'PASSWORD_CHANGED'",
                "audit_log|action = 'ADMIN_LOGIN' and result = 'SUCCESS'",
                "select count(*) from audit_log where action = 'ADMIN_LOGIN' and result = 'SUCCESS'",
                "audit_log|action = 'ADMIN_LOGIN' and result = 'FAILURE'",
                "select count(*) from audit_log where action = 'ADMIN_LOGIN' and result = 'FAILURE'",
                "audit_log|action = 'ADMIN_PASSWORD_CHANGED'",
                "select count(*) from audit_log where action = 'ADMIN_PASSWORD_CHANGED'");
        String query = supportedQueries.get(table + "|" + condition);
        if (query == null) {
            throw new IllegalArgumentException("unsupported count query");
        }
        return jdbcTemplate.queryForObject(query, Long.class);
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var resultSet = statement.executeQuery(
                "select exists(select 1 from pg_roles where rolname = '" + role + "')")) {
            resultSet.next();
            if (!resultSet.getBoolean(1)) {
                statement.execute("create role " + role);
            }
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("supabase"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("supabase"))) {
            return parent;
        }
        throw new IllegalStateException("supabase directory was not found");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            LegacyFlywayTestBootstrap.prepare(postgres.getPostgresDatabase());
            return postgres;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl() {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException exception) {
            throw new IllegalStateException("embedded PostgreSQL URL lookup failed", exception);
        }
    }
}
