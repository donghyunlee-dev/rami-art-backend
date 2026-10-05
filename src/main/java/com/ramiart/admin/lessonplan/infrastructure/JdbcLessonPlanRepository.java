package com.ramiart.admin.lessonplan.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.lessonplan.application.LessonPlanModels.*;
import com.ramiart.admin.lessonplan.application.LessonPlanRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLessonPlanRepository implements LessonPlanRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcLessonPlanRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override public Optional<Group> group(UUID classGroupId) {
        return jdbc.sql("""
                select g.id,g.code,g.name,g.course_id,c.name course_name
                  from class_group g join course c on c.id=g.course_id
                 where g.id=:id and g.status='ACTIVE'
                """).param("id", classGroupId).query((r, n) -> new Group(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("name"), r.getObject("course_id", UUID.class), r.getString("course_name"))).optional();
    }

    @Override public Optional<Group> groupForPlan(UUID planId) {
        return jdbc.sql("""
                select g.id,g.code,g.name,g.course_id,c.name course_name
                  from lesson_plan p join class_group g on g.id=p.class_group_id join course c on c.id=g.course_id
                 where p.id=:id
                """).param("id", planId).query((r, n) -> new Group(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("name"), r.getObject("course_id", UUID.class), r.getString("course_name"))).optional();
    }

    @Override public Optional<String> monthForPlan(UUID planId) {
        return jdbc.sql("select year_month from lesson_plan where id=:id").param("id", planId).query(String.class).optional();
    }

    @Override public String adminDisplayName(UUID actorId) {
        return jdbc.sql("select display_name from admin_user where id=:id").param("id", actorId).query(String.class).optional().orElse("관리자");
    }

    @Override public boolean isOwner(UUID actorId) {
        return jdbc.sql("""
                select exists(select 1 from admin_user_role ur join admin_role r on r.id=ur.admin_role_id
                               where ur.admin_user_id=:actor and r.code='OWNER' and r.active)
                """).param("actor", actorId).query(Boolean.class).single();
    }

    @Override public boolean managesMonth(UUID actorId, UUID classGroupId, LocalDate monthStart, LocalDate monthEnd) {
        return jdbc.sql("""
                select exists(
                  select 1 from class_staff_assignment a join staff_profile s on s.id=a.staff_profile_id
                   where s.admin_user_id=:actor and s.status='ACTIVE' and a.class_group_id=:group
                     and a.effective_from<=:start and coalesce(a.effective_to,'infinity'::date)>=:end
                )
                """).param("actor", actorId).param("group", classGroupId).param("start", monthStart)
                .param("end", monthEnd).query(Boolean.class).single();
    }

    @Override public Optional<ScheduleSnapshot> scheduleSnapshot(UUID classGroupId, String month, boolean lock) {
        YearMonth selected = YearMonth.parse(month);
        var statement = jdbc.sql("select revision from monthly_schedule where year_month=:month and status='PUBLISHED'"
                + (lock ? " for update" : "")).param("month", month);
        Optional<Integer> revision = statement.query(Integer.class).optional();
        if (revision.isEmpty()) return Optional.empty();
        List<LocalDate> dates = jdbc.sql("""
                with published as (
                  select id from monthly_schedule where year_month=:month and status='PUBLISHED'
                ), regular_dates as (
                  select generated.day::date as lesson_date
                    from published p
                    join monthly_schedule_item i on i.monthly_schedule_id=p.id
                    join schedule_slot slot on slot.id=i.schedule_slot_id
                    join class_group group_data on group_data.id=slot.class_group_id
                    cross join generate_series(cast(:start as date),cast(:end as date),interval '1 day') generated(day)
                   where slot.class_group_id=:group
                     and extract(isodow from generated.day)::integer=i.day_of_week
                     and generated.day::date>=group_data.starts_on
                     and (group_data.ends_on is null or generated.day::date<=group_data.ends_on)
                     and not exists(select 1 from schedule_override o where o.monthly_schedule_id=p.id
                           and o.schedule_item_id=i.id and o.target_date=generated.day::date and o.type='CANCEL')
                ), makeup_dates as (
                  select o.target_date as lesson_date from published p join schedule_override o on o.monthly_schedule_id=p.id
                   left join monthly_schedule_item i on i.id=o.schedule_item_id
                   left join schedule_slot slot on slot.id=i.schedule_slot_id
                   join class_group group_data on group_data.id=coalesce(o.class_group_id,slot.class_group_id)
                   where o.type='MAKEUP' and o.target_date between cast(:start as date) and cast(:end as date)
                     and (o.class_group_id=:group or slot.class_group_id=:group)
                     and o.target_date>=group_data.starts_on
                     and (group_data.ends_on is null or o.target_date<=group_data.ends_on)
                )
                select distinct lesson_date from (select lesson_date from regular_dates union all select lesson_date from makeup_dates) dates
                 order by lesson_date
                """).param("month", month).param("start", selected.atDay(1)).param("end", selected.atEndOfMonth())
                .param("group", classGroupId).query(LocalDate.class).list();
        return Optional.of(new ScheduleSnapshot(revision.get(), dates));
    }

    @Override public Optional<Plan> findPlan(UUID classGroupId, String month, String status, boolean lock) {
        return findOne("p.class_group_id=:group and p.year_month=:month and p.status=:status", statement -> statement
                .param("group", classGroupId).param("month", month).param("status", status), lock);
    }

    @Override public Optional<Plan> findPlanById(UUID planId, boolean lock) {
        return findOne("p.id=:id", statement -> statement.param("id", planId), lock);
    }

    private Optional<Plan> findOne(String where,
            java.util.function.Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind, boolean lock) {
        var query = jdbc.sql("""
                select p.id,p.revision,p.status,p.version,p.based_on_plan_id,p.change_summary,p.published_by,
                       a.display_name published_by_name,p.published_at
                  from lesson_plan p left join admin_user a on a.id=p.published_by where """ + " " + where + (lock ? " for update of p" : ""));
        Object[] header = bind.apply(query).query((r, n) -> new Object[] {
                r.getObject("id", UUID.class), r.getInt("revision"), r.getString("status"), r.getLong("version"),
                r.getObject("based_on_plan_id", UUID.class), r.getString("change_summary"),
                r.getObject("published_by", UUID.class), r.getString("published_by_name"), instant(r, "published_at")
        }).optional().orElse(null);
        if (header == null) return Optional.empty();
        UUID id = (UUID) header[0];
        return Optional.of(new Plan(id, (Integer) header[1], (String) header[2], (Long) header[3], (UUID) header[4],
                (String) header[5], (UUID) header[6], (String) header[7], (Instant) header[8], items(id)));
    }

    @Override public int nextRevision(UUID classGroupId, String month) {
        return jdbc.sql("select coalesce(max(revision),0)+1 from lesson_plan where class_group_id=:group and year_month=:month")
                .param("group", classGroupId).param("month", month).query(Integer.class).single();
    }

    @Override public Plan insertDraft(UUID classGroupId, String month, int revision, UUID basedOnPlanId, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into lesson_plan(id,class_group_id,year_month,revision,status,based_on_plan_id,created_by)
                values(:id,:group,:month,:revision,'DRAFT',:based,:actor)
                """).param("id", id).param("group", classGroupId).param("month", month).param("revision", revision)
                .param("based", basedOnPlanId).param("actor", actorId).update();
        return findPlanById(id, false).orElseThrow();
    }

    @Override public int updateDraft(UUID planId, long version) {
        return jdbc.sql("update lesson_plan set version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("id", planId).param("version", version).update();
    }

    @Override public void replaceItems(UUID planId, List<ItemWrite> items) {
        jdbc.sql("delete from lesson_plan_item where lesson_plan_id=:id").param("id", planId).update();
        for (ItemWrite item : items) {
            jdbc.sql("""
                    insert into lesson_plan_item(id,lesson_plan_id,planned_date,sequence,title,objectives,activities,materials,preparations,internal_note)
                    values(:id,:plan,:date,:sequence,:title,cast(:objectives as jsonb),cast(:activities as jsonb),cast(:materials as jsonb),cast(:preparations as jsonb),:note)
                    """).param("id", item.id()).param("plan", planId).param("date", item.plannedDate())
                    .param("sequence", item.sequence()).param("title", item.title())
                    .param("objectives", json(item.objectives())).param("activities", json(item.activities()))
                    .param("materials", json(item.materials())).param("preparations", json(item.preparations()))
                    .param("note", item.internalNote()).update();
        }
    }

    @Override public int archivePublished(UUID classGroupId, String month) {
        return jdbc.sql("update lesson_plan set status='ARCHIVED' where class_group_id=:group and year_month=:month and status='PUBLISHED'")
                .param("group", classGroupId).param("month", month).update();
    }

    @Override public int publish(UUID planId, long version, String changeSummary, UUID actorId, Instant publishedAt) {
        return jdbc.sql("""
                update lesson_plan set status='PUBLISHED',change_summary=:summary,published_by=:actor,published_at=:at,version=version+1
                 where id=:id and status='DRAFT' and version=:version
                """).param("summary", changeSummary).param("actor", actorId).param("at", publishedAt.atOffset(java.time.ZoneOffset.UTC))
                .param("id", planId).param("version", version).update();
    }

    @Override public boolean claimIdempotency(String scope, UUID key, String hash) {
        try {
            jdbc.sql("""
                    insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at)
                    values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')
                    """).param("scope", scope).param("key", key).param("hash", hash).update();
            return true;
        } catch (DuplicateKeyException exception) { return false; }
    }

    @Override public Optional<String> idempotencyHash(String scope, UUID key) {
        return jdbc.sql("select request_hash from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope", scope).param("key", key).query(String.class).optional();
    }

    @Override public Optional<UUID> idempotencyResource(String scope, UUID key) {
        return jdbc.sql("select resource_id from idempotency_record where scope=:scope and idempotency_key=:key and state='COMPLETED'")
                .param("scope", scope).param("key", key).query(UUID.class).optional();
    }

    @Override public void completeIdempotency(String scope, UUID key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status
                 where scope=:scope and idempotency_key=:key
                """).param("resource", resourceId).param("status", status).param("scope", scope).param("key", key).update();
    }

    private List<ItemWrite> items(UUID planId) {
        return jdbc.sql("""
                select id,planned_date,sequence,title,objectives::text,activities::text,materials::text,preparations::text,internal_note
                  from lesson_plan_item where lesson_plan_id=:id order by planned_date,sequence,id
                """).param("id", planId).query((r, n) -> new ItemWrite(r.getObject("id", UUID.class),
                r.getObject("planned_date", LocalDate.class), r.getInt("sequence"), r.getString("title"),
                strings(r, "objectives"), strings(r, "activities"), strings(r, "materials"), strings(r, "preparations"),
                r.getString("internal_note"))).list();
    }

    private List<String> strings(ResultSet result, String column) throws SQLException {
        try { return objectMapper.readValue(result.getString(column), STRINGS); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Stored lesson plan JSON is invalid", exception); }
    }

    private String json(List<String> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Unable to encode lesson plan values", exception); }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
