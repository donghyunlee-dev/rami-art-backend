package com.ramiart.admin.adminuser.api;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import com.ramiart.admin.adminuser.application.AdminUserService;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public final class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service = service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<ListResponse>> list(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String role, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="displayName,asc") String sort, Authentication auth, HttpServletRequest request) {
        var result = service.list(new Query(keyword, role, status, page, size, sort), auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }
}
