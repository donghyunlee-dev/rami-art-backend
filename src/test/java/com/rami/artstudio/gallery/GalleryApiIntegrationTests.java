package com.rami.artstudio.gallery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.auth.infra.AdminRefreshTokenRepository;
import com.rami.artstudio.auth.infra.AdminUserRepository;
import com.rami.artstudio.gallery.domain.GalleryCategory;
import com.rami.artstudio.gallery.domain.GalleryWork;
import com.rami.artstudio.gallery.infra.GalleryCategoryRepository;
import com.rami.artstudio.gallery.infra.GalleryWorkRepository;
import com.rami.artstudio.gallery.storage.GalleryImageStorage;
import com.rami.artstudio.gallery.storage.GalleryImageStorage.UploadedImage;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class GalleryApiIntegrationTests {

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
    private GalleryCategoryRepository categoryRepository;

    @Autowired
    private GalleryWorkRepository workRepository;

    @MockBean
    private GalleryImageStorage galleryImageStorage;

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

        given(galleryImageStorage.upload(any())).willAnswer(invocation -> {
            MockMultipartFile file = (MockMultipartFile) invocation.getArgument(0);
            String suffix = file.getOriginalFilename() != null && file.getOriginalFilename().contains("updated")
                    ? "updated"
                    : "created";
            return new UploadedImage(
                    "https://test-project.supabase.co/storage/v1/object/public/pub_rami_art_bucket/gallery/2026/" + suffix + ".jpg",
                    "gallery/2026/" + suffix + ".jpg");
        });
        doNothing().when(galleryImageStorage).delete(any());
    }

    @Test
    void publicGalleryListAndDetailWork() throws Exception {
        mockMvc.perform(get("/api/v1/gallery/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));

        GalleryWork work = workRepository.findAll().stream()
                .filter(item -> item.getDeletedAt() == null && Boolean.TRUE.equals(item.getIsActive()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/api/v1/gallery/works")
                        .param("featured", "true")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").isNumber());

        mockMvc.perform(get("/api/v1/gallery/works/{workId}", work.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(work.getId().toString()))
                .andExpect(jsonPath("$.data.imageUrl").exists());
    }

    @Test
    void adminGalleryApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/gallery/works"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void adminCategoryAndWorkCrudFlowWorks() throws Exception {
        String accessToken = loginAndGetAccessToken();

        String categoryBody = """
                {
                  "name": "테스트 카테고리",
                  "slug": "test-category",
                  "sortOrder": 30,
                  "isActive": true
                }
                """;

        MvcResult categoryResult = mockMvc.perform(post("/api/v1/admin/gallery/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("test-category"))
                .andReturn();

        JsonNode categoryJson = objectMapper.readTree(categoryResult.getResponse().getContentAsString());
        String categoryId = categoryJson.path("data").path("id").asText();

        MockMultipartFile createImage = new MockMultipartFile(
                "image",
                "create.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "create-image".getBytes());

        MvcResult createWorkResult = mockMvc.perform(multipart("/api/v1/admin/gallery/works")
                        .file(createImage)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("categoryId", categoryId)
                        .param("title", "[TEST] 작품 등록")
                        .param("description", "테스트 설명")
                        .param("ageGroup", "elementary")
                        .param("artistName", "테스트 학생")
                        .param("createdYear", "2026")
                        .param("sortOrder", "40")
                        .param("isFeatured", "true")
                        .param("isActive", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("[TEST] 작품 등록"))
                .andExpect(jsonPath("$.data.category.id").value(categoryId))
                .andExpect(jsonPath("$.data.imageUrl").value("https://test-project.supabase.co/storage/v1/object/public/pub_rami_art_bucket/gallery/2026/created.jpg"))
                .andReturn();

        JsonNode workJson = objectMapper.readTree(createWorkResult.getResponse().getContentAsString());
        String workId = workJson.path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/admin/gallery/works")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .param("categoryId", categoryId)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(workId));

        String sortBody = """
                {
                  "sortOrder": 12
                }
                """;

        mockMvc.perform(patch("/api/v1/admin/gallery/works/{workId}/sort", workId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sortBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(12));

        MockMultipartFile updateImage = new MockMultipartFile(
                "image",
                "updated.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "updated-image".getBytes());

        mockMvc.perform(multipart("/api/v1/admin/gallery/works/{workId}", workId)
                        .file(updateImage)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .with(putMultipart())
                        .param("categoryId", categoryId)
                        .param("title", "[TEST] 작품 수정")
                        .param("description", "수정 설명")
                        .param("ageGroup", "middle")
                        .param("artistName", "수정 학생")
                        .param("createdYear", "2025")
                        .param("sortOrder", "12")
                        .param("isFeatured", "false")
                        .param("isActive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("[TEST] 작품 수정"))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.imagePath").value("gallery/2026/updated.jpg"));

        String updateCategoryBody = """
                {
                  "name": "테스트 카테고리 수정",
                  "slug": "test-category-updated",
                  "sortOrder": 10,
                  "isActive": true
                }
                """;

        mockMvc.perform(put("/api/v1/admin/gallery/categories/{categoryId}", categoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateCategoryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("test-category-updated"));

        mockMvc.perform(delete("/api/v1/admin/gallery/works/{workId}", workId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(delete("/api/v1/admin/gallery/categories/{categoryId}", categoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(galleryImageStorage).should(times(2)).upload(any());
        then(galleryImageStorage).should().delete(eq("gallery/2026/created.jpg"));
        then(galleryImageStorage).should().delete(eq("gallery/2026/updated.jpg"));
    }

    @Test
    void publicGalleryFiltersByCategory() throws Exception {
        GalleryCategory category = categoryRepository.findAllByIsActiveTrueOrderBySortOrderAscCreatedAtAsc().stream()
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/api/v1/gallery/works")
                        .param("categoryId", category.getId().toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].category.id").value(category.getId().toString()));
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

    private RequestPostProcessor putMultipart() {
        return request -> {
            request.setMethod("PUT");
            return request;
        };
    }
}
