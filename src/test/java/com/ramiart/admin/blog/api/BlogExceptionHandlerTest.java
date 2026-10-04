package com.ramiart.admin.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.blog.application.BlogException;
import com.ramiart.admin.common.api.AdminGlobalExceptionHandler;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class BlogExceptionHandlerTest {
    private final BlogExceptionHandler handler = new BlogExceptionHandler();

    @Test
    @DisplayName("MGT-BLOG-EDIT maps domain and persistence errors before the global handler")
    void mapsErrorsBeforeGlobalFallback() {
        MockHttpServletRequest request = request("/api/admin/blog-posts");

        var validation = handler.handle(new BlogException("BLOG_QUERY_INVALID"), request);
        var persistence = handler.persistence(new DataAccessResourceFailureException("private db detail"), request);

        assertThat(OrderUtils.getOrder(BlogExceptionHandler.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(AdminGlobalExceptionHandler.class)).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validation.getBody().error().code()).isEqualTo("BLOG_QUERY_INVALID");
        assertThat(persistence.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(persistence.getBody().error().code()).isEqualTo("BLOG_PERSISTENCE_FAILED");
        assertThat(persistence.getBody().requestId()).isEqualTo("req_phase5_blog");
        assertThat(persistence.getBody().error().message()).doesNotContain("private db detail");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_phase5_blog");
        return request;
    }
}
