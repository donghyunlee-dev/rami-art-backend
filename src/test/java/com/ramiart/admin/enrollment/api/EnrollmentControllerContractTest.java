package com.ramiart.admin.enrollment.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.enrollment.application.EnrollmentService;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;

class EnrollmentControllerContractTest {
    @Test
    void listDatabaseFailureReturnsEnrollmentErrorAndRequestId() throws Exception {
        EnrollmentService service=mock(EnrollmentService.class);
        when(service.list(null,null,null,null,0,20))
                .thenThrow(new BadSqlGrammarException("enrollment list","select",new SQLException("query failed")));
        var mvc=MockMvcBuilders.standaloneSetup(new EnrollmentController(service))
                .addFilters(new RequestIdFilter()).build();

        mvc.perform(get("/api/admin/enrollments?page=0&size=20").header("X-Request-Id","req_enrollment_list"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-Id","req_enrollment_list"))
                .andExpect(jsonPath("$.error.code").value("ENROLLMENT_READ_FAILED"))
                .andExpect(jsonPath("$.error.message").value("상담 등록 목록을 불러오지 못했습니다."))
                .andExpect(jsonPath("$.requestId").value("req_enrollment_list"));
    }

    @Test
    void inquiryCaseCreationRequiresInquiryReadAndReturnsTopLevelRequestId() throws Exception {
        EnrollmentService service=mock(EnrollmentService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new EnrollmentController(service))
                .addFilters(new RequestIdFilter()).build();

        mvc.perform(post("/api/admin/enrollments").header("Idempotency-Key","40000000-0000-0000-0000-000000000002")
                        .principal(authentication("20000000-0000-0000-0000-000000000001","ENROLLMENT_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadName\":\"김상담\",\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/enrollments").header("X-Request-Id","req_inquiry_create_denied")
                        .header("Idempotency-Key","40000000-0000-0000-0000-000000000001")
                        .principal(authentication("20000000-0000-0000-0000-000000000001","ENROLLMENT_READ","ENROLLMENT_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inquiryId\":\"81000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.requestId").value("req_inquiry_create_denied"));
    }

    @Test
    void duplicateCandidateIdsAreOnlyReturnedWithTheirResourceReadPermission() throws Exception {
        EnrollmentService service=mock(EnrollmentService.class);
        when(service.duplicateCandidates("010-1234-5678")).thenReturn(List.of(
                new com.ramiart.admin.enrollment.application.EnrollmentModels.ContactDuplicateCandidate("INQUIRY",UUID.randomUUID(),"김**","5678","RECEIVED",List.of("PHONE_HASH")),
                new com.ramiart.admin.enrollment.application.EnrollmentModels.ContactDuplicateCandidate("ENROLLMENT",UUID.randomUUID(),"박**","5678","NEW",List.of("PHONE_HASH")),
                new com.ramiart.admin.enrollment.application.EnrollmentModels.ContactDuplicateCandidate("STUDENT",UUID.randomUUID(),"이**","5678","ACTIVE",List.of("PHONE_HASH"))));
        var mvc=MockMvcBuilders.standaloneSetup(new EnrollmentController(service)).build();

        mvc.perform(post("/api/admin/enrollments/duplicate-candidates")
                        .principal(authentication("20000000-0000-0000-0000-000000000001","ENROLLMENT_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[1].id").doesNotExist())
                .andExpect(jsonPath("$.data[2].id").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("010-1234-5678"))));
    }

    private static org.springframework.security.core.Authentication authentication(String name,String... permissions){
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                name,"n/a",java.util.Arrays.stream(permissions).<org.springframework.security.core.GrantedAuthority>map(permission->()->permission).toList());
    }
}
