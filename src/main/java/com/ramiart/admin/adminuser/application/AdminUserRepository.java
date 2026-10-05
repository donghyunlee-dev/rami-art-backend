package com.ramiart.admin.adminuser.application;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import java.util.List;

public interface AdminUserRepository {
    long count(Query query);
    List<Summary> find(Query query, int limit, int offset);
}
