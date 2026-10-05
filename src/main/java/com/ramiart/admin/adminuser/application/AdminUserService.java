package com.ramiart.admin.adminuser.application;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private static final Set<String> ROLES = Set.of("OWNER", "OPERATOR", "CONTENT", "FINANCE");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "LOCKED");
    private final AdminUserRepository repository;

    public AdminUserService(AdminUserRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public ListResponse list(Query input, Authentication auth) {
        UUID current = require(auth, "ADMIN_ACCOUNT_READ");
        String keyword = input.keyword() == null ? null : input.keyword().trim();
        String role = blank(input.role());
        String status = blank(input.status());
        String sort = input.sort() == null ? "displayName,asc" : input.sort();
        if (keyword != null && (keyword.length() < 2 || keyword.length() > 100)
                || role != null && !ROLES.contains(role) || status != null && !STATUSES.contains(status)
                || input.page() < 0 || input.size() != 20
                || !Set.of("displayName,asc", "displayName,desc", "lastLoginAt,asc", "lastLoginAt,desc", "updatedAt,asc", "updatedAt,desc").contains(sort))
            throw new AdminUserException("ADMIN_USER_QUERY_INVALID");
        Query query = new Query(keyword, role, status, input.page(), input.size(), sort);
        long total = repository.count(query);
        int offset = Math.multiplyExact(input.page(), input.size());
        List<Summary> items = repository.find(query, input.size(), offset).stream()
                .map(item -> actions(item, current)).toList();
        int pages = (int) Math.ceil(total / (double) input.size());
        return new ListResponse(items, input.page(), input.size(), total, pages, sort);
    }

    private Summary actions(Summary item, UUID current) {
        List<String> actions = new ArrayList<>();
        boolean self = item.id().equals(current);
        if ("ACTIVE".equals(item.status())) {
            actions.add("CHANGE_ROLE");
            if (!self) actions.add("DEACTIVATE");
        } else if ("INACTIVE".equals(item.status())) {
            actions.add("REACTIVATE");
        } else {
            actions.add("UNLOCK");
        }
        if (!self && ("ACTIVE".equals(item.status()) || "LOCKED".equals(item.status())))
            actions.add("ISSUE_TEMPORARY_PASSWORD");
        return new Summary(item.id(), item.displayName(), item.email(), item.role(), item.status(), item.lastLoginAt(),
                item.lockedUntil(), item.passwordMustChange(), item.createdAt(), item.updatedAt(), item.version(), self, List.copyOf(actions));
    }

    private UUID require(Authentication auth, String permission) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID id)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(permission)))
            throw new AdminUserException("ADMIN_ACCOUNT_READ_DENIED");
        return id;
    }
    private static String blank(String value) { if (value == null || value.isBlank()) return null; return value.trim(); }
    public static final class AdminUserException extends RuntimeException {
        private final String code;
        public AdminUserException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
