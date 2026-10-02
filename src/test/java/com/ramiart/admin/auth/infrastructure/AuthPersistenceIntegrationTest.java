package com.ramiart.admin.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.AdminApplication;
import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.AuthSessionService;
import com.ramiart.admin.auth.application.AuthSessionService.IssuedSession;
import com.ramiart.admin.auth.application.PasswordChangeService;
import com.ramiart.admin.auth.application.PasswordVerifier;
import com.ramiart.admin.dev.PostgresScriptRunner;
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

@SpringBootTest(classes = AdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthPersistenceIntegrationTest {

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
            return EmbeddedPostgres.builder().start();
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
