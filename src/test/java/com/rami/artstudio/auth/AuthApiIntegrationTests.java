package com.rami.artstudio.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.auth.infra.AdminRefreshTokenRepository;
import com.rami.artstudio.auth.infra.AdminUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTests {

    private static final String EMAIL = "admin@ramiartstudio.com";
    private static final String PASSWORD = "ChangeMe123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminRefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setupAdminCredentials() {
        AdminUser adminUser = adminUserRepository.findByEmail(EMAIL)
                .orElseGet(() -> {
                    AdminUser user = new AdminUser();
                    user.setId(UUID.randomUUID());
                    user.setName("원장");
                    user.setEmail(EMAIL);
                    user.setRole("admin");
                    user.setCreatedAt(OffsetDateTime.now());
                    user.setUpdatedAt(OffsetDateTime.now());
                    return user;
                });

        adminUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        adminUserRepository.save(adminUser);
        refreshTokenRepository.revokeAllByAdminUserId(adminUser.getId());
    }

    @Test
    void loginAndMeFlowWorks() throws Exception {
        String loginBody = """
                {
                  "email": "admin@ramiartstudio.com",
                  "password": "ChangeMe123!"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshAndLogoutFlowWorks() throws Exception {
        String loginBody = """
                {
                  "email": "admin@ramiartstudio.com",
                  "password": "ChangeMe123!"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.path("data").path("accessToken").asText();
        String refreshToken = loginJson.path("data").path("refreshToken").asText();

        String refreshBody = """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void changePasswordWorks() throws Exception {
        String loginBody = """
                {
                  "email": "admin@ramiartstudio.com",
                  "password": "ChangeMe123!"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginJson.path("data").path("accessToken").asText();

        String changeBody = """
                {
                  "currentPassword": "ChangeMe123!",
                  "newPassword": "ChangeMe123!2"
                }
                """;

        mockMvc.perform(patch("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .content(changeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
