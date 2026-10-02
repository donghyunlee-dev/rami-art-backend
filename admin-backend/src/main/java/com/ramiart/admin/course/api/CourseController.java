package com.ramiart.admin.course.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.course.application.CourseModels.ClassGroupView;
import com.ramiart.admin.course.application.CourseModels.ClassGroupWrite;
import com.ramiart.admin.course.application.CourseModels.CourseDetail;
import com.ramiart.admin.course.application.CourseModels.CoursePage;
import com.ramiart.admin.course.application.CourseModels.CourseWrite;
import com.ramiart.admin.course.application.CourseModels.OccupancySummary;
import java.time.LocalDate;
import com.ramiart.admin.course.application.CourseService;
import com.ramiart.admin.course.application.CourseService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    ResponseEntity<ApiEnvelope<CoursePage>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ok(service.findCourses(keyword, active, page, size), request);
    }

    @GetMapping("/courses/{courseId}")
    ResponseEntity<ApiEnvelope<CourseDetail>> detail(@PathVariable UUID courseId, HttpServletRequest request) {
        return ok(service.findCourse(courseId), request);
    }

    @PostMapping("/courses")
    ResponseEntity<ApiEnvelope<CourseDetail>> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CourseWrite body, Authentication authentication, HttpServletRequest request) {
        CourseDetail created = service.createCourse(body, actor(authentication), idempotencyKey, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(created, RequestIdFilter.get(request)));
    }

    @PutMapping("/courses/{courseId}")
    ResponseEntity<ApiEnvelope<CourseDetail>> update(
            @PathVariable UUID courseId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CourseWrite body, Authentication authentication, HttpServletRequest request) {
        return ok(service.updateCourse(courseId, body, actor(authentication), idempotencyKey, metadata(request)), request);
    }

    @PostMapping("/courses/{courseId}/class-groups")
    ResponseEntity<ApiEnvelope<ClassGroupView>> createClassGroup(
            @PathVariable UUID courseId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody ClassGroupWrite body, Authentication authentication, HttpServletRequest request) {
        ClassGroupView created = service.createClassGroup(
                courseId, body, actor(authentication), idempotencyKey, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(created, RequestIdFilter.get(request)));
    }

    @PutMapping("/class-groups/{classGroupId}")
    ResponseEntity<ApiEnvelope<ClassGroupView>> updateClassGroup(
            @PathVariable UUID classGroupId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody ClassGroupWrite body, Authentication authentication, HttpServletRequest request) {
        return ok(service.updateClassGroup(
                classGroupId, body, actor(authentication), idempotencyKey, metadata(request)), request);
    }

    @GetMapping("/class-groups/{classGroupId}/occupancy")
    ResponseEntity<ApiEnvelope<OccupancySummary>> occupancy(
            @PathVariable UUID classGroupId, @RequestParam LocalDate from,
            @RequestParam LocalDate to, HttpServletRequest request) {
        return ok(service.findOccupancy(classGroupId, from, to), request);
    }

    @DeleteMapping("/class-groups/{classGroupId}")
    ResponseEntity<Void> deleteClassGroup(
            @PathVariable UUID classGroupId, @RequestParam long version,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication, HttpServletRequest request) {
        service.deleteClassGroup(classGroupId, version, actor(authentication), idempotencyKey, metadata(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }
}
