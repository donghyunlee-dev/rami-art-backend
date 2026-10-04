package com.ramiart.admin.tuition.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.*;

public interface StudentTuitionAssignmentRepository {
    record Student(UUID id,String name,String status){}
    record Assignment(UUID id,UUID studentId,UUID policyId,UUID policyItemId,LocalDate from,LocalDate to,Long overrideAmount,String overrideReason,long version,int count,long baseAmount,int policyYear,int revision,long dueDay,LocalDate earliestBilledMonth,LocalDate latestBilledMonth,int billingCount){}
    Optional<Student> student(UUID id,boolean lock);
    int scheduleCount(UUID studentId,LocalDate at);
    List<PolicyOption> policyOptions(LocalDate date,int count);
    List<Assignment> assignments(UUID studentId);
    Optional<Assignment> assignment(UUID id,boolean lock);
    boolean policyItemPublished(UUID id);
    Optional<Integer> policyItemCount(UUID id);
    List<Assignment> overlaps(UUID studentId,LocalDate from,LocalDate to,UUID except);
    UUID insert(UUID id,UUID studentId,Write write,UUID actor);
    int update(UUID id,Update write,String reason,UUID actor);
    record Claim(boolean claimed,UUID resourceId){}
    Claim claim(String scope,UUID key,String hash);
    void complete(String scope,UUID key,UUID id,int status);
}
