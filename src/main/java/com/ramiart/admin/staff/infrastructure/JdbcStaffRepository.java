package com.ramiart.admin.staff.infrastructure;

import com.ramiart.admin.staff.application.StaffException;
import com.ramiart.admin.staff.application.StaffModels.AdminUserOption;
import com.ramiart.admin.staff.application.StaffModels.AssignmentView;
import com.ramiart.admin.staff.application.StaffModels.AssignmentWrite;
import com.ramiart.admin.staff.application.StaffModels.ClassGroupOption;
import com.ramiart.admin.staff.application.StaffModels.StaffPage;
import com.ramiart.admin.staff.application.StaffModels.StaffSummary;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import com.ramiart.admin.staff.application.StaffRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStaffRepository implements StaffRepository {

    private static final String STAFF_FILTER = """
            where (:keyword is null or s.staff_code ilike '%' || :keyword || '%'
                   or s.name ilike '%' || :keyword || '%' or s.display_name ilike '%' || :keyword || '%')
              and (:status is null or s.status = :status)
              and (:job_title is null or s.job_title = :job_title)
            """;

    private final JdbcClient jdbcClient;

    public JdbcStaffRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public StaffPage findStaff(String keyword, String status, String jobTitle, int page, int size) {
        long total = jdbcClient.sql("select count(*) from staff_profile s " + STAFF_FILTER)
                .param("keyword", keyword).param("status", status).param("job_title", jobTitle)
                .query(Long.class).single();
        List<StaffSummary> content = jdbcClient.sql("""
                        select s.id, s.staff_code, s.display_name, s.job_title, s.phone_last4,
                               s.hired_on, s.left_on, s.status, s.version,
                               coalesce((select string_agg(distinct g.name, E'\\u001f' order by g.name)
                                 from class_staff_assignment a
                                 join class_group g on g.id = a.class_group_id
                                where a.staff_profile_id = s.id
                                  and current_date between a.effective_from
                                      and coalesce(a.effective_to, 'infinity'::date)), '') current_groups
                        from staff_profile s
                        """ + STAFF_FILTER + """
                        order by case when s.status = 'ACTIVE' then 0 else 1 end, s.display_name, s.id
                        limit :size offset :offset
                        """)
                .param("keyword", keyword).param("status", status).param("job_title", jobTitle)
                .param("size", size).param("offset", page * size)
                .query(this::mapSummary).list();
        return new StaffPage(content, total, page, size);
    }

    @Override
    public Optional<StaffRecord> findStaff(UUID staffId) {
        return jdbcClient.sql("""
                        select s.*, u.display_name admin_display_name
                        from staff_profile s
                        left join admin_user u on u.id = s.admin_user_id
                        where s.id = :id
                        """)
                .param("id", staffId).query(this::mapRecord).optional();
    }

    @Override
    public List<AssignmentView> findAssignments(UUID staffId) {
        return jdbcClient.sql("""
                        select a.id, a.class_group_id, g.code class_group_code, g.name class_group_name,
                               c.name course_name, a.role, a.effective_from, a.effective_to, a.version,
                               case when current_date < a.effective_from then 'UPCOMING'
                                    when current_date > coalesce(a.effective_to, 'infinity'::date) then 'ENDED'
                                    else 'CURRENT' end derived_status
                        from class_staff_assignment a
                        join class_group g on g.id = a.class_group_id
                        join course c on c.id = g.course_id
                        where a.staff_profile_id = :id
                        order by a.effective_from desc, c.name, g.name, a.role, a.id
                        """)
                .param("id", staffId)
                .query((rs, rowNum) -> new AssignmentView(
                        rs.getObject("id", UUID.class), rs.getObject("class_group_id", UUID.class),
                        rs.getString("class_group_code"), rs.getString("class_group_name"),
                        rs.getString("course_name"), rs.getString("role"),
                        rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                        rs.getLong("version"), rs.getString("derived_status")))
                .list();
    }

    @Override
    public List<ClassGroupOption> findClassGroupOptions() {
        return jdbcClient.sql("""
                        select g.id, g.code, g.name, c.name course_name, g.starts_on, g.ends_on, g.status
                        from class_group g join course c on c.id = g.course_id
                        order by c.display_order, c.name, g.name, g.id
                        """)
                .query((rs, rowNum) -> new ClassGroupOption(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("course_name"), rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class), rs.getString("status")))
                .list();
    }

    @Override
    public List<AdminUserOption> findAdminUserOptions() {
        return jdbcClient.sql("""
                        select u.id, u.display_name, u.email
                        from admin_user u
                        where u.status = 'ACTIVE'
                          and not exists(select 1 from staff_profile s where s.admin_user_id = u.id)
                        order by u.display_name, u.id
                        """)
                .query((rs, rowNum) -> new AdminUserOption(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("email")))
                .list();
    }

    @Override
    public void insertStaff(UUID id, StaffWrite command, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, UUID actorId) {
        jdbcClient.sql("""
                        insert into staff_profile (
                          id, staff_code, name, display_name, job_title, phone_ciphertext, phone_hash,
                          phone_last4, hired_on, left_on, status, admin_user_id, created_by, updated_by
                        ) values (
                          :id, :staff_code, :name, :display_name, :job_title, :phone_ciphertext, :phone_hash,
                          :phone_last4, :hired_on, :left_on, :status, :admin_user_id, :actor_id, :actor_id
                        )
                        """)
                .param("id", id).param("staff_code", command.staffCode()).param("name", command.name())
                .param("display_name", command.displayName()).param("job_title", command.jobTitle())
                .param("phone_ciphertext", phoneCiphertext).param("phone_hash", phoneHash)
                .param("phone_last4", phoneLast4).param("hired_on", command.hiredOn())
                .param("left_on", command.leftOn()).param("status", command.status())
                .param("admin_user_id", command.adminUserId()).param("actor_id", actorId).update();
    }

    @Override
    public int updateStaff(UUID id, StaffWrite command, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, UUID actorId) {
        return jdbcClient.sql("""
                        update staff_profile set
                          name = :name, display_name = :display_name, job_title = :job_title,
                          phone_ciphertext = :phone_ciphertext, phone_hash = :phone_hash,
                          phone_last4 = :phone_last4, hired_on = :hired_on, left_on = :left_on,
                          status = :status, admin_user_id = :admin_user_id,
                          version = version + 1, updated_by = :actor_id
                        where id = :id and version = :version
                        """)
                .param("id", id).param("name", command.name()).param("display_name", command.displayName())
                .param("job_title", command.jobTitle()).param("phone_ciphertext", phoneCiphertext)
                .param("phone_hash", phoneHash).param("phone_last4", phoneLast4)
                .param("hired_on", command.hiredOn()).param("left_on", command.leftOn())
                .param("status", command.status()).param("admin_user_id", command.adminUserId())
                .param("version", command.version()).param("actor_id", actorId).update();
    }

    @Override
    public boolean staffCodeExists(String code, UUID excludedId) {
        return exists("staff_code", code, excludedId);
    }

    @Override
    public boolean phoneHashExists(String phoneHash, UUID excludedId) {
        return exists("phone_hash", phoneHash, excludedId);
    }

    private boolean exists(String column, String value, UUID excludedId) {
        String sql = "select exists(select 1 from staff_profile where " + column + " = :value"
                + (excludedId == null ? ")" : " and id <> :id)");
        var query = jdbcClient.sql(sql).param("value", value);
        if (excludedId != null) query = query.param("id", excludedId);
        return query.query(Boolean.class).single();
    }

    @Override
    public boolean adminUserLinked(UUID adminUserId, UUID excludedId) {
        if (adminUserId == null) return false;
        return jdbcClient.sql("""
                        select exists(select 1 from staff_profile
                         where admin_user_id = :admin_user_id and (:excluded_id is null or id <> :excluded_id))
                        """)
                .param("admin_user_id", adminUserId).param("excluded_id", excludedId)
                .query(Boolean.class).single();
    }

    @Override
    public boolean hasAssignmentAfter(UUID staffId, LocalDate date) {
        return jdbcClient.sql("""
                        select exists(select 1 from class_staff_assignment
                         where staff_profile_id = :id
                           and coalesce(effective_to, 'infinity'::date) > :date)
                        """)
                .param("id", staffId).param("date", date).query(Boolean.class).single();
    }

    @Override
    public int closeAssignmentsAfter(UUID staffId, LocalDate date, UUID actorId) {
        int removed = jdbcClient.sql("""
                        delete from class_staff_assignment
                        where staff_profile_id = :id and effective_from > :date
                        """)
                .param("id", staffId).param("date", date).update();
        int closed = jdbcClient.sql("""
                        update class_staff_assignment
                        set effective_to = :date, version = version + 1, updated_by = :actor_id
                        where staff_profile_id = :id and effective_from <= :date
                          and coalesce(effective_to, 'infinity'::date) > :date
                        """)
                .param("id", staffId).param("date", date).param("actor_id", actorId).update();
        return removed + closed;
    }

    @Override
    public int replaceAssignments(UUID staffId, long staffVersion, List<AssignmentWrite> assignments, UUID actorId) {
        int versionUpdated = jdbcClient.sql("""
                        update staff_profile set version = version + 1, updated_by = :actor_id
                        where id = :id and version = :version
                        """)
                .param("id", staffId).param("version", staffVersion).param("actor_id", actorId).update();
        if (versionUpdated == 0) return 0;
        List<UUID> existingIds = jdbcClient.sql(
                        "select id from class_staff_assignment where staff_profile_id = :id for update")
                .param("id", staffId).query(UUID.class).list();
        var retainedIds = new HashSet<UUID>();
        for (AssignmentWrite assignment : assignments) {
            if (assignment.id() == null) {
                jdbcClient.sql("""
                            insert into class_staff_assignment (
                              id, class_group_id, staff_profile_id, role, effective_from, effective_to,
                              created_by, updated_by
                            ) values (
                              :id, :class_group_id, :staff_id, :role, :effective_from, :effective_to,
                              :actor_id, :actor_id
                            )
                            """)
                        .param("id", UUID.randomUUID()).param("class_group_id", assignment.classGroupId())
                        .param("staff_id", staffId).param("role", assignment.role())
                        .param("effective_from", assignment.effectiveFrom())
                        .param("effective_to", assignment.effectiveTo()).param("actor_id", actorId).update();
            } else {
                retainedIds.add(assignment.id());
                if (assignmentMatches(staffId, assignment)) continue;
                int updated = jdbcClient.sql("""
                                update class_staff_assignment set
                                  class_group_id = :class_group_id, role = :role,
                                  effective_from = :effective_from, effective_to = :effective_to,
                                  version = version + 1, updated_by = :actor_id
                                where id = :id and staff_profile_id = :staff_id and version = :version
                                """)
                        .param("id", assignment.id()).param("staff_id", staffId)
                        .param("class_group_id", assignment.classGroupId()).param("role", assignment.role())
                        .param("effective_from", assignment.effectiveFrom())
                        .param("effective_to", assignment.effectiveTo()).param("version", assignment.version())
                        .param("actor_id", actorId).update();
                if (updated == 0) return 0;
            }
        }
        for (UUID existingId : existingIds) {
            if (!retainedIds.contains(existingId)) {
                jdbcClient.sql("delete from class_staff_assignment where id = :id and staff_profile_id = :staff_id")
                        .param("id", existingId).param("staff_id", staffId).update();
            }
        }
        return 1;
    }

    private boolean assignmentMatches(UUID staffId, AssignmentWrite assignment) {
        return jdbcClient.sql("""
                        select exists(select 1 from class_staff_assignment
                         where id = :id and staff_profile_id = :staff_id and version = :version
                           and class_group_id = :class_group_id and role = :role
                           and effective_from = :effective_from
                           and effective_to is not distinct from :effective_to)
                        """)
                .param("id", assignment.id()).param("staff_id", staffId).param("version", assignment.version())
                .param("class_group_id", assignment.classGroupId()).param("role", assignment.role())
                .param("effective_from", assignment.effectiveFrom()).param("effective_to", assignment.effectiveTo())
                .query(Boolean.class).single();
    }

    @Override
    public IdempotencyClaim claim(String scope, UUID key, String requestHash) {
        int inserted = jdbcClient.sql("""
                        insert into idempotency_record (scope, idempotency_key, request_hash, expires_at)
                        values (:scope, :key, :request_hash, statement_timestamp() + interval '24 hours')
                        on conflict (scope, idempotency_key) do nothing
                        """)
                .param("scope", scope).param("key", key).param("request_hash", requestHash).update();
        if (inserted == 1) return new IdempotencyClaim(true, null);
        IdempotencyRecord record = jdbcClient.sql("""
                        select request_hash, state, resource_id
                        from idempotency_record where scope = :scope and idempotency_key = :key
                        """)
                .param("scope", scope).param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"), rs.getString("state"), rs.getObject("resource_id", UUID.class)))
                .single();
        if (!record.requestHash().equals(requestHash)) throw new StaffException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(record.state()) || record.resourceId() == null) {
            throw new StaffException("IDEMPOTENCY_IN_PROGRESS");
        }
        return new IdempotencyClaim(false, record.resourceId());
    }

    @Override
    public void complete(String scope, UUID key, UUID resourceId, int responseStatus) {
        jdbcClient.sql("""
                        update idempotency_record
                        set state = 'COMPLETED', resource_id = :resource_id, response_status = :response_status
                        where scope = :scope and idempotency_key = :key and state = 'PROCESSING'
                        """)
                .param("resource_id", resourceId).param("response_status", responseStatus)
                .param("scope", scope).param("key", key).update();
    }

    private StaffSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        String currentGroups = rs.getString("current_groups");
        List<String> groups = currentGroups == null || currentGroups.isEmpty()
                ? List.of() : Arrays.asList(currentGroups.split("\\u001f", -1));
        return new StaffSummary(
                rs.getObject("id", UUID.class), rs.getString("staff_code"), rs.getString("display_name"),
                rs.getString("job_title"), rs.getString("phone_last4"),
                rs.getObject("hired_on", LocalDate.class), rs.getObject("left_on", LocalDate.class),
                rs.getString("status"), groups, rs.getLong("version"));
    }

    private StaffRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new StaffRecord(
                rs.getObject("id", UUID.class), rs.getString("staff_code"), rs.getString("name"),
                rs.getString("display_name"), rs.getString("job_title"), rs.getBytes("phone_ciphertext"),
                rs.getString("phone_hash"), rs.getString("phone_last4"),
                rs.getObject("hired_on", LocalDate.class), rs.getObject("left_on", LocalDate.class),
                rs.getString("status"), rs.getObject("admin_user_id", UUID.class),
                rs.getString("admin_display_name"), rs.getLong("version"));
    }

    private record IdempotencyRecord(String requestHash, String state, UUID resourceId) {
    }
}
