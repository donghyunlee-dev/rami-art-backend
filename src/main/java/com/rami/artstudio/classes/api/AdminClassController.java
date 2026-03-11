package com.rami.artstudio.classes.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rami.artstudio.classes.dto.ClassDtos.ClassView;
import com.rami.artstudio.classes.dto.ClassDtos.UpdateSortRequest;
import com.rami.artstudio.classes.dto.ClassDtos.UpsertClassRequest;
import com.rami.artstudio.classes.service.ClassService;
import com.rami.artstudio.common.api.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/classes")
public class AdminClassController {

    private final ClassService classService;

    public AdminClassController(ClassService classService) {
        this.classService = classService;
    }

    @GetMapping
    public ApiResponse<List<ClassView>> getClasses() {
        return ApiResponse.success(classService.getAdminClasses());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ClassView>> createClass(@Valid @RequestBody UpsertClassRequest request) {
        ClassView created = classService.createClass(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/classes/" + created.id()))
                .body(ApiResponse.success(created, "수업이 생성되었습니다."));
    }

    @PutMapping("/{classId}")
    public ApiResponse<ClassView> updateClass(@PathVariable UUID classId, @Valid @RequestBody UpsertClassRequest request) {
        return ApiResponse.success(classService.updateClass(classId, request), "수업이 수정되었습니다.");
    }

    @DeleteMapping("/{classId}")
    public ApiResponse<Void> deleteClass(@PathVariable UUID classId) {
        classService.deleteClass(classId);
        return ApiResponse.success(null, "수업이 삭제되었습니다.");
    }

    @PatchMapping("/{classId}/sort")
    public ApiResponse<ClassView> updateSortOrder(
            @PathVariable UUID classId,
            @Valid @RequestBody UpdateSortRequest request) {
        return ApiResponse.success(classService.updateSortOrder(classId, request), "정렬 순서가 변경되었습니다.");
    }
}
