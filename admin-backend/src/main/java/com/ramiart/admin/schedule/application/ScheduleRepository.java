package com.ramiart.admin.schedule.application;
import com.ramiart.admin.schedule.application.ScheduleModels.*;import java.time.*;import java.util.*;
public interface ScheduleRepository {
  record ScheduleRecord(UUID id,String yearMonth,int revision,String status,long version,UUID basedOn,UUID publishedBy,String publishedByName,Instant publishedAt,String changeSummary){}
  record Claim(boolean claimed,UUID resourceId){}
  record AttendanceOccurrence(UUID scheduleItemId,UUID scheduleOverrideId,UUID scheduleSlotId,UUID classGroupId,
                              LocalDate attendanceDate,String title,String roomCode,LocalTime startTime,LocalTime endTime){}
  Optional<ScheduleRecord> find(String yearMonth,String status,boolean lock);
  Optional<ScheduleRecord> findById(UUID id,boolean lock);
  List<Item> items(UUID scheduleId);
  List<ScheduleOverride> overrides(UUID scheduleId);
  int nextRevision(String yearMonth);
  UUID insertDraft(String yearMonth,int revision,UUID basedOn,UUID actor);
  boolean activeClassGroup(UUID id);
  Optional<UUID> slotClassGroup(UUID slotId);
  UUID createSlot(UUID classGroupId,UUID actor);
  int updateDraft(UUID id,long version,String summary);
  void replaceItems(UUID scheduleId,List<Item> items);
  void replaceOverrides(UUID scheduleId,List<ScheduleOverride> overrides);
  int archivePublished(String yearMonth);
  int publish(UUID id,long version,UUID actor,Instant at);
  boolean hasRecordedAttendance(UUID scheduleId,LocalDate from);
  int cancelOpenAttendance(UUID scheduleId,LocalDate from);
  int createAttendance(AttendanceOccurrence occurrence,ZoneId studioZone);
  void savePublicationResult(UUID scheduleId,LocalDate from,int created,int targets,int cancelled);
  Optional<AttendanceGeneration> publicationResult(UUID scheduleId);
  Claim claim(String scope,UUID key,String hash);
  void complete(String scope,UUID key,UUID resourceId,int status);
}
