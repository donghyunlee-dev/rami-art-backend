package com.ramiart.admin.inquiry;

import com.ramiart.admin.auth.application.AuthSessionService;
import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.common.api.AdminGlobalExceptionHandler;
import com.ramiart.admin.common.security.SecurityConfiguration;
import com.ramiart.admin.inquiry.api.PublicInquiryController;
import com.ramiart.admin.inquiry.application.InquiryModels.PublicCourseOption;
import com.ramiart.admin.inquiry.application.InquiryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(PublicInquirySecurityTest.Config.class)
@WebAppConfiguration
class PublicInquirySecurityTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, PublicInquiryController.class, AdminGlobalExceptionHandler.class})
    static class Config {
        @Bean AuthSessionService authSessionService() {
            AuthSessionService service = mock(AuthSessionService.class);
            when(service.current(null)).thenThrow(new AuthSessionException("SESSION_REQUIRED"));
            return service;
        }
        @Bean InquiryService inquiryService() { return mock(InquiryService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired InquiryService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousVisitorCanReadPublishedCourseOptions() throws Exception {
        UUID courseId = UUID.fromString("00000000-0000-4000-8000-000000000101");
        when(service.publicCourseOptions()).thenReturn(List.of(new PublicCourseOption(courseId, "유치부")));
        mvc.perform(get("/api/public/inquiries/course-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].courseId").value(courseId.toString()));
    }

    @Test
    void anonymousVisitorCannotReadAdminInquiryOptions() throws Exception {
        mvc.perform(get("/api/admin/inquiries/course-options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_REQUIRED"));
    }
}
