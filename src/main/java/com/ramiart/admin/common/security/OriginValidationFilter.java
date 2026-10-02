package com.ramiart.admin.common.security;

import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String allowedOrigin;

    public OriginValidationFilter(
            @Value("${admin.security.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (allowedOrigin.equals(request.getHeader("Origin"))) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = RequestIdFilter.get(request);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("""
                {"success":false,"data":null,"error":{"code":"ORIGIN_NOT_ALLOWED","message":"허용되지 않은 요청 출처입니다.","fieldErrors":[]},"requestId":"%s"}
                """.formatted(requestId).strip());
    }
}
