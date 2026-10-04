package com.ramiart.admin.tuition.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StudentTuitionAssignmentModels {
    private StudentTuitionAssignmentModels() {}
    public record Write(UUID policyItemId, LocalDate effectiveFrom, LocalDate effectiveTo, Long overrideAmount, String overrideReason) {}
    public record Update(LocalDate effectiveFrom, LocalDate effectiveTo, Long overrideAmount, String overrideReason, long version) {}
    public record Metadata(String requestId,String ip,String userAgent) {}
    public record CandidateQuery(LocalDate effectiveFrom) {}
    public record PolicyOption(UUID policyItemId,int policyYear,int policyRevision,int lessonCountPerWeek,long monthlyAmount,int defaultDueDay) {}
    public record CandidateResult(LocalDate effectiveFrom,int lessonCountPerWeek,String reasonCode,List<PolicyOption> candidates) {}
}
