package com.ramiart.admin.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.common.api.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginValidationFilterTest {

    private final OriginValidationFilter filter = new OriginValidationFilter("https://admin.rami.art");

    @Test
    void rejectsUnsafeRequestFromDifferentOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/admin/auth/sessions");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_origin");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED").contains("req_origin");
    }

    @Test
    void allowsUnsafeRequestFromConfiguredOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH", "/api/admin/auth/sessions/current");
        request.addHeader("Origin", "https://admin.rami.art");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
