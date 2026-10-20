package com.ramiart.admin.course.infrastructure;

import com.ramiart.admin.course.application.CourseModels.ClassGroupView;
import com.ramiart.admin.course.application.CourseModels.ClassGroupPage;
import com.ramiart.admin.course.application.CourseModels.ClassGroupSummary;
import com.ramiart.admin.course.application.CourseModels.ClassGroupWrite;
import com.ramiart.admin.course.application.CourseModels.CourseDetail;
import com.ramiart.admin.course.application.CourseModels.CoursePage;
import com.ramiart.admin.course.application.CourseModels.CourseSummary;
import com.ramiart.admin.course.application.CourseModels.CourseWrite;
import com.ramiart.admin.course.application.CourseModels.OccupancySummary;
import java.time.LocalDate;
import com.ramiart.admin.course.application.CourseRepository;
import com.ramiart.admin.course.application.CourseException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCourseRepository implements CourseRepository {

    private static final String COURSE_FILTER = """
            where (cast(:keyword as text) is null or c.name ilike '%' || cast(:keyword as text) || '%')
              and (cast(:active as boolean) is null or c.active = cast(:active as boolean))
            """;

    private static final String CLASS_GROUP_FILTER = """
            where (cast(:course_id as uuid) is null or g.course_id = cast(:course_id as uuid))
              and (cast(:keyword as text) is null or g.name ilike '%' || cast(:keyword as text) || '%')
              and (cast(:status as text) is null or g.status = cast(:status as text))
            """;

    private final JdbcClient jdbcClient;

    public JdbcCourseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public CoursePage findCourses(String keyword, Boolean active, int page, int size) {
        long total = jdbcClient.sql("select count(*) from course c " + COURSE_FILTER)
                .param("keyword", keyword).param("active", active).query(Long.class).single();
        List<CourseSummary> content = jdbcClient.sql("""
                        select c.id, c.code, c.name, c.description, c.age_guide,
                               c.session_duration_minutes, c.weekly_sessions, c.display_order,
                               c.active, c.version, count(g.id)::int class_group_count,
                               count(g.id) filter (where g.status = 'ACTIVE')::int active_class_group_count
                        from course c
                        left join class_group g on g.course_id = c.id
                        """ + COURSE_FILTER + """
                        group by c.id
                        order by c.display_order, c.id
                        limit :size offset :offset
                        """)
                .param("keyword", keyword).param("active", active).param("size", size).param("offset", page * size)
                .query(this::mapSummary).list();
        return new CoursePage(content, total, page, size);
    }

    @Override
    public ClassGroupPage findClassGroups(UUID courseId, String keyword, String status, int page, int size) {
        long total = jdbcClient.sql("select count(*) from class_group g " + CLASS_GROUP_FILTER)
                .param("course_id", courseId).param("keyword", keyword).param("status", status)
                .query(Long.class).single();
        List<ClassGroupSummary> content = jdbcClient.sql("""
                        select g.*, c.name course_name
                        from (
                        """ + classGroupSelect() + """
                        ) g
                        join course c on c.id = g.course_id
                        """ + CLASS_GROUP_FILTER + """
                        order by c.display_order, g.name, g.id
                        limit :size offset :offset
                        """)
                .param("course_id", courseId).param("keyword", keyword).param("status", status)
                .param("size", size).param("offset", page * size)
                .query(this::mapClassGroupSummary).list();
        return new ClassGroupPage(content, total, page, size);
    }

    @Override
    public Optional<CourseDetail> findCourse(UUID courseId) {
        Optional<CourseDetail> course = jdbcClient.sql("""
                        select id, code, name, description, age_guide, session_duration_minutes, weekly_sessions,
                               display_order, active, version
                        from course where id = :id
                        """)
                .param("id", courseId)
                .query((rs, rowNum) -> new CourseDetail(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("age_guide"),
                        (Integer) rs.getObject("session_duration_minutes"), (Integer) rs.getObject("weekly_sessions"),
                        rs.getInt("display_order"),
                        rs.getBoolean("active"), rs.getLong("version"), List.of()))
                .optional();
        return course.map(value -> new CourseDetail(value.id(), value.code(), value.name(), value.description(),
                value.ageGuide(), value.sessionDurationMinutes(), value.weeklySessions(), value.displayOrder(),
                value.active(), value.version(), findGroups(courseId)));
    }

    @Override
    public Optional<ClassGroupView> findClassGroup(UUID classGroupId) {
        return jdbcClient.sql(classGroupSelect() + " where g.id = :id")
                .param("id", classGroupId).query(this::mapClassGroup).optional();
    }

    @Override
    public Optional<OccupancySummary> findOccupancy(UUID classGroupId, LocalDate from, LocalDate to) {
        return jdbcClient.sql("""
                        select g.id, g.capacity,
                               (select count(distinct a.student_id)::int
                                  from student_schedule_assignment a
                                  join schedule_slot s on s.id = a.schedule_slot_id
                                 where s.class_group_id = g.id and s.status = 'ACTIVE'
                                   and a.effective_from <= :to
                                   and coalesce(a.effective_to, 'infinity'::date) >= :from) regular_occupancy,
                               (select count(*)::int from makeup_case m
                                  join attendance_session x on x.id = m.reserved_session_id
                                 where x.class_group_id = g.id and m.status = 'RESERVED'
                                   and x.attendance_date between :from and :to) reserved_makeup_count
                        from class_group g where g.id = :id
                        """)
                .param("id", classGroupId).param("from", from).param("to", to)
                .query((rs, rowNum) -> {
                    int regular = rs.getInt("regular_occupancy");
                    int makeup = rs.getInt("reserved_makeup_count");
                    int capacity = rs.getInt("capacity");
                    return new OccupancySummary(classGroupId, from, to, regular, makeup,
                            Math.max(0, capacity - regular - makeup));
                }).optional();
    }

    private List<ClassGroupView> findGroups(UUID courseId) {
        return jdbcClient.sql(classGroupSelect() + " where g.course_id = :course_id order by g.name, g.id")
                .param("course_id", courseId).query(this::mapClassGroup).list();
    }

    @Override
    public void insertCourse(UUID id, CourseWrite command, UUID actorId) {
        jdbcClient.sql("""
                        insert into course (id, code, name, description, age_guide, session_duration_minutes,
                            weekly_sessions, display_order, active, created_by, updated_by)
                        values (:id, :code, :name, :description, :age_guide, :duration, :weekly,
                            :display_order, :active, :actor_id, :actor_id)
                        """)
                .param("id", id).param("code", command.code()).param("name", command.name())
                .param("description", command.description()).param("age_guide", command.ageGuide())
                .param("duration", command.sessionDurationMinutes()).param("weekly", command.weeklySessions())
                .param("display_order", command.displayOrder()).param("active", command.active())
                .param("actor_id", actorId).update();
    }

    @Override
    public int updateCourse(UUID id, CourseWrite command, UUID actorId) {
        return jdbcClient.sql("""
                        update course set code = :code, name = :name, description = :description,
                            age_guide = :age_guide, session_duration_minutes = :duration, weekly_sessions = :weekly,
                            display_order = :display_order, active = :active,
                            version = version + 1, updated_by = :actor_id
                        where id = :id and version = :version
                        """)
                .param("id", id).param("code", command.code()).param("name", command.name())
                .param("description", command.description()).param("age_guide", command.ageGuide())
                .param("duration", command.sessionDurationMinutes()).param("weekly", command.weeklySessions())
                .param("display_order", command.displayOrder()).param("active", command.active())
                .param("version", command.version()).param("actor_id", actorId).update();
    }

    @Override
    public void insertClassGroup(UUID id, UUID courseId, String code, ClassGroupWrite command, UUID actorId) {
        jdbcClient.sql("""
                        insert into class_group (
                            id, course_id, code, name, room_code, capacity, waitlist_enabled,
                            makeup_valid_days, starts_on, ends_on, status, created_by, updated_by
                        ) values (
                            :id, :course_id, :code, :name, :room_code, :capacity, :waitlist_enabled,
                            :makeup_valid_days, :starts_on, :ends_on, :status, :actor_id, :actor_id
                        )
                        """)
                .param("id", id).param("course_id", courseId).param("code", code)
                .param("name", command.name()).param("room_code", command.roomCode())
                .param("capacity", command.capacity()).param("waitlist_enabled", command.waitlistEnabled())
                .param("makeup_valid_days", command.makeupValidDays()).param("starts_on", command.startsOn())
                .param("ends_on", command.endsOn()).param("status", command.status())
                .param("actor_id", actorId).update();
    }

    @Override
    public int updateClassGroup(UUID id, ClassGroupWrite command, UUID actorId) {
        return jdbcClient.sql("""
                        update class_group set name = :name, room_code = :room_code,
                            capacity = :capacity, waitlist_enabled = :waitlist_enabled,
                            makeup_valid_days = :makeup_valid_days, starts_on = :starts_on,
                            ends_on = :ends_on, status = :status, version = version + 1, updated_by = :actor_id
                        where id = :id and version = :version
                        """)
                .param("id", id).param("name", command.name())
                .param("room_code", command.roomCode()).param("capacity", command.capacity())
                .param("waitlist_enabled", command.waitlistEnabled()).param("makeup_valid_days", command.makeupValidDays())
                .param("starts_on", command.startsOn()).param("ends_on", command.endsOn())
                .param("status", command.status()).param("version", command.version())
                .param("actor_id", actorId).update();
    }

    @Override
    public Optional<ClassGroupDeletionState> lockClassGroupForDeletion(UUID classGroupId) {
        return jdbcClient.sql("""
                        select g.status, g.version,
                               exists(select 1 from class_staff_assignment x where x.class_group_id = g.id)
                               or exists(select 1 from schedule_slot x where x.class_group_id = g.id)
                               or exists(select 1 from enrollment_case x where x.desired_class_group_id = g.id)
                               or exists(select 1 from schedule_override x where x.class_group_id = g.id)
                               or exists(select 1 from lesson_plan x where x.class_group_id = g.id)
                               or exists(select 1 from attendance_session x where x.class_group_id = g.id)
                               or exists(select 1 from makeup_case x where x.class_group_id = g.id) referenced
                        from class_group g where g.id = :id for update
                        """)
                .param("id", classGroupId)
                .query((rs, rowNum) -> new ClassGroupDeletionState(
                        rs.getString("status"), rs.getLong("version"), rs.getBoolean("referenced")))
                .optional();
    }

    @Override
    public Optional<CourseDeletionState> lockCourseForDeletion(UUID courseId) {
        return jdbcClient.sql("""
                        select c.code, c.version,
                               exists(select 1 from class_group g where g.course_id = c.id)
                               or exists(select 1 from inquiry i where i.interested_course_id = c.id)
                               or exists(select 1 from gallery_artwork a where a.course_id = c.id)
                               or exists(select 1 from class_program p where p.course_id = c.id) referenced
                        from course c where c.id = :id for update of c
                        """)
                .param("id", courseId)
                .query((rs, rowNum) -> new CourseDeletionState(
                        rs.getString("code"), rs.getLong("version"), rs.getBoolean("referenced")))
                .optional();
    }

    @Override
    public int deleteDraftClassGroup(UUID classGroupId, long version) {
        return jdbcClient.sql("delete from class_group where id = :id and status = 'DRAFT' and version = :version")
                .param("id", classGroupId).param("version", version).update();
    }

    @Override
    public int deleteCourse(UUID courseId, long version) {
        return jdbcClient.sql("delete from course where id = :id and version = :version")
                .param("id", courseId).param("version", version).update();
    }

    @Override
    public int findMaximumFutureOccupancy(UUID classGroupId) {
        jdbcClient.sql("select id from class_group where id = :id for update")
                .param("id", classGroupId).query(UUID.class).single();
        return jdbcClient.sql("""
                        with event_dates(day) as (
                          select current_date
                          union
                          select a.effective_from
                            from student_schedule_assignment a
                            join schedule_slot s on s.id = a.schedule_slot_id
                           where s.class_group_id = :id and s.status = 'ACTIVE'
                             and a.effective_from >= current_date
                          union
                          select x.attendance_date
                            from makeup_case m
                            join attendance_session x on x.id = m.reserved_session_id
                           where x.class_group_id = :id and m.status = 'RESERVED'
                             and x.attendance_date >= current_date
                        )
                        select coalesce(max(
                          (select count(distinct a.student_id)::int
                             from student_schedule_assignment a
                             join schedule_slot s on s.id = a.schedule_slot_id
                            where s.class_group_id = :id and s.status = 'ACTIVE'
                              and a.effective_from <= event_dates.day
                              and coalesce(a.effective_to, 'infinity'::date) >= event_dates.day)
                          +
                          (select count(*)::int
                             from makeup_case m
                             join attendance_session x on x.id = m.reserved_session_id
                            where x.class_group_id = :id and m.status = 'RESERVED'
                              and x.attendance_date = event_dates.day)
                        ), 0)::int
                        from event_dates
                        """)
                .param("id", classGroupId).query(Integer.class).single();
    }

    @Override
    public boolean courseCodeExists(String code, UUID excludedId) {
        return exists("course", code, excludedId);
    }

    @Override
    public boolean classGroupCodeExists(String code, UUID excludedId) {
        return exists("class_group", code, excludedId);
    }

    private boolean exists(String table, String code, UUID excludedId) {
        if (excludedId == null) {
            return jdbcClient.sql("select exists(select 1 from " + table + " where code = :code)")
                    .param("code", code).query(Boolean.class).single();
        }
        return jdbcClient.sql("select exists(select 1 from " + table + " where code = :code and id <> :id)")
                .param("code", code).param("id", excludedId).query(Boolean.class).single();
    }

    @Override
    public boolean hasActiveClassGroups(UUID courseId) {
        return jdbcClient.sql("select exists(select 1 from class_group where course_id = :id and status = 'ACTIVE')")
                .param("id", courseId).query(Boolean.class).single();
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
        var record = jdbcClient.sql("""
                        select request_hash, state, resource_id
                        from idempotency_record where scope = :scope and idempotency_key = :key
                        """)
                .param("scope", scope).param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"), rs.getString("state"), rs.getObject("resource_id", UUID.class)))
                .single();
        if (!record.requestHash().equals(requestHash)) throw new CourseException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(record.state()) || record.resourceId() == null) {
            throw new CourseException("IDEMPOTENCY_IN_PROGRESS");
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

    private CourseSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new CourseSummary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("age_guide"),
                (Integer) rs.getObject("session_duration_minutes"), (Integer) rs.getObject("weekly_sessions"),
                rs.getInt("display_order"),
                rs.getBoolean("active"), rs.getLong("version"), rs.getInt("class_group_count"),
                rs.getInt("active_class_group_count"));
    }

    private ClassGroupView mapClassGroup(ResultSet rs, int rowNum) throws SQLException {
        int capacity = rs.getInt("capacity");
        int regular = rs.getInt("regular_occupancy");
        int makeup = rs.getInt("reserved_makeup_count");
        String status = rs.getString("status");
        boolean referenced = rs.getBoolean("referenced");
        List<String> actions = classGroupActions(status, referenced);
        return new ClassGroupView(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("room_code"), capacity,
                rs.getBoolean("waitlist_enabled"), rs.getInt("makeup_valid_days"),
                rs.getObject("starts_on", java.time.LocalDate.class), rs.getObject("ends_on", java.time.LocalDate.class),
                status, rs.getLong("version"), regular, makeup, Math.max(0, capacity - regular - makeup),
                rs.getInt("future_slot_count"), rs.getInt("future_assignment_count"),
                rs.getString("current_lead_staff"), actions);
    }

    private ClassGroupSummary mapClassGroupSummary(ResultSet rs, int rowNum) throws SQLException {
        int regular = rs.getInt("regular_occupancy");
        int makeup = rs.getInt("reserved_makeup_count");
        String status = rs.getString("status");
        return new ClassGroupSummary(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getString("course_name"), rs.getString("name"), rs.getString("room_code"),
                rs.getInt("capacity"), rs.getBoolean("waitlist_enabled"), rs.getInt("makeup_valid_days"),
                rs.getObject("starts_on", java.time.LocalDate.class), rs.getObject("ends_on", java.time.LocalDate.class),
                status, rs.getLong("version"), regular, makeup,
                Math.max(0, rs.getInt("capacity") - regular - makeup),
                classGroupActions(status, rs.getBoolean("referenced")));
    }

    private static List<String> classGroupActions(String status, boolean referenced) {
        return switch (status) {
            case "DRAFT" -> referenced ? List.of("EDIT") : List.of("EDIT", "DELETE");
            case "ACTIVE" -> List.of("EDIT", "CLOSE");
            default -> List.of("VIEW");
        };
    }

    private static String classGroupSelect() {
        return """
                select g.*,
                       (select count(distinct a.student_id)::int
                          from student_schedule_assignment a
                          join schedule_slot s on s.id = a.schedule_slot_id
                         where s.class_group_id = g.id and s.status = 'ACTIVE'
                           and current_date between a.effective_from and coalesce(a.effective_to, 'infinity'::date)) regular_occupancy,
                       (select count(*)::int from makeup_case m
                          join attendance_session x on x.id = m.reserved_session_id
                         where x.class_group_id = g.id and m.status = 'RESERVED'
                           and x.attendance_date >= current_date) reserved_makeup_count,
                       (select count(*)::int from schedule_slot s
                         where s.class_group_id = g.id and s.status = 'ACTIVE') future_slot_count,
                       (select count(*)::int from student_schedule_assignment a
                          join schedule_slot s on s.id = a.schedule_slot_id
                         where s.class_group_id = g.id and a.effective_from > current_date) future_assignment_count,
                       (select sp.display_name from class_staff_assignment a
                         join staff_profile sp on sp.id = a.staff_profile_id
                         where a.class_group_id = g.id and a.role = 'LEAD'
                           and current_date between a.effective_from and coalesce(a.effective_to, 'infinity'::date)
                         order by a.effective_from desc limit 1) current_lead_staff,
                       exists(select 1 from class_staff_assignment x where x.class_group_id = g.id)
                       or exists(select 1 from schedule_slot x where x.class_group_id = g.id)
                       or exists(select 1 from enrollment_case x where x.desired_class_group_id = g.id)
                       or exists(select 1 from schedule_override x where x.class_group_id = g.id)
                       or exists(select 1 from lesson_plan x where x.class_group_id = g.id)
                       or exists(select 1 from attendance_session x where x.class_group_id = g.id)
                       or exists(select 1 from makeup_case x where x.class_group_id = g.id) referenced
                from class_group g
                """;
    }

    private record IdempotencyRecord(String requestHash, String state, UUID resourceId) {
    }
}
