package com.ramiart.admin.sitebrand.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.common.api.AdminGlobalExceptionHandler;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.sitebrand.application.SiteBrandException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class SiteBrandExceptionHandlerTest {
    private final SiteBrandExceptionHandler handler = new SiteBrandExceptionHandler();

    @Test
    @DisplayName("MGT-SITE-BRAND returns contract conflict and persistence errors")
    void mapsDomainAndPersistenceErrors() {
        MockHttpServletRequest request = request();

        var conflict = handler.handle(new SiteBrandException("SITE_BRAND_VERSION_CONFLICT"), request);
        var persistence = handler.persistence(new DataAccessResourceFailureException("private db detail"), request);

        assertThat(OrderUtils.getOrder(SiteBrandExceptionHandler.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(AdminGlobalExceptionHandler.class)).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().error().code()).isEqualTo("SITE_BRAND_VERSION_CONFLICT");
        assertThat(persistence.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(persistence.getBody().error().code()).isEqualTo("SITE_BRAND_PERSISTENCE_FAILED");
        assertThat(persistence.getBody().error().message()).doesNotContain("private db detail");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/site-brand");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_phase5_brand");
        return request;
    }
}
