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
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;

@Configuration
@Order(1)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AuthSessionService service,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        return http
                .securityMatcher("/api/admin/**", "/api/public/**", "/media/**", "/actuator/**")
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
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/reauthentication").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/public/inquiries").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/blog-posts", "/api/public/blog-posts/**",
                                "/api/public/site-brand").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/blog-posts", "/api/admin/blog-posts/**")
                                .hasAuthority("BLOG_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/dashboard/summary")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/admin/dashboard/monthly")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/admin/data-transfer/templates/*")
                                .hasAuthority("DATA_TRANSFER_IMPORT")
                        .requestMatchers(HttpMethod.GET, "/api/admin/data-transfer/jobs/*", "/api/admin/data-transfer/jobs/*/rows")
                                .hasAnyAuthority("DATA_TRANSFER_IMPORT", "DATA_TRANSFER_EXPORT")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition-billings/*/payments")
                                .hasAuthority("TUITION_PAYMENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition-billings/*/payments",
                                "/api/admin/tuition-payments/*/cancellations")
                                .hasAuthority("TUITION_PAYMENT_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/financial-entries", "/api/admin/finance-ledger-options")
                                .hasAuthority("FINANCE_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/finance-settlements", "/api/admin/finance-settlements/**")
                                .hasAuthority("FINANCE_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/financial-imports", "/api/admin/financial-imports/**")
                                .hasAuthority("FINANCE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/financial-imports")
                                .hasAuthority("FINANCE_IMPORT")
                        .requestMatchers(HttpMethod.POST, "/api/admin/financial-imports/*/confirmations")
                                .hasAuthority("FINANCE_IMPORT")
                        .requestMatchers(HttpMethod.GET, "/api/admin/consent-policies", "/api/admin/students/*/consents",
                                "/api/admin/student-consents/*/evidence-url")
                                .hasAuthority("CONSENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/consent-policies/*/draft",
                                "/api/admin/consent-policies/draft/*/publish", "/api/admin/students/*/consents",
                                "/api/admin/student-consents/*/revocation")
                                .hasAuthority("CONSENT_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/consent-policies/draft/*")
                                .hasAuthority("CONSENT_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/notifications", "/api/admin/notifications/*")
                                .hasAuthority("NOTIFICATION_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/notifications/preview")
                                .hasAuthority("NOTIFICATION_SEND")
                        .requestMatchers(HttpMethod.POST, "/api/admin/notifications",
                                "/api/admin/notifications/*/cancellation", "/api/admin/notifications/*/retry")
                                .hasAuthority("NOTIFICATION_SEND")
                        .requestMatchers(HttpMethod.POST, "/api/admin/financial-entries",
                                "/api/admin/financial-entries/*/cancellations")
                                .hasAuthority("FINANCE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition/billings/*/adjustments",
                                "/api/admin/tuition/billings/*/adjustment-preview")
                                .hasAuthority("TUITION_ADJUSTMENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition/billings/*/adjustment-preview")
                                .hasAuthority("TUITION_ADJUSTMENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition/billings/*/adjustments",
                                "/api/admin/tuition/adjustments/*/cancellation")
                                .hasAuthority("TUITION_ADJUSTMENT_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition/billings/*/refunds",
                                "/api/admin/tuition/refunds/*/cancellation")
                                .hasAuthority("TUITION_REFUND_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition/payments/*/receipt",
                                "/api/admin/tuition/receipts/*/versions/*/download-url")
                                .hasAuthority("TUITION_RECEIPT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition/payments/*/receipt",
                                "/api/admin/tuition/receipts/*/versions")
                                .hasAuthority("TUITION_RECEIPT_ISSUE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition-billings/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition-policies/**")
                                .hasAuthority("TUITION_POLICY_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition-policies/**")
                                .hasAuthority("TUITION_POLICY_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/tuition-policies/**")
                                .hasAuthority("TUITION_POLICY_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/admin/students/*/tuition-assignments",
                                "/api/admin/students/*/tuition-assignment-candidates").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/admin/students/*/tuition-assignments")
                                .authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/admin/student-tuition-assignments/*")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/admin/tuition-billing-previews/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/admin/tuition-billings/batches")
                                .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/admin/blog-posts")
                                .hasAuthority("BLOG_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/blog-posts/*/drafts")
                                .hasAuthority("BLOG_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/blog-posts/*/drafts/*")
                                .hasAuthority("BLOG_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/blog-posts/*/publications")
                                .hasAuthority("BLOG_PUBLISH")
                        .requestMatchers(HttpMethod.GET, "/api/admin/site-brand")
                                .hasAuthority("SITE_BRAND_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/site-brand/preview")
                                .hasAuthority("SITE_BRAND_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/site-brand/draft")
                                .hasAuthority("SITE_BRAND_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/site-brand/draft/*")
                                .hasAuthority("SITE_BRAND_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/site-brand/draft/*/publish")
                                .hasAuthority("SITE_BRAND_PUBLISH")
                        .requestMatchers(HttpMethod.GET, "/api/admin/studio-profile",
                                "/api/admin/studio-profile/drafts/*/preview")
                                .hasAuthority("CONTENT_PROFILE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/studio-profile/drafts",
                                "/api/admin/studio-profile/publications")
                                .hasAuthority("CONTENT_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/studio-profile/drafts/*")
                                .hasAuthority("CONTENT_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/public/studio-profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/director-profile",
                                "/api/admin/director-profile/drafts/*/preview")
                                .hasAuthority("CONTENT_PROFILE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/director-profile/drafts",
                                "/api/admin/director-profile/publications")
                                .hasAuthority("CONTENT_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/director-profile/drafts/*")
                                .hasAuthority("CONTENT_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/public/director-profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/content/class-programs", "/api/admin/content/class-programs/**")
                                .hasAuthority("CONTENT_PROGRAM_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/content/class-programs/preview")
                                .hasAuthority("CONTENT_PROGRAM_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/content/class-programs/*/drafts",
                                "/api/admin/content/class-programs/*/publications")
                                .hasAuthority("CONTENT_PROGRAM_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/content/class-programs/*/drafts/*")
                                .hasAuthority("CONTENT_PROGRAM_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/public/class-programs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/admin/gallery-artworks", "/api/admin/gallery-artworks/*")
                                .hasAuthority("GALLERY_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/gallery-artworks/preview")
                                .hasAuthority("GALLERY_READ")
                        .requestMatchers(HttpMethod.GET, "/api/admin/student-consents")
                                .hasAuthority("CONSENT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/admin/gallery-artworks", "/api/admin/gallery-artworks/*/drafts")
                                .hasAuthority("GALLERY_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/gallery-artworks/*/drafts/*")
                                .hasAuthority("GALLERY_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/gallery-artworks/*/publications")
                                .hasAuthority("GALLERY_PUBLISH")
                        .requestMatchers(HttpMethod.GET, "/api/public/gallery-artworks").permitAll()
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
                        .requestMatchers(HttpMethod.GET, "/api/admin/makeups", "/api/admin/makeups/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/admin/makeups/*/reservations",
                                "/api/admin/makeups/*/reservation-cancellation", "/api/admin/makeups/*/waiver",
                                "/api/admin/makeups/*/extension")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/admin/attendance-sessions", "/api/admin/attendance-sessions/**")
                                .hasAuthority("ATTENDANCE_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/attendance-sessions/*/students/*")
                                .hasAuthority("ATTENDANCE_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/admin/attendance-sessions/*/closures")
                                .hasAuthority("ATTENDANCE_CLOSE")
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
    PasswordEncoder adminPasswordEncoder() {
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
