package com.rami.artstudio.classes.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rami.artstudio.classes.dto.ClassDtos.ClassView;
import com.rami.artstudio.classes.service.ClassService;
import com.rami.artstudio.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/classes")
public class PublicClassController {

    private final ClassService classService;

    public PublicClassController(ClassService classService) {
        this.classService = classService;
    }

    @GetMapping
    public ApiResponse<List<ClassView>> getClasses() {
        return ApiResponse.success(classService.getPublicClasses());
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassView> getClassDetail(@PathVariable UUID classId) {
        return ApiResponse.success(classService.getPublicClass(classId));
    }
}
