package com.ramiart.admin.enrollment.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.enrollment.application.EnrollmentService;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
}
