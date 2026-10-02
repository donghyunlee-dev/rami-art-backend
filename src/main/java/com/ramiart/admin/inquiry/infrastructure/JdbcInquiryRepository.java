package com.ramiart.admin.inquiry.infrastructure;

import com.ramiart.admin.inquiry.application.InquiryException;
import com.ramiart.admin.inquiry.application.InquiryModels.InquiryQuery;
import com.ramiart.admin.inquiry.application.InquiryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInquiryRepository implements InquiryRepository {
    private final JdbcClient jdbc;
    public JdbcInquiryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public boolean activeCourseExists(UUID id) {
        return jdbc.sql("select exists(select 1 from course where id=:id and active)").param("id", id).query(Boolean.class).single();
    }

    @Override public boolean coursesExist(List<UUID> ids) {
        if (ids.isEmpty()) return true;
        Integer count = jdbc.sql("select count(*) from course where id=any(cast(:ids as uuid[]))")
                .param("ids", ids.toArray(UUID[]::new)).query(Integer.class).single();
        return count == ids.stream().distinct().count();
    }

    @Override public List<CourseRecord> findCourseOptions() {
        return jdbc.sql("select id,name,active from course order by display_order,name,id")
                .query((rs,n) -> new CourseRecord(rs.getObject("id",UUID.class),rs.getString("name"),rs.getBoolean("active"))).list();
    }

    @Override public int incrementRateLimit(String type, String hash, Instant windowStart) {
        return jdbc.sql("""
                insert into inquiry_rate_limit_bucket(bucket_type,bucket_hash,window_started_at,request_count)
                values (:type,:hash,:window,1)
                on conflict(bucket_type,bucket_hash,window_started_at) do update
                   set request_count=inquiry_rate_limit_bucket.request_count+1, updated_at=statement_timestamp()
                returning request_count
                """).param("type", type).param("hash", hash).param("window", odt(windowStart)).query(Integer.class).single();
    }

    @Override public Claim claim(String scope, UUID key, String requestHash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,expires_at)
                values (:scope,:key,:hash,statement_timestamp()+interval '24 hours')
                on conflict(scope,idempotency_key) do nothing
                """).param("scope", scope).param("key", key).param("hash", requestHash).update();
        if (inserted == 1) return new Claim(true, null);
        var row = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope", scope).param("key", key).query((rs,n) -> new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if (!row[0].equals(requestHash)) throw new InquiryException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row[1])) throw new InquiryException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false, (UUID) row[2]);
    }

    @Override public void complete(String scope, UUID key, UUID resourceId, int status) {
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key and state='PROCESSING'")
                .param("resource", resourceId).param("status", status).param("scope", scope).param("key", key).update();
    }

    @Override public void insert(UUID id, byte[] name, String nameHash, byte[] phone, String phoneHash,
            String phoneLast4, UUID courseId, byte[] message, String policyVersion, Instant now) {
        jdbc.sql("""
                insert into inquiry(id,name_ciphertext,name_hash,phone_ciphertext,phone_hash,phone_last4,
                  interested_course_id,message_ciphertext,consent_policy_version,consented_at,received_at,retention_expires_at)
                values(:id,:name,:name_hash,:phone,:phone_hash,:last4,:course,:message,:policy,:now,:now,:now+interval '3 years')
                """).param("id",id).param("name",name).param("name_hash",nameHash).param("phone",phone)
                .param("phone_hash",phoneHash).param("last4",phoneLast4).param("course",courseId)
                .param("message",message).param("policy",policyVersion).param("now",odt(now)).update();
    }

    @Override public PageRecords findPage(InquiryQuery q, String keywordHash, Instant staleBefore) {
        String filter = """
                where i.received_at >= :from_inclusive and i.received_at < :to_exclusive
                  and i.status = any(cast(:statuses as text[]))
                  and (:course_empty or i.interested_course_id = any(cast(:courses as uuid[])))
                  and (:read_state='ALL' or (:read_state='READ' and i.read_at is not null) or (:read_state='UNREAD' and i.read_at is null))
                  and (:keyword_hash is null or i.name_hash=:keyword_hash or i.phone_hash=:keyword_hash)
                """;
        var base = jdbc.sql("""
                select count(*) total, count(*) filter(where i.read_at is null) unread,
                  count(*) filter(where i.status in ('RECEIVED','CONTACTING') and i.received_at<=:stale) stale
                from inquiry i
                """ + filter);
        bind(base,q,keywordHash,staleBefore);
        long[] counts = base.query((rs,n)->new long[]{rs.getLong("total"),rs.getLong("unread"),rs.getLong("stale")}).single();
        var query = jdbc.sql("""
                select i.*, c.name course_name, c.active course_active,
                  u.display_name read_by_name, coalesce(max(a.created_at),i.received_at) last_activity_at
                from inquiry i left join course c on c.id=i.interested_course_id
                left join admin_user u on u.id=i.read_by left join inquiry_activity a on a.inquiry_id=i.id
                """ + filter + """
                group by i.id,c.id,c.name,c.active,u.id,u.display_name
                order by case when i.status in ('RECEIVED','CONTACTING') then 0 else 1 end,i.received_at,i.id
                limit :size offset :offset
                """);
        bind(query,q,keywordHash,staleBefore); query.param("size",q.size()).param("offset",q.page()*q.size());
        return new PageRecords(query.query(this::mapInquiry).list(),counts[0],counts[1],counts[2]);
    }

    private void bind(JdbcClient.StatementSpec spec, InquiryQuery q, String hash, Instant stale) {
        spec.param("from_inclusive",odt(q.fromInclusive())).param("to_exclusive",odt(q.toExclusive()))
                .param("statuses",q.statuses().toArray(String[]::new)).param("course_empty",q.courseIds().isEmpty())
                .param("courses",q.courseIds().toArray(UUID[]::new)).param("read_state",q.readState())
                .param("keyword_hash",hash).param("stale",odt(stale));
    }

    @Override public Optional<InquiryRecord> find(UUID id) {
        return jdbc.sql("""
                select i.*,c.name course_name,c.active course_active,u.display_name read_by_name,
                  coalesce((select max(created_at) from inquiry_activity where inquiry_id=i.id),i.received_at) last_activity_at
                from inquiry i left join course c on c.id=i.interested_course_id left join admin_user u on u.id=i.read_by
                where i.id=:id
                """).param("id",id).query(this::mapInquiry).optional();
    }

    @Override public List<ActivityRecord> findActivities(UUID id) {
        return jdbc.sql("""
                select a.*,u.display_name created_by_name from inquiry_activity a join admin_user u on u.id=a.created_by
                where a.inquiry_id=:id order by a.created_at,a.id
                """).param("id",id).query(this::mapActivity).list();
    }

    @Override public int markRead(UUID id,long version,UUID actor,Instant now) {
        return jdbc.sql("update inquiry set read_at=:now,read_by=:actor,version=version+1 where id=:id and version=:version and read_at is null")
                .param("now",odt(now)).param("actor",actor).param("id",id).param("version",version).update();
    }

    @Override public int transition(UUID id,long version,String from,String to) {
        return jdbc.sql("update inquiry set status=:to,version=version+1 where id=:id and version=:version and status=:from")
                .param("to",to).param("id",id).param("version",version).param("from",from).update();
    }

    @Override public UUID insertActivity(UUID inquiryId,String from,String to,byte[] note,UUID actor,long version,Instant now) {
        UUID id=UUID.randomUUID();
        jdbc.sql("insert into inquiry_activity(id,inquiry_id,from_status,to_status,note_ciphertext,created_by,created_at,inquiry_version) values(:id,:inquiry,:from,:to,:note,:actor,:now,:version)")
                .param("id",id).param("inquiry",inquiryId).param("from",from).param("to",to).param("note",note)
                .param("actor",actor).param("now",odt(now)).param("version",version).update();
        return id;
    }

    @Override public Optional<ActivityRecord> findActivity(UUID id) {
        return jdbc.sql("select a.*,u.display_name created_by_name from inquiry_activity a join admin_user u on u.id=a.created_by where a.id=:id")
                .param("id",id).query(this::mapActivity).optional();
    }

    private InquiryRecord mapInquiry(ResultSet rs,int n)throws SQLException {
        UUID courseId=rs.getObject("interested_course_id",UUID.class);
        CourseRecord course=courseId==null?null:new CourseRecord(courseId,rs.getString("course_name"),rs.getBoolean("course_active"));
        return new InquiryRecord(rs.getObject("id",UUID.class),rs.getBytes("name_ciphertext"),rs.getBytes("phone_ciphertext"),
                rs.getString("phone_last4"),rs.getBytes("message_ciphertext"),course,rs.getString("status"),
                rs.getString("consent_policy_version"),instant(rs,"consented_at"),instant(rs,"received_at"),instant(rs,"read_at"),
                rs.getObject("read_by",UUID.class),rs.getString("read_by_name"),rs.getString("notification_status"),
                instant(rs,"notification_attempted_at"),rs.getLong("version"),instant(rs,"last_activity_at"));
    }
    private ActivityRecord mapActivity(ResultSet rs,int n)throws SQLException { return new ActivityRecord(rs.getObject("id",UUID.class),
            rs.getString("from_status"),rs.getString("to_status"),rs.getBytes("note_ciphertext"),
            rs.getObject("created_by",UUID.class),rs.getString("created_by_name"),instant(rs,"created_at")); }
    private static Instant instant(ResultSet rs,String name)throws SQLException { OffsetDateTime value=rs.getObject(name,OffsetDateTime.class); return value==null?null:value.toInstant(); }
    private static OffsetDateTime odt(Instant value){ return value.atOffset(ZoneOffset.UTC); }
}
