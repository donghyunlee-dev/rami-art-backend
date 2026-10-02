package com.ramiart.admin.schedule.application;
import java.time.*;import java.util.*;
public final class ScheduleModels {private ScheduleModels(){}
  public record ClassGroupView(UUID id,String code,String name,UUID courseId,String courseName,int capacity){}
  public record Item(UUID id,UUID scheduleSlotId,UUID classGroupId,int dayOfWeek,LocalTime startTime,LocalTime endTime,String title,String roomCode,int displayOrder,ClassGroupView classGroup){}
  public record ScheduleOverride(UUID id,UUID scheduleItemId,UUID classGroupId,LocalDate targetDate,String type,LocalTime startTime,LocalTime endTime,String title,String roomCode,String reason){}
  public record Session(UUID sourceItemId,UUID scheduleSlotId,UUID overrideId,LocalTime startTime,LocalTime endTime,String title,String roomCode,String effectiveType,LocalTime originalStartTime,LocalTime originalEndTime){}
  public record Cancelled(UUID sourceItemId,UUID scheduleSlotId,UUID overrideId,LocalTime originalStartTime,LocalTime originalEndTime,String title,String roomCode,String reason){}
  public record Day(LocalDate date,List<Session> sessions,List<Cancelled> cancelled){}
  public record Issue(String code,String entityType,UUID entityId,String field,UUID conflictingEntityId,String message){}
  public record ChangeSummary(int added,int changed,int removed,int overrides){}
  public record Validation(boolean valid,List<Issue> errors,List<Issue> warnings,ChangeSummary changeSummary){}
  public record AdminView(UUID id,String displayName){}
  public record ScheduleView(UUID id,String yearMonth,int revision,String status,long version,UUID basedOnScheduleId,Instant publishedAt,AdminView publishedBy,List<Item> items,List<ScheduleOverride> overrides,List<Day> days,Validation validation,List<String> actions){}
  public record DraftWrite(long version,String changeSummary,List<Item> items,List<ScheduleOverride> overrides){}
  public record PublishWrite(UUID draftId,long draftVersion){}
  public record AttendanceGeneration(LocalDate from,int createdSessionCount,int createdTargetCount,int cancelledSessionCount){}
  public record Publication(UUID scheduleId,String yearMonth,int revision,String status,Instant publishedAt,AdminView publishedBy,ChangeSummary changeSummary,AttendanceGeneration attendanceGeneration){}
}
