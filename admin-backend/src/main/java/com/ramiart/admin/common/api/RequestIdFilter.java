package com.ramiart.admin.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader("X-Request-Id");
        String requestId = inbound != null && VALID_REQUEST_ID.matcher(inbound).matches()
                ? inbound
                : "req_" + UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        if (request.getRequestURI().startsWith("/api/admin/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        filterChain.doFilter(request, response);
    }

    public static String get(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }
}
