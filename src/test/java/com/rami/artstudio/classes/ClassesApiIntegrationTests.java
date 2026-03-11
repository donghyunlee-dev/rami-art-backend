package com.rami.artstudio.classes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.auth.infra.AdminRefreshTokenRepository;
import com.rami.artstudio.auth.infra.AdminUserRepository;
import com.rami.artstudio.classes.domain.ArtClass;
import com.rami.artstudio.classes.infra.ArtClassRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class ClassesApiIntegrationTests {

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

    @Autowired
    private ArtClassRepository artClassRepository;

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
    void publicClassListReturnsSeededActiveClasses() throws Exception {
        mockMvc.perform(get("/api/v1/classes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].tags").isArray());
    }

    @Test
    void publicClassDetailReturnsSeededClass() throws Exception {
        ArtClass artClass = artClassRepository.findByAgeGroupAndDeletedAtIsNull("kindergarten")
                .orElseThrow();

        mockMvc.perform(get("/api/v1/classes/{classId}", artClass.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ageGroup").value("kindergarten"))
                .andExpect(jsonPath("$.data.title").value("유치부 미술 수업"));
    }

    @Test
    void adminClassListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/classes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void adminCreateUpdateDeleteAndSortFlowWorks() throws Exception {
        String accessToken = loginAndGetAccessToken();

        ArtClass middle = artClassRepository.findByAgeGroupAndDeletedAtIsNull("middle")
                .orElseThrow();
        middle.setDeletedAt(OffsetDateTime.now());
        middle.setUpdatedAt(OffsetDateTime.now());
        artClassRepository.save(middle);

        String createBody = """
                {
                  "ageGroup": "middle",
                  "ageRange": "14-16세",
                  "title": "[TEST] 중등부 심화 수업",
                  "description": "테스트용 수업 설명",
                  "curriculum": "테스트 커리큘럼",
                  "thumbnailUrl": "https://example.com/test-class.jpg",
                  "sortOrder": 99,
                  "isActive": true,
                  "tags": ["테스트 태그 A", "테스트 태그 B"]
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/classes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("[TEST] 중등부 심화 수업"))
                .andReturn();

        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String classId = createJson.path("data").path("id").asText();

        String sortBody = """
                {
                  "sortOrder": 77
                }
                """;

        mockMvc.perform(patch("/api/v1/admin/classes/{classId}/sort", classId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sortBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(77));

        String updateBody = """
                {
                  "ageGroup": "middle",
                  "ageRange": "14-16세",
                  "title": "[TEST] 중등부 수정 수업",
                  "description": "수정된 설명",
                  "curriculum": "수정된 커리큘럼",
                  "thumbnailUrl": "https://example.com/test-class-updated.jpg",
                  "sortOrder": 77,
                  "isActive": false,
                  "tags": ["수정 태그 A", "수정 태그 B"]
                }
                """;

        mockMvc.perform(put("/api/v1/admin/classes/{classId}", classId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("[TEST] 중등부 수정 수업"))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.tags.length()").value(2));

        mockMvc.perform(get("/api/v1/admin/classes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')].title".formatted(classId)).value("[TEST] 중등부 수정 수업"));

        mockMvc.perform(delete("/api/v1/admin/classes/{classId}", classId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String loginAndGetAccessToken() throws Exception {
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
        return loginJson.path("data").path("accessToken").asText();
    }
}
