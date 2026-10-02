package com.ramiart.admin.schedule.infrastructure;
import com.ramiart.admin.schedule.application.*;import com.ramiart.admin.schedule.application.ScheduleModels.*;import java.sql.*;import java.time.*;import java.util.*;import org.springframework.dao.DuplicateKeyException;import org.springframework.jdbc.core.simple.JdbcClient;import org.springframework.stereotype.Repository;
@Repository public class JdbcScheduleRepository implements ScheduleRepository {
 private final JdbcClient jdbc; public JdbcScheduleRepository(JdbcClient jdbc){this.jdbc=jdbc;}
 public Optional<ScheduleRecord> find(String month,String status,boolean lock){return jdbc.sql("select s.*,a.display_name published_by_name from monthly_schedule s left join admin_user a on a.id=s.published_by where s.year_month=:month and s.status=:status"+(lock?" for update of s":"")).param("month",month).param("status",status).query(this::schedule).optional();}
 public Optional<ScheduleRecord> findById(UUID id,boolean lock){return jdbc.sql("select s.*,a.display_name published_by_name from monthly_schedule s left join admin_user a on a.id=s.published_by where s.id=:id"+(lock?" for update of s":"")).param("id",id).query(this::schedule).optional();}
 public List<Item> items(UUID id){return jdbc.sql("""
  select i.*,g.id group_id,g.code group_code,g.name group_name,g.course_id,c.name course_name,g.capacity
  from monthly_schedule_item i join schedule_slot ss on ss.id=i.schedule_slot_id join class_group g on g.id=ss.class_group_id join course c on c.id=g.course_id
  where i.monthly_schedule_id=:id order by i.day_of_week,i.start_time,i.room_code,i.id
  """).param("id",id).query((r,n)->{UUID group=r.getObject("group_id",UUID.class);return new Item(r.getObject("id",UUID.class),r.getObject("schedule_slot_id",UUID.class),group,r.getInt("day_of_week"),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class),r.getString("title"),r.getString("room_code"),r.getInt("display_order"),new ClassGroupView(group,r.getString("group_code"),r.getString("group_name"),r.getObject("course_id",UUID.class),r.getString("course_name"),r.getInt("capacity")));}).list();}
 public List<ScheduleOverride> overrides(UUID id){return jdbc.sql("select * from schedule_override where monthly_schedule_id=:id order by target_date,start_time,id").param("id",id).query((r,n)->new ScheduleOverride(r.getObject("id",UUID.class),r.getObject("schedule_item_id",UUID.class),r.getObject("class_group_id",UUID.class),r.getObject("target_date",LocalDate.class),r.getString("type"),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class),r.getString("title"),r.getString("room_code"),r.getString("reason"))).list();}
 public int nextRevision(String month){return jdbc.sql("select coalesce(max(revision),0)+1 from monthly_schedule where year_month=:month").param("month",month).query(Integer.class).single();}
 public UUID insertDraft(String month,int revision,UUID basedOn,UUID actor){UUID id=UUID.randomUUID();jdbc.sql("insert into monthly_schedule(id,year_month,revision,status,based_on_schedule_id,created_by) values(:id,:month,:revision,'DRAFT',:based,:actor)").param("id",id).param("month",month).param("revision",revision).param("based",basedOn).param("actor",actor).update();return id;}
 public boolean activeClassGroup(UUID id){return jdbc.sql("select exists(select 1 from class_group where id=:id and status='ACTIVE')").param("id",id).query(Boolean.class).single();}
 public Optional<UUID> slotClassGroup(UUID id){return jdbc.sql("select class_group_id from schedule_slot where id=:id").param("id",id).query(UUID.class).optional();}
 public UUID createSlot(UUID group,UUID actor){UUID id=UUID.randomUUID();jdbc.sql("insert into schedule_slot(id,class_group_id,status,created_by) values(:id,:group,'ACTIVE',:actor)").param("id",id).param("group",group).param("actor",actor).update();return id;}
 public int updateDraft(UUID id,long version,String summary){return jdbc.sql("update monthly_schedule set change_summary=:summary,version=version+1 where id=:id and version=:version and status='DRAFT'").param("summary",summary).param("id",id).param("version",version).update();}
 public void replaceItems(UUID schedule,List<Item> values){jdbc.sql("delete from monthly_schedule_item where monthly_schedule_id=:id").param("id",schedule).update();for(Item i:values)jdbc.sql("insert into monthly_schedule_item(id,schedule_slot_id,monthly_schedule_id,day_of_week,start_time,end_time,title,room_code,display_order) values(:id,:slot,:schedule,:day,:start,:end,:title,:room,:display)").param("id",i.id()).param("slot",i.scheduleSlotId()).param("schedule",schedule).param("day",i.dayOfWeek()).param("start",i.startTime()).param("end",i.endTime()).param("title",i.title()).param("room",i.roomCode()).param("display",i.displayOrder()).update();}
 public void replaceOverrides(UUID schedule,List<ScheduleOverride> values){jdbc.sql("delete from schedule_override where monthly_schedule_id=:id").param("id",schedule).update();for(ScheduleOverride o:values)jdbc.sql("insert into schedule_override(id,monthly_schedule_id,schedule_item_id,class_group_id,target_date,type,start_time,end_time,title,room_code,reason) values(:id,:schedule,:item,:group,:date,:type,:start,:end,:title,:room,:reason)").param("id",o.id()).param("schedule",schedule).param("item",o.scheduleItemId()).param("group",o.classGroupId()).param("date",o.targetDate()).param("type",o.type()).param("start",o.startTime()).param("end",o.endTime()).param("title",o.title()).param("room",o.roomCode()).param("reason",o.reason()).update();}
 public int archivePublished(String month){return jdbc.sql("update monthly_schedule set status='ARCHIVED' where year_month=:month and status='PUBLISHED'").param("month",month).update();}
 public int publish(UUID id,long version,UUID actor,Instant at){return jdbc.sql("update monthly_schedule set status='PUBLISHED',published_by=:actor,published_at=:at,version=version+1 where id=:id and status='DRAFT' and version=:version").param("actor",actor).param("at",at.atOffset(ZoneOffset.UTC)).param("id",id).param("version",version).update();}
 public boolean hasRecordedAttendance(UUID schedule,LocalDate from){return jdbc.sql("""
  select exists(select 1 from attendance_session s
    left join monthly_schedule_item i on i.id=s.schedule_item_id
    left join schedule_override o on o.id=s.schedule_override_id
    where coalesce(i.monthly_schedule_id,o.monthly_schedule_id)=:schedule and s.attendance_date>=:from
      and (s.status='CLOSED' or exists(select 1 from student_attendance a where a.attendance_session_id=s.id)))
  """).param("schedule",schedule).param("from",from).query(Boolean.class).single();}
 public int cancelOpenAttendance(UUID schedule,LocalDate from){return jdbc.sql("""
  update attendance_session s set status='CANCELLED',cancel_reason='SCHEDULE_REPLACED',version=version+1
  where s.status='OPEN' and s.attendance_date>=:from and not exists(select 1 from student_attendance a where a.attendance_session_id=s.id)
    and exists(select 1 from monthly_schedule_item i where i.id=s.schedule_item_id and i.monthly_schedule_id=:schedule
      union all select 1 from schedule_override o where o.id=s.schedule_override_id and o.monthly_schedule_id=:schedule)
  """).param("schedule",schedule).param("from",from).update();}
 public int createAttendance(AttendanceOccurrence occurrence,ZoneId studioZone){
  List<Object[]> targets=occurrence.scheduleSlotId()==null?List.of():jdbc.sql("""
   select a.id assignment_id,s.id student_id,s.student_name
   from student_schedule_assignment a join student s on s.id=a.student_id
   where a.schedule_slot_id=:slot and a.effective_from<=:date and (a.effective_to is null or a.effective_to>=:date)
     and s.status='ACTIVE' order by s.student_name_search,s.id
   """).param("slot",occurrence.scheduleSlotId()).param("date",occurrence.attendanceDate())
    .query((r,n)->new Object[]{r.getObject("assignment_id",UUID.class),r.getObject("student_id",UUID.class),r.getString("student_name")}).list();
  UUID session=UUID.randomUUID(); OffsetDateTime starts=occurrence.attendanceDate().atTime(occurrence.startTime()).atZone(studioZone).toOffsetDateTime(); OffsetDateTime ends=occurrence.attendanceDate().atTime(occurrence.endTime()).atZone(studioZone).toOffsetDateTime();
  jdbc.sql("""
   insert into attendance_session(id,schedule_item_id,schedule_override_id,schedule_slot_id,class_group_id,attendance_date,class_name_snapshot,room_code_snapshot,starts_at,ends_at,target_count)
   values(:id,:item,:override,:slot,:group,:date,:title,:room,:starts,:ends,:targets)
   """).param("id",session).param("item",occurrence.scheduleItemId()).param("override",occurrence.scheduleOverrideId())
    .param("slot",occurrence.scheduleSlotId()).param("group",occurrence.classGroupId()).param("date",occurrence.attendanceDate())
    .param("title",occurrence.title()).param("room",occurrence.roomCode()).param("starts",starts).param("ends",ends).param("targets",targets.size()).update();
  for(int index=0;index<targets.size();index++){Object[] target=targets.get(index);jdbc.sql("insert into attendance_session_student(attendance_session_id,student_id,student_name_snapshot,display_order,assignment_id) values(:session,:student,:name,:display,:assignment)")
    .param("session",session).param("student",target[1]).param("name",target[2]).param("display",index).param("assignment",target[0]).update();}
  return targets.size();
 }
 public void savePublicationResult(UUID id,LocalDate from,int created,int targets,int cancelled){jdbc.sql("insert into schedule_publication_result(schedule_id,generated_from,created_session_count,created_target_count,cancelled_session_count) values(:id,:from,:created,:targets,:cancelled)").param("id",id).param("from",from).param("created",created).param("targets",targets).param("cancelled",cancelled).update();}
 public Optional<AttendanceGeneration> publicationResult(UUID id){return jdbc.sql("select * from schedule_publication_result where schedule_id=:id").param("id",id).query((r,n)->new AttendanceGeneration(r.getObject("generated_from",LocalDate.class),r.getInt("created_session_count"),r.getInt("created_target_count"),r.getInt("cancelled_session_count"))).optional();}
 public Claim claim(String scope,UUID key,String hash){try{jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')").param("scope",scope).param("key",key).param("hash",hash).update();return new Claim(true,null);}catch(DuplicateKeyException e){Object[] row=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key").param("scope",scope).param("key",key).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,UUID.class)}).single();if(!hash.equals(row[0]))throw new ScheduleException("IDEMPOTENCY_KEY_REUSED");if(!"COMPLETED".equals(row[1]))throw new ScheduleException("IDEMPOTENCY_IN_PROGRESS");return new Claim(false,(UUID)row[2]);}}
 public void complete(String scope,UUID key,UUID resource,int status){jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key").param("resource",resource).param("status",status).param("scope",scope).param("key",key).update();}
 private ScheduleRecord schedule(ResultSet r,int n)throws SQLException{return new ScheduleRecord(r.getObject("id",UUID.class),r.getString("year_month"),r.getInt("revision"),r.getString("status"),r.getLong("version"),r.getObject("based_on_schedule_id",UUID.class),r.getObject("published_by",UUID.class),r.getString("published_by_name"),instant(r,"published_at"),r.getString("change_summary"));}
 private static Instant instant(ResultSet r,String c)throws SQLException{OffsetDateTime v=r.getObject(c,OffsetDateTime.class);return v==null?null:v.toInstant();}
}
