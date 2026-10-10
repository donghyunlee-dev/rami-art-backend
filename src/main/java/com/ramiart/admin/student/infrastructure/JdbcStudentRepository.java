package com.ramiart.admin.student.infrastructure;

import com.ramiart.admin.student.application.StudentException;
import com.ramiart.admin.student.application.StudentModels.*;
import com.ramiart.admin.student.application.StudentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStudentRepository implements StudentRepository {
    private final JdbcClient jdbc;
    public JdbcStudentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public StudentPage findStudents(String keyword, String className, List<String> statuses, LocalDate from, LocalDate to,
            String birthdayFrom, String birthdayTo, int page, int size, String sort) {
        String order = switch (sort) {
            case "studentName,desc" -> "s.student_name_search desc, s.id asc";
            case "joinedAt,asc" -> "s.joined_at asc, s.id asc";
            case "joinedAt,desc" -> "s.joined_at desc, s.id asc";
            case "updatedAt,asc" -> "s.updated_at asc, s.id asc";
            case "updatedAt,desc" -> "s.updated_at desc, s.id asc";
            default -> "s.student_name_search asc, s.id asc";
        };
        StringBuilder where = new StringBuilder(" where s.status in (:statuses)");
        Map<String,Object> params = new HashMap<>(); params.put("statuses", statuses);
        if (keyword != null) { where.append(" and (s.student_name ilike :keyword or s.school_name ilike :keyword)"); params.put("keyword", "%" + keyword + "%"); }
        if (className != null) {
            where.append(" and exists (select 1 from student_schedule_assignment a join schedule_slot ss on ss.id=a.schedule_slot_id join class_group g on g.id=ss.class_group_id where a.student_id=s.id and current_date between a.effective_from and coalesce(a.effective_to,'infinity'::date) and g.name ilike :class_name)");
            params.put("class_name", "%" + className + "%");
        }
        if (from != null) { where.append(" and s.joined_at >= :joined_from"); params.put("joined_from", from); }
        if (to != null) { where.append(" and s.joined_at <= :joined_to"); params.put("joined_to", to); }
        if (birthdayFrom != null) {
            String mmdd = "to_char(s.birthday, 'MM-DD')";
            if (birthdayFrom.compareTo(birthdayTo) <= 0) where.append(" and s.birthday is not null and ").append(mmdd).append(" between :birthday_from and :birthday_to");
            else where.append(" and s.birthday is not null and (").append(mmdd).append(" >= :birthday_from or ").append(mmdd).append(" <= :birthday_to)");
            params.put("birthday_from", birthdayFrom); params.put("birthday_to", birthdayTo);
        }
        long total = query("select count(*) from student s" + where, params).query(Long.class).single();
        String sql = """
                select s.id,s.student_name,s.school_name,to_char(s.birthday,'MM-DD') birthday_month_day,
                  s.status,s.joined_at,s.version,count(distinct a.schedule_slot_id) lesson_count
                from student s left join student_schedule_assignment a on a.student_id=s.id
                  and current_date between a.effective_from and coalesce(a.effective_to,'infinity'::date)
                """ + where + " group by s.id order by " + order + " limit :limit offset :offset";
        params.put("limit", size); params.put("offset", page * size);
        List<StudentSummary> items = query(sql, params).query(this::summary).list();
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new StudentPage(items, new PageInfo(page,size,total,pages,page==0,page+1>=pages),
                Map.of("keyword", keyword == null ? "" : keyword, "className", className == null ? "" : className,
                        "statuses", statuses, "sort", sort));
    }

    public Optional<StudentRecord> findStudent(UUID id) {
        return jdbc.sql("""
                select s.*,e.id enrollment_case_id,h.changed_at,h.reason from student s
                left join enrollment_case e on e.student_id=s.id
                left join lateral (select changed_at,reason from student_status_history where student_id=s.id order by changed_at desc,id desc limit 1) h on true
                where s.id=:id
                """).param("id",id).query(this::student).optional();
    }
    public int lessonCount(UUID id) { return jdbc.sql("""
            select count(distinct schedule_slot_id) from student_schedule_assignment
            where student_id=:id and current_date between effective_from and coalesce(effective_to,'infinity'::date)
            """).param("id",id).query(Integer.class).single(); }
    public List<GuardianRecord> findGuardians(UUID id) { return jdbc.sql("select * from guardian_contact where student_id=:id order by display_order")
            .param("id",id).query(this::guardian).list(); }
    public boolean guardianReferenced(UUID id) { return jdbc.sql("""
            select exists(select 1 from student_consent where guardian_contact_id=:id)
                or exists(select 1 from notification_message where guardian_contact_id=:id)
            """).param("id",id).query(Boolean.class).single(); }
    public List<StudentSummary> findDuplicateCandidates(String nameSearch, LocalDate birthday, List<String> hashes) {
        if (birthday == null && hashes.isEmpty()) return List.of();
        return jdbc.sql("""
                select distinct s.id,s.student_name,s.school_name,to_char(s.birthday,'MM-DD') birthday_month_day,
                  s.status,s.joined_at,s.version,0 lesson_count
                from student s left join guardian_contact g on g.student_id=s.id
                where (cast(:birthday as date) is not null and s.student_name_search=:name_search and s.birthday=cast(:birthday as date))
                   or (cardinality(cast(:hashes as text[])) > 0 and g.phone_hash=any(cast(:hashes as text[])))
                order by s.student_name,s.id limit 10
                """).param("birthday",birthday).param("name_search",nameSearch)
                .param("hashes", hashes.toArray(String[]::new)).query(this::summary).list();
    }
    public void insertStudent(UUID id, StudentCreate c, String search, UUID actor) { jdbc.sql("""
            insert into student(id,student_name,student_name_search,school_name,birthday,status,joined_at,created_by,updated_by)
            values(:id,:name,:search,:school,:birthday,'ACTIVE',:joined,:actor,:actor)
            """).param("id",id).param("name",c.studentName()).param("search",search).param("school",c.schoolName())
            .param("birthday",c.birthday()).param("joined",c.joinedAt()).param("actor",actor).update(); }
    public void insertGuardian(UUID studentId, GuardianWrite g, UUID id, byte[] pc, String ph, String last4,
            byte[] ec, String eh, String domain) { jdbc.sql("""
            insert into guardian_contact(id,student_id,name,relationship,relationship_detail,phone_ciphertext,phone_hash,phone_last4,
              email_ciphertext,email_hash,email_domain,preferred_channel,primary_contact,display_order)
            values(:id,:student,:name,:relationship,:detail,:pc,:ph,:last4,:ec,:eh,:domain,:channel,:primary,:display)
            """).param("id",id).param("student",studentId).param("name",g.name()).param("relationship",g.relationship())
            .param("detail",g.relationshipDetail()).param("pc",pc).param("ph",ph).param("last4",last4)
            .param("ec",ec).param("eh",eh).param("domain",domain).param("channel",g.preferredChannel())
            .param("primary",g.primaryContact()).param("display",g.displayOrder()).update(); }
    public int updateStudent(UUID id, StudentUpdate c, String search, UUID actor) { return jdbc.sql("""
            update student set student_name=:name,student_name_search=:search,school_name=:school,birthday=:birthday,
              joined_at=:joined,updated_by=:actor,version=version+1 where id=:id and version=:version
            """).param("name",c.studentName()).param("search",search).param("school",c.schoolName())
            .param("birthday",c.birthday()).param("joined",c.joinedAt()).param("actor",actor).param("id",id).param("version",c.version()).update(); }
    public void replaceGuardians(UUID studentId, List<StoredGuardian> values) {
        List<UUID> keep = values.stream().map(StoredGuardian::id).toList();
        if (keep.isEmpty()) jdbc.sql("delete from guardian_contact where student_id=:student").param("student",studentId).update();
        else jdbc.sql("delete from guardian_contact where student_id=:student and id not in (:ids)").param("student",studentId).param("ids",keep).update();
        jdbc.sql("update guardian_contact set primary_contact=false,display_order=display_order+100 where student_id=:student")
                .param("student",studentId).update();
        for (StoredGuardian s: values) {
            GuardianWrite g=s.value();
            int updated=jdbc.sql("""
              update guardian_contact set name=:name,relationship=:relationship,relationship_detail=:detail,
                phone_ciphertext=:pc,phone_hash=:ph,phone_last4=:last4,email_ciphertext=:ec,email_hash=:eh,
                email_domain=:domain,preferred_channel=:channel,primary_contact=:primary,display_order=:display
              where id=:id and student_id=:student
              """).param("name",g.name()).param("relationship",g.relationship()).param("detail",g.relationshipDetail())
              .param("pc",s.phoneCiphertext()).param("ph",s.phoneHash()).param("last4",s.phoneLast4())
              .param("ec",s.emailCiphertext()).param("eh",s.emailHash()).param("domain",s.emailDomain())
              .param("channel",g.preferredChannel()).param("primary",g.primaryContact()).param("display",g.displayOrder())
              .param("id",s.id()).param("student",studentId).update();
            if(updated==0) insertGuardian(studentId,g,s.id(),s.phoneCiphertext(),s.phoneHash(),s.phoneLast4(),s.emailCiphertext(),s.emailHash(),s.emailDomain());
        }
    }
    public int updateStatus(UUID id,long version,String status,UUID actor) { return jdbc.sql("update student set status=:status,updated_by=:actor,version=version+1 where id=:id and version=:version")
            .param("status",status).param("actor",actor).param("id",id).param("version",version).update(); }
    public UUID insertStatusHistory(UUID student,String from,String to,LocalDate date,String reason,UUID actor,long version) {
        UUID id=UUID.randomUUID(); jdbc.sql("insert into student_status_history(id,student_id,from_status,to_status,effective_date,reason,changed_by,student_version) values(:id,:student,:from,:to,:date,:reason,:actor,:version)")
                .param("id",id).param("student",student).param("from",from).param("to",to).param("date",date).param("reason",reason).param("actor",actor).param("version",version).update(); return id; }
    public Optional<StatusChangeRecord> findStatusChange(UUID id) { return jdbc.sql("select * from student_status_history where id=:id")
            .param("id",id).query((r,n)->new StatusChangeRecord(r.getObject("id",UUID.class),r.getString("from_status"),r.getString("to_status"),r.getObject("effective_date",LocalDate.class),instant(r,"changed_at"),r.getLong("student_version"))).optional(); }
    public Impacts impacts(UUID id) {
        int attendance=jdbc.sql("select count(*) from attendance_session_student t join attendance_session s on s.id=t.attendance_session_id where t.student_id=:id and s.attendance_date>=current_date and s.status='OPEN'").param("id",id).query(Integer.class).single();
        int assignments=jdbc.sql("select count(*) from student_schedule_assignment where student_id=:id and current_date between effective_from and coalesce(effective_to,'infinity'::date)").param("id",id).query(Integer.class).single();
        int billing=jdbc.sql("select count(*) from tuition_billing where student_id=:id and payment_status in ('ISSUED','PARTIALLY_PAID')").param("id",id).query(Integer.class).single();
        return new Impacts(attendance,assignments,billing,List.of());
    }
    public Claim claim(String scope,UUID key,String hash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at)
                values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')
                on conflict (scope,idempotency_key) do nothing
                """).param("scope",scope).param("key",key).param("hash",hash).update();
        if (inserted == 1) return new Claim(true,null);
        var r=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query((rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if(!hash.equals(r[0])) throw new StudentException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(r[1])||r[2]==null) throw new StudentException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,(UUID)r[2]);
    }
    public void complete(String scope,UUID key,UUID resource,int status) { jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("resource",resource).param("status",status).param("scope",scope).param("key",key).update(); }
    public boolean studentExists(UUID id){ return jdbc.sql("select exists(select 1 from student where id=:id)").param("id",id).query(Boolean.class).single(); }
    public List<NoteRecord> findNotes(UUID student,Instant at,UUID id,int limit){ String cursor=at==null?"":" and (n.created_at,n.id)<(:at,:id)"; var q=jdbc.sql("select n.*,coalesce(a.display_name,'삭제된 관리자') creator from student_note n left join admin_user a on a.id=n.created_by where n.student_id=:student and n.hidden_at is null"+cursor+" order by n.created_at desc,n.id desc limit :limit").param("student",student).param("limit",limit); if(at!=null) q=q.param("at",OffsetDateTime.ofInstant(at,ZoneOffset.UTC)).param("id",id); return q.query(this::note).list(); }
    public Optional<NoteRecord> findNote(UUID id){ return jdbc.sql("select n.*,coalesce(a.display_name,'삭제된 관리자') creator from student_note n left join admin_user a on a.id=n.created_by where n.id=:id and n.hidden_at is null").param("id",id).query(this::note).optional(); }
    public void insertNote(UUID id,UUID student,byte[] cipher,UUID actor){ jdbc.sql("insert into student_note(id,student_id,content_ciphertext,created_by,updated_by) values(:id,:student,:content,:actor,:actor)").param("id",id).param("student",student).param("content",cipher).param("actor",actor).update(); }
    public int updateNote(UUID id,long version,byte[] cipher,UUID actor){ return jdbc.sql("update student_note set content_ciphertext=:content,updated_by=:actor,version=version+1 where id=:id and version=:version and hidden_at is null and created_by=:actor and created_at+interval '24 hours'>statement_timestamp()")
            .param("content",cipher).param("actor",actor).param("id",id).param("version",version).update(); }
    public int hideNote(UUID id,long version,String reason,UUID actor){ return jdbc.sql("update student_note set hidden_at=statement_timestamp(),hidden_by=:actor,hidden_reason=:reason,updated_by=:actor,version=version+1 where id=:id and version=:version and hidden_at is null")
            .param("actor",actor).param("reason",reason).param("id",id).param("version",version).update(); }

    private JdbcClient.StatementSpec query(String sql,Map<String,Object> p){ var q=jdbc.sql(sql); for(var e:p.entrySet()) q=q.param(e.getKey(),e.getValue()); return q; }
    private StudentSummary summary(ResultSet r,int n)throws SQLException{return new StudentSummary(r.getObject("id",UUID.class),r.getString("student_name"),r.getString("school_name"),r.getString("birthday_month_day"),r.getInt("lesson_count"),r.getString("status"),r.getObject("joined_at",LocalDate.class),r.getLong("version"),List.of("VIEW","EDIT","CHANGE_STATUS"));}
    private StudentRecord student(ResultSet r,int n)throws SQLException{return new StudentRecord(r.getObject("id",UUID.class),r.getObject("enrollment_case_id",UUID.class),r.getString("student_name"),r.getString("school_name"),r.getObject("birthday",LocalDate.class),r.getString("status"),r.getObject("joined_at",LocalDate.class),r.getObject("created_by",UUID.class),r.getLong("version"),instant(r,"changed_at"),r.getString("reason"));}
    private GuardianRecord guardian(ResultSet r,int n)throws SQLException{return new GuardianRecord(r.getObject("id",UUID.class),r.getObject("student_id",UUID.class),r.getString("name"),r.getString("relationship"),r.getString("relationship_detail"),r.getBytes("phone_ciphertext"),r.getBytes("email_ciphertext"),r.getString("email_domain"),r.getString("preferred_channel"),r.getBoolean("primary_contact"),r.getInt("display_order"));}
    private NoteRecord note(ResultSet r,int n)throws SQLException{return new NoteRecord(r.getObject("id",UUID.class),r.getObject("student_id",UUID.class),r.getBytes("content_ciphertext"),r.getObject("created_by",UUID.class),r.getString("creator"),instant(r,"created_at"),instant(r,"updated_at"),r.getLong("version"));}
    private static Instant instant(ResultSet r,String c)throws SQLException{OffsetDateTime o=r.getObject(c,OffsetDateTime.class);return o==null?null:o.toInstant();}
}
