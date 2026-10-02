package com.ramiart.admin.common.security;

import java.time.Clock;
import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.auth.application.AuthSessionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import java.util.Map;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthSessionService service,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new AdminSessionFilter(service, resolver), AuthorizationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> resolver.resolveException(
                                request, response, null, new AuthSessionException("SESSION_REQUIRED")))
                        .accessDeniedHandler((request, response, exception) -> resolver.resolveException(
                                request, response, null, new AuthSessionException("ADMIN_ACCESS_DENIED"))))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health/**",
                                "/api/admin/auth/sessions/**",
                                "/api/admin/users/me/password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/inquiries").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/courses", "/api/admin/courses/**",
                                "/api/admin/class-groups/*/occupancy").hasAuthority("COURSE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/courses", "/api/admin/courses/*/class-groups")
                                .hasAuthority("COURSE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/courses/*", "/api/admin/class-groups/*")
                                .hasAuthority("COURSE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/class-groups/*")
                                .hasAuthority("COURSE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/staff", "/api/admin/staff/**")
                                .hasAuthority("STAFF_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/staff")
                                .hasAuthority("STAFF_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/staff/**")
                                .hasAuthority("STAFF_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/media-assets")
                                .hasAuthority("MEDIA_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/media-assets/*")
                                .hasAuthority("MEDIA_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/inquiries", "/api/admin/inquiries/**")
                                .hasAuthority("INQUIRY_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/inquiries/*/read-receipts")
                                .hasAuthority("INQUIRY_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/inquiries/*/activities")
                                .hasAuthority("INQUIRY_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/students/*/notes")
                                .hasAuthority("STUDENT_NOTE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/students/*/notes")
                                .hasAuthority("STUDENT_NOTE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/student-notes/*")
                                .hasAuthority("STUDENT_NOTE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/student-notes/*")
                                .hasAuthority("STUDENT_NOTE_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/students/*/status-change-previews",
                                "/api/admin/students/*/status-changes")
                                .hasAuthority("STUDENT_STATUS_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/students", "/api/admin/students/*")
                                .hasAuthority("STUDENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/students")
                                .hasAuthority("STUDENT_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/students/*")
                                .hasAuthority("STUDENT_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/monthly-schedules/**")
                                .hasAuthority("SCHEDULE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/monthly-schedules/*/drafts")
                                .hasAuthority("SCHEDULE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/monthly-schedules/*/drafts/*")
                                .hasAuthority("SCHEDULE_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/monthly-schedules/*/publications")
                                .hasAuthority("SCHEDULE_PUBLISH")
                        .requestMatchers(HttpMethod.GET, "/api/admin/attendance-sessions", "/api/admin/attendance-sessions/**")
                                .hasAuthority("ATTENDANCE_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/students/*/schedule-assignments",
                                "/api/admin/students/*/schedule-assignment-candidates")
                                .hasAuthority("STUDENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/students/*/schedule-assignments")
                                .hasAuthority("STUDENT_SCHEDULE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/student-schedule-assignments/*")
                                .hasAuthority("STUDENT_SCHEDULE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/student-schedule-assignments/*")
                                .hasAuthority("STUDENT_SCHEDULE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/enrollments", "/api/admin/enrollments/**")
                                .hasAuthority("ENROLLMENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/enrollments", "/api/admin/enrollments/**")
                                .hasAuthority("ENROLLMENT_WRITE")
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                        .anyRequest().denyAll())
                .headers(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        var pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        var encoder = new DelegatingPasswordEncoder("pbkdf2-sha256-600k",
                Map.of("pbkdf2-sha256-600k", pbkdf2, "bcrypt", new BCryptPasswordEncoder(12)));
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder(12));
        return encoder;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
