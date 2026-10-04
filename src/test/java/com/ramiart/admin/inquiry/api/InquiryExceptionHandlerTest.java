package com.ramiart.admin.inquiry.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.common.api.AdminGlobalExceptionHandler;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.inquiry.application.InquiryException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class InquiryExceptionHandlerTest {
    private final InquiryExceptionHandler handler = new InquiryExceptionHandler();

    @Test
    @DisplayName("MGT-INQUIRY-LIST keeps validation and persistence errors distinct")
    void mapsDomainAndPersistenceErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/inquiries");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_phase5_inquiry");

        var validation = handler.inquiry(new InquiryException("INQUIRY_QUERY_INVALID"), request);
        var persistence = handler.persistence(new DataAccessResourceFailureException("private db detail"), request);

        assertThat(OrderUtils.getOrder(InquiryExceptionHandler.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(AdminGlobalExceptionHandler.class)).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validation.getBody().error().code()).isEqualTo("INQUIRY_QUERY_INVALID");
        assertThat(persistence.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(persistence.getBody().error().code()).isEqualTo("INQUIRY_SAVE_FAILED");
        assertThat(persistence.getBody().error().message()).doesNotContain("private db detail");
    }
}
