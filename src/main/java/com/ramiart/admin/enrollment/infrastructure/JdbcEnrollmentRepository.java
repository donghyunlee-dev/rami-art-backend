package com.ramiart.admin.enrollment.infrastructure;

import com.ramiart.admin.enrollment.application.EnrollmentException;
import com.ramiart.admin.enrollment.application.EnrollmentModels.*;
import com.ramiart.admin.enrollment.application.EnrollmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEnrollmentRepository implements EnrollmentRepository {
    private final JdbcClient jdbc;
    public JdbcEnrollmentRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    public Optional<InquiryLead> findInquiry(UUID id){return jdbc.sql("""
            select i.id,i.name_ciphertext,i.phone_ciphertext,i.interested_course_id,
              coalesce(i.interested_course_name_snapshot,c.name) course_name,i.privacy_policy_revision_id,
              i.consent_policy_version,i.consented_at
            from inquiry i left join course c on c.id=i.interested_course_id where i.id=:id
            """)
            .param("id",id).query((r,n)->new InquiryLead(r.getObject("id",UUID.class),r.getBytes("name_ciphertext"),
                    r.getBytes("phone_ciphertext"),r.getObject("interested_course_id",UUID.class),r.getString("course_name"),
                    r.getObject("privacy_policy_revision_id",UUID.class),r.getString("consent_policy_version"),
                    instant(r,"consented_at"))).optional();}
    public Optional<UUID> findCaseByInquiry(UUID id){return jdbc.sql("select id from enrollment_case where inquiry_id=:id").param("id",id).query(UUID.class).optional();}
    public CaseRecordsPage findPage(List<String> statuses,UUID courseId,Instant from,Instant to,int page,int size){
        String where=" where e.status=any(cast(:statuses as text[])) and e.updated_at>=:from and e.updated_at<:to";
        if(courseId!=null)where+=" and e.desired_course_id=:course";
        var count=jdbc.sql("select count(*) from enrollment_case e"+where);bind(count,statuses,courseId,from,to);long total=count.query(Long.class).single();
        var query=jdbc.sql(baseSelect()+where+" order by e.updated_at desc,e.id limit :size offset :offset");bind(query,statuses,courseId,from,to);query.param("size",size).param("offset",page*size);
        return new CaseRecordsPage(query.query(this::mapCase).list(),total);
    }
    public Optional<CaseRecord> findCase(UUID id,boolean lock){return jdbc.sql(baseSelect()+" where e.id=:id"+(lock?" for update of e":""))
            .param("id",id).query(this::mapCase).optional();}
    public List<ActivityRecord> findActivities(UUID id){return jdbc.sql("select * from enrollment_activity where enrollment_case_id=:id order by sequence desc")
            .param("id",id).query(this::mapActivity).list();}
    public int waitlistPosition(UUID id,UUID group,Instant at){if(group==null||at==null)return 0;return jdbc.sql("select count(*)+1 from enrollment_case where desired_class_group_id=:group and status='WAITLISTED' and (waitlisted_at,id)<(:at,:id)")
            .param("group",group).param("at",odt(at)).param("id",id).query(Integer.class).single();}
    public void insertCase(UUID id,UUID inquiry,byte[] name,byte[] phone,String hash,String last4,UUID course,UUID group,UUID actor){jdbc.sql("""
            insert into enrollment_case(id,inquiry_id,lead_name_ciphertext,phone_ciphertext,phone_hash,phone_last4,desired_course_id,desired_class_group_id,assignee_admin_user_id,created_by,updated_by)
            values(:id,:inquiry,:name,:phone,:hash,:last4,:course,:group,:assignee,:actor,:actor)
            """).param("id",id).param("inquiry",inquiry).param("name",name).param("phone",phone).param("hash",hash).param("last4",last4)
            .param("course",course).param("group",group).param("assignee",actor).param("actor",actor).update();}
    public List<AssigneeOption> findAssignees(){return jdbc.sql("""
            select u.id,u.display_name from admin_user u
            join admin_user_role ur on ur.admin_user_id=u.id
            join admin_role r on r.id=ur.admin_role_id and r.active
            where u.status='ACTIVE' order by u.display_name,u.id
            """)
            .query((r,n)->new AssigneeOption(r.getObject("id",UUID.class),r.getString("display_name"))).list();}
    public boolean isActiveAssignee(UUID id){return Boolean.TRUE.equals(jdbc.sql("""
            select exists(select 1 from admin_user u join admin_user_role ur on ur.admin_user_id=u.id
              join admin_role r on r.id=ur.admin_role_id and r.active where u.id=:id and u.status='ACTIVE')
            """)
            .param("id",id).query(Boolean.class).single());}
    public int updateAssignee(UUID id,long version,UUID assigneeId,UUID actor){return jdbc.sql("""
            update enrollment_case set assignee_admin_user_id=:assignee,updated_by=:actor,version=version+1
            where id=:id and version=:version
            """).param("assignee",assigneeId).param("actor",actor).param("id",id).param("version",version).update();}
    public int updateState(UUID id,long version,String expected,String next,UUID course,UUID group,Instant trial,Instant wait,String lost,UUID student,UUID actor){return jdbc.sql("""
            update enrollment_case set status=:next,desired_course_id=coalesce(:course,desired_course_id),desired_class_group_id=coalesce(:group,desired_class_group_id),
              trial_starts_at=coalesce(:trial,trial_starts_at),waitlisted_at=:wait,lost_reason=:lost,student_id=:student,updated_by=:actor,version=version+1
            where id=:id and version=:version and status=:expected
            """).param("next",next).param("course",course).param("group",group).param("trial",trial==null?null:odt(trial)).param("wait",wait==null?null:odt(wait))
            .param("lost",lost).param("student",student).param("actor",actor).param("id",id).param("version",version).param("expected",expected).update();}
    public UUID insertActivity(UUID caseId,String type,String from,String to,String channel,String outcome,byte[] note,Instant occurred,UUID actor){UUID id=UUID.randomUUID();jdbc.sql("""
            insert into enrollment_activity(id,enrollment_case_id,sequence,type,from_status,to_status,channel,outcome,note_ciphertext,occurred_at,created_by)
            select :id,:case,coalesce(max(sequence),0)+1,:type,:from,:to,:channel,:outcome,:note,:occurred,:actor from enrollment_activity where enrollment_case_id=:case
            """).param("id",id).param("case",caseId).param("type",type).param("from",from).param("to",to).param("channel",channel).param("outcome",outcome)
            .param("note",note).param("occurred",odt(occurred)).param("actor",actor).update();return id;}
    public Optional<ClassLock> lockClass(UUID group,LocalDate from){return jdbc.sql("""
            select g.id,g.course_id,g.capacity,g.status,g.starts_on,g.ends_on,
              (select count(distinct a.student_id) from student_schedule_assignment a join schedule_slot s on s.id=a.schedule_slot_id
               where s.class_group_id=g.id and :day between a.effective_from and coalesce(a.effective_to,'infinity'::date)) occupancy
            from class_group g where g.id=:id for update
            """).param("day",from).param("id",group).query((r,n)->new ClassLock(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getString(4),r.getObject(5,LocalDate.class),r.getObject(6,LocalDate.class),r.getInt(7))).optional();}
    public List<UUID> activeSlotIds(UUID group,List<UUID> ids){if(ids==null||ids.isEmpty())return List.of();return jdbc.sql("select id from schedule_slot where class_group_id=:group and status='ACTIVE' and id=any(cast(:ids as uuid[])) order by id")
            .param("group",group).param("ids",ids.toArray(UUID[]::new)).query(UUID.class).list();}
    public List<UUID> requiredConsentIds(){return jdbc.sql("select id from consent_policy where status='PUBLISHED' and required order by type,id").query(UUID.class).list();}
    public List<DuplicateCandidate> duplicateCandidates(String name,LocalDate birthday,List<String> hashes){return jdbc.sql("""
            select s.id,s.student_name,s.status,array_agg(distinct matched.reason order by matched.reason) matched_by
            from student s
            cross join lateral (
              select 'NAME_BIRTHDAY'::text reason
              where :birthday is not null and s.student_name_search=:name and s.birthday=:birthday
              union all
              select 'GUARDIAN_PHONE'::text reason
              where cardinality(cast(:hashes as text[]))>0 and exists(
                select 1 from guardian_contact g where g.student_id=s.id and g.phone_hash=any(cast(:hashes as text[])))
            ) matched
            group by s.id,s.student_name,s.status
            order by s.student_name,s.id limit 10
            """).param("name",name).param("birthday",birthday).param("hashes",hashes.toArray(String[]::new))
            .query((r,n)->new DuplicateCandidate(r.getObject("id",UUID.class),r.getString("student_name"),r.getString("status"),
                    Arrays.asList((String[])r.getArray("matched_by").getArray()))).list();}
    public void insertStudent(UUID id,StudentWrite s,String search,UUID actor){jdbc.sql("insert into student(id,student_name,student_name_search,school_name,birthday,status,joined_at,created_by,updated_by) values(:id,:name,:search,:school,:birthday,'ACTIVE',:joined,:actor,:actor)")
            .param("id",id).param("name",s.name()).param("search",search).param("school",s.schoolName()).param("birthday",s.birthday()).param("joined",s.joinedAt()).param("actor",actor).update();}
    public void insertGuardian(UUID student,StoredGuardian g){jdbc.sql("""
            insert into guardian_contact(id,student_id,name,relationship,relationship_detail,phone_ciphertext,phone_hash,phone_last4,email_ciphertext,email_hash,email_domain,preferred_channel,primary_contact,display_order)
            values(:id,:student,:name,:relationship,:detail,:phone,:hash,:last4,:email,:emailHash,:domain,:channel,:primary,:display)
            """).param("id",g.id()).param("student",student).param("name",g.value().name()).param("relationship",g.value().relationship()).param("detail",g.value().relationshipDetail())
            .param("phone",g.phoneCiphertext()).param("hash",g.phoneHash()).param("last4",g.last4()).param("email",g.emailCiphertext()).param("emailHash",g.emailHash()).param("domain",g.emailDomain())
            .param("channel",g.value().preferredChannel()).param("primary",g.value().primaryContact()).param("display",g.value().displayOrder()).update();}
    public void insertInitialStatus(UUID student,LocalDate joined,UUID actor){jdbc.sql("insert into student_status_history(id,student_id,to_status,effective_date,reason,changed_by,student_version) values(:id,:student,'ACTIVE',:joined,'상담 등록 전환',:actor,0)")
            .param("id",UUID.randomUUID()).param("student",student).param("joined",joined).param("actor",actor).update();}
    public UUID insertAssignment(UUID student,UUID slot,LocalDate from,UUID actor){UUID id=UUID.randomUUID();jdbc.sql("insert into student_schedule_assignment(id,student_id,schedule_slot_id,effective_from,created_by,updated_by) values(:id,:student,:slot,:from,:actor,:actor)")
            .param("id",id).param("student",student).param("slot",slot).param("from",from).param("actor",actor).update();return id;}
    public void insertConsent(UUID student,UUID guardian,UUID policy,UUID actor){jdbc.sql("""
            insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,created_by)
            select :id,:student,p.id,p.type,:guardian,'PAPER','ACTIVE',statement_timestamp(),:actor from consent_policy p where p.id=:policy and p.status='PUBLISHED'
            """).param("id",UUID.randomUUID()).param("student",student).param("guardian",guardian).param("actor",actor).param("policy",policy).update();}
    public List<UUID> guardianIds(UUID student){return jdbc.sql("select id from guardian_contact where student_id=:id order by display_order").param("id",student).query(UUID.class).list();}
    public List<UUID> assignmentIds(UUID student){return jdbc.sql("select id from student_schedule_assignment where student_id=:id order by created_at,id").param("id",student).query(UUID.class).list();}
    public Claim claim(String scope,UUID key,String hash){int inserted=jdbc.sql("""
            insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at)
            values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')
            on conflict (scope,idempotency_key) do nothing
            """).param("scope",scope).param("key",key).param("hash",hash).update();if(inserted==1)return new Claim(true,null);Object[] row=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
            .param("scope",scope).param("key",key).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,UUID.class)}).single();if(!hash.equals(row[0]))throw new EnrollmentException("IDEMPOTENCY_KEY_REUSED");if(!"COMPLETED".equals(row[1])||row[2]==null)throw new EnrollmentException("IDEMPOTENCY_IN_PROGRESS");return new Claim(false,(UUID)row[2]);}
    public void complete(String scope,UUID key,UUID resource,int status){jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("resource",resource).param("status",status).param("scope",scope).param("key",key).update();}

    private String baseSelect(){return """
            select e.*,assignee.display_name assignee_name,c.name course_name,g.name group_name,coalesce(g.capacity,0) capacity,
              coalesce((select count(distinct a.student_id) from student_schedule_assignment a join schedule_slot ss on ss.id=a.schedule_slot_id where ss.class_group_id=g.id and current_date between a.effective_from and coalesce(a.effective_to,'infinity'::date)),0) occupancy
            from enrollment_case e left join course c on c.id=e.desired_course_id left join class_group g on g.id=e.desired_class_group_id
            join admin_user assignee on assignee.id=e.assignee_admin_user_id
            """;}
    private void bind(JdbcClient.StatementSpec q,List<String>s,UUID c,Instant f,Instant t){q.param("statuses",s.toArray(String[]::new)).param("from",odt(f)).param("to",odt(t));if(c!=null)q.param("course",c);}
    private CaseRecord mapCase(ResultSet r,int n)throws SQLException{return new CaseRecord(r.getObject("id",UUID.class),r.getObject("inquiry_id",UUID.class),r.getBytes("lead_name_ciphertext"),r.getBytes("phone_ciphertext"),r.getString("phone_hash"),r.getString("phone_last4"),r.getString("status"),r.getObject("desired_course_id",UUID.class),r.getString("course_name"),r.getObject("desired_class_group_id",UUID.class),r.getString("group_name"),instant(r,"trial_starts_at"),instant(r,"waitlisted_at"),r.getObject("student_id",UUID.class),r.getString("lost_reason"),r.getLong("version"),instant(r,"updated_at"),r.getInt("capacity"),r.getInt("occupancy"),r.getObject("assignee_admin_user_id",UUID.class),r.getString("assignee_name"));}
    private ActivityRecord mapActivity(ResultSet r,int n)throws SQLException{return new ActivityRecord(r.getObject("id",UUID.class),r.getLong("sequence"),r.getString("type"),r.getString("from_status"),r.getString("to_status"),r.getString("channel"),r.getString("outcome"),r.getBytes("note_ciphertext"),instant(r,"occurred_at"),r.getObject("created_by",UUID.class));}
    private static Instant instant(ResultSet r,String c)throws SQLException{OffsetDateTime v=r.getObject(c,OffsetDateTime.class);return v==null?null:v.toInstant();}
    private static OffsetDateTime odt(Instant v){return v.atOffset(ZoneOffset.UTC);}
}
