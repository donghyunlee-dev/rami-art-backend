package com.ramiart.admin.common.security;

import com.ramiart.admin.auth.api.AuthSessionController;
import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.auth.application.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

final class AdminSessionFilter extends OncePerRequestFilter {

    private final AuthSessionService service;
    private final HandlerExceptionResolver exceptionResolver;

    AdminSessionFilter(AuthSessionService service, HandlerExceptionResolver exceptionResolver) {
        this.service = service;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/admin/")
                || path.equals("/api/admin/auth/sessions")
                || path.equals("/api/admin/auth/sessions/current")
                || path.equals("/api/admin/users/me/password");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            String token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                    .filter(cookie -> AuthSessionController.COOKIE_NAME.equals(cookie.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
            var user = service.current(token).user();
            if (user.passwordMustChange()) {
                throw new AuthSessionException("PASSWORD_CHANGE_REQUIRED");
            }
            var authorities = user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.id(), null, authorities));
            SecurityContextHolder.setContext(context);
        } catch (Exception exception) {
            exceptionResolver.resolveException(request, response, null, exception);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
