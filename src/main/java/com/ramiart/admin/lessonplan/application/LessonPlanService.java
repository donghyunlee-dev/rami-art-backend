package com.ramiart.admin.lessonplan.application;

import static com.ramiart.admin.lessonplan.application.LessonPlanModels.*;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonPlanService {
    private final LessonPlanRepository repository;
    private final AuditRecorder audit;

    public LessonPlanService(LessonPlanRepository repository, AuditRecorder audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID classGroupId, String month, Authentication authentication) {
        UUID actorId = actor(authentication, "LESSON_PLAN_READ");
        YearMonth selected = parseMonth(month);
        Group group = group(classGroupId);
        requireScope(actorId, classGroupId, selected);
        return view(group, selected, authentication, null, null);
    }

    @Transactional
    public PlanView createDraft(UUID classGroupId, String month, Authentication authentication, RequestMetadata metadata) {
        UUID actorId = actor(authentication, "LESSON_PLAN_WRITE");
        YearMonth selected = parseMonth(month);
        Group group = group(classGroupId);
        requireScope(actorId, classGroupId, selected);
        if (repository.scheduleSnapshot(classGroupId, month, true).isEmpty())
            throw new LessonPlanException("LESSON_PLAN_SCHEDULE_NOT_FOUND");
        if (repository.findPlan(classGroupId, month, "DRAFT", true).isPresent())
            throw new LessonPlanException("LESSON_PLAN_DRAFT_EXISTS");
        Plan published = repository.findPlan(classGroupId, month, "PUBLISHED", true).orElse(null);
        Plan draft = repository.insertDraft(classGroupId, month, repository.nextRevision(classGroupId, month),
                published == null ? null : published.id(), actorId);
        if (published != null) repository.replaceItems(draft.id(), published.items().stream()
                .map(item -> new ItemWrite(UUID.randomUUID(), item.plannedDate(), item.sequence(), item.title(),
                        item.objectives(), item.activities(), item.materials(), item.preparations(), item.internalNote()))
                .toList());
        event(actorId, metadata, "LESSON_PLAN_DRAFT_CREATED", draft.id(), Map.of("classGroupId", classGroupId, "month", month));
        Plan freshDraft = repository.findPlanById(draft.id(), false).orElseThrow();
        return view(group, selected, authentication, freshDraft, null);
    }

    @Transactional
    public PlanView save(UUID planId, PlanWrite command, Authentication authentication, RequestMetadata metadata) {
        UUID actorId = actor(authentication, "LESSON_PLAN_WRITE");
        if (command == null || command.items() == null || command.items().isEmpty() || command.items().size() > 200
                || command.version() < 0) throw new LessonPlanException("VALIDATION_ERROR");
        Plan draft = repository.findPlanById(planId, true).orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(draft.status())) throw new LessonPlanException("LESSON_PLAN_PUBLISHED_IMMUTABLE");
        YearMonth month = parseMonthById(planId);
        Group group = groupForPlan(planId);
        requireScope(actorId, group.id(), month);
        List<ItemWrite> normalized = normalize(command.items(), month);
        if (draft.version() != command.version()) throw new LessonPlanException("LESSON_PLAN_VERSION_CONFLICT");
        if (repository.updateDraft(planId, command.version()) != 1) throw new LessonPlanException("LESSON_PLAN_VERSION_CONFLICT");
        repository.replaceItems(planId, normalized);
        event(actorId, metadata, "LESSON_PLAN_DRAFT_SAVED", planId, Map.of("itemCount", normalized.size()));
        return view(group, month, authentication, null, null);
    }

    @Transactional(readOnly = true)
    public PlanView preview(UUID classGroupId, String month, UUID draftId, List<ItemWrite> proposedItems,
            Authentication authentication) {
        UUID actorId = actor(authentication, "LESSON_PLAN_READ");
        if (classGroupId == null) throw new LessonPlanException("VALIDATION_ERROR");
        YearMonth selected = parseMonth(month);
        Group group = group(classGroupId);
        requireScope(actorId, classGroupId, selected);
        PlanView current = view(group, selected, authentication, null, null);
        if (draftId == null && proposedItems == null) return current;
        List<ItemWrite> items;
        if (draftId != null) {
            Plan draft = repository.findPlanById(draftId, false)
                    .filter(value -> "DRAFT".equals(value.status()))
                    .orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
            if (!Objects.equals(current.draft() == null ? null : current.draft().id(), draft.id()))
                throw new LessonPlanException("LESSON_PLAN_SCOPE_DENIED");
            items = draft.items();
        } else {
            if (proposedItems == null || proposedItems.size() > 200) throw new LessonPlanException("VALIDATION_ERROR");
            items = proposedItems;
        }
        ScheduleSnapshot schedule = current.scheduleSnapshot();
        Diff diff = diff(schedule, items, selected);
        return new PlanView(group, month, current.published(), current.draft(), schedule, diff, publishable(schedule, diff),
                permissions(authentication, actorId, classGroupId, selected));
    }

    @Transactional
    public PublishResult publish(UUID draftId, PublishWrite command, Authentication authentication, UUID key,
            RequestMetadata metadata) {
        UUID actorId = actor(authentication, "LESSON_PLAN_PUBLISH");
        if (command == null || command.version() < 0 || command.scheduleRevision() < 1 || key == null)
            throw new LessonPlanException("VALIDATION_ERROR");
        repository.findPlanById(draftId, false)
                .orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
        Group group = groupForPlan(draftId);
        YearMonth month = parseMonthById(draftId);
        requireScope(actorId, group.id(), month);
        String summary = normalizeSummary(command.changeSummary());
        String scope = actorId + ":POST:/admin/lesson-plans/draft/{id}/publish";
        String requestHash = hash(draftId + ":" + command.version() + ":" + summary + ":" + command.scheduleRevision());
        if (!repository.claimIdempotency(scope, key, requestHash)) {
            if (!repository.idempotencyHash(scope, key).filter(requestHash::equals).isPresent())
                throw new LessonPlanException("IDEMPOTENCY_KEY_REUSED");
            UUID resourceId = repository.idempotencyResource(scope, key).orElseThrow(() -> new LessonPlanException("IDEMPOTENCY_IN_PROGRESS"));
            Plan replayed = repository.findPlanById(resourceId, false).orElseThrow();
            if (!"PUBLISHED".equals(replayed.status())) replayed = new Plan(replayed.id(), replayed.revision(), "PUBLISHED",
                    replayed.version(), replayed.basedOnPlanId(), replayed.changeSummary(), replayed.publishedBy(),
                    replayed.publishedByName(), replayed.publishedAt(), replayed.items());
            return new PublishResult(replayed, false);
        }
        ScheduleSnapshot schedule = repository.scheduleSnapshot(group.id(), month.toString(), true)
                .orElseThrow(() -> new LessonPlanException("LESSON_PLAN_SCHEDULE_NOT_FOUND"));
        Plan draft = repository.findPlanById(draftId, true)
                .orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
        if (!"DRAFT".equals(draft.status())) throw new LessonPlanException("LESSON_PLAN_PUBLISHED_IMMUTABLE");
        if (draft.version() != command.version()) throw new LessonPlanException("LESSON_PLAN_VERSION_CONFLICT");
        if (schedule.revision() == null || schedule.revision() != command.scheduleRevision())
            throw new LessonPlanException("LESSON_PLAN_SCHEDULE_CHANGED");
        Diff diff = diff(schedule, draft.items(), month);
        if (!diff.orphanPlanItems().isEmpty())
            throw new LessonPlanException("LESSON_PLAN_DATE_INVALID", Map.of("diff", diff));
        if (!publishable(schedule, diff)) throw new LessonPlanException("LESSON_PLAN_INCOMPLETE", Map.of("diff", diff));
        repository.archivePublished(group.id(), month.toString());
        if (repository.publish(draftId, command.version(), summary, actorId, Instant.now()) != 1)
            throw new LessonPlanException("LESSON_PLAN_VERSION_CONFLICT");
        repository.linkAttendanceSessions(group.id(), month.toString(), draftId);
        repository.completeIdempotency(scope, key, draftId, 201);
        event(actorId, metadata, "LESSON_PLAN_PUBLISHED", draftId, Map.of("revision", draft.revision(), "scheduleRevision", schedule.revision()));
        return new PublishResult(repository.findPlanById(draftId, false).orElseThrow(), true);
    }

    private PlanView view(Group group, YearMonth month, Authentication authentication, Plan draftOverride, Diff diffOverride) {
        String value = month.toString();
        Plan published = repository.findPlan(group.id(), value, "PUBLISHED", false).orElse(null);
        Plan draft = draftOverride != null ? draftOverride : repository.findPlan(group.id(), value, "DRAFT", false).orElse(null);
        ScheduleSnapshot schedule = repository.scheduleSnapshot(group.id(), value, false).orElse(new ScheduleSnapshot(null, List.of()));
        Plan basis = draft == null ? published : draft;
        Diff diff = diffOverride != null ? diffOverride : diff(schedule, basis == null ? List.of() : basis.items(), month);
        UUID actorId = actorId(authentication);
        return new PlanView(group, value, published, draft, schedule, diff, publishable(schedule, diff),
                permissions(authentication, actorId, group.id(), month));
    }

    private Permissions permissions(Authentication authentication, UUID actorId, UUID groupId, YearMonth month) {
        boolean scope = actorId != null && (repository.isOwner(actorId)
                || repository.managesMonth(actorId, groupId, month.atDay(1), month.atEndOfMonth()));
        return new Permissions(has(authentication, "LESSON_PLAN_READ") && scope,
                has(authentication, "LESSON_PLAN_WRITE") && scope, has(authentication, "LESSON_PLAN_PUBLISH") && scope);
    }

    private void requireScope(UUID actorId, UUID groupId, YearMonth month) {
        if (!repository.isOwner(actorId)
                && !repository.managesMonth(actorId, groupId, month.atDay(1), month.atEndOfMonth()))
            throw new LessonPlanException("LESSON_PLAN_SCOPE_DENIED");
    }

    private Group group(UUID id) {
        return repository.group(id).orElseThrow(() -> new LessonPlanException("LESSON_PLAN_CLASS_GROUP_NOT_FOUND"));
    }

    private Group groupForPlan(UUID planId) {
        return repository.groupForPlan(planId).orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
    }

    private static List<ItemWrite> normalize(List<ItemWrite> source, YearMonth month) {
        Set<UUID> ids = new HashSet<>();
        Set<String> sequences = new HashSet<>();
        List<ItemWrite> result = new ArrayList<>();
        for (ItemWrite item : source) {
            if (item == null || item.plannedDate() == null) throw new LessonPlanException("VALIDATION_ERROR");
            if (!YearMonth.from(item.plannedDate()).equals(month)) throw new LessonPlanException("LESSON_PLAN_DATE_INVALID");
            UUID id = item.id() == null ? UUID.randomUUID() : item.id();
            if (!ids.add(id) || item.sequence() < 1 || item.sequence() > 20
                    || !sequences.add(item.plannedDate() + ":" + item.sequence()))
                throw new LessonPlanException("VALIDATION_ERROR");
            String title = requiredText(item.title(), 120, "LESSON_PLAN_INCOMPLETE");
            List<String> objectives = strings(item.objectives(), 1, 5, 200);
            List<String> activities = strings(item.activities(), 1, 10, 300);
            List<String> materials = strings(item.materials(), 0, 20, 200);
            List<String> preparations = strings(item.preparations(), 0, 20, 200);
            String note = optionalText(item.internalNote(), 1000);
            result.add(new ItemWrite(id, item.plannedDate(), item.sequence(), title, objectives, activities, materials, preparations, note));
        }
        result.sort(Comparator.comparing(ItemWrite::plannedDate).thenComparingInt(ItemWrite::sequence));
        return List.copyOf(result);
    }

    private static List<String> strings(List<String> values, int min, int max, int length) {
        if (values == null || values.size() < min || values.size() > max) throw new LessonPlanException("LESSON_PLAN_INCOMPLETE");
        List<String> normalized = values.stream().map(value -> requiredText(value, length, "LESSON_PLAN_INCOMPLETE")).toList();
        if (new HashSet<>(normalized).size() != normalized.size()) throw new LessonPlanException("LESSON_PLAN_INCOMPLETE");
        return normalized;
    }

    private static String requiredText(String value, int max, String code) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw new LessonPlanException(code);
        return normalized;
    }

    private static String optionalText(String value, int max) {
        if (value == null) return null;
        return requiredText(value, max, "VALIDATION_ERROR");
    }

    private static String normalizeSummary(String value) {
        return requiredText(value, 500, "VALIDATION_ERROR");
    }

    private static Diff diff(ScheduleSnapshot schedule, List<ItemWrite> items, YearMonth month) {
        Set<LocalDate> scheduleDates = new LinkedHashSet<>(schedule.dates());
        Set<LocalDate> planDates = new LinkedHashSet<>();
        Set<UUID> itemIds = new HashSet<>();
        Set<String> dateSequences = new HashSet<>();
        List<InvalidItem> invalid = new ArrayList<>();
        for (ItemWrite item : items) {
            if (item == null) {
                invalid.add(new InvalidItem(null, List.of("item")));
                continue;
            }
            List<String> fields = new ArrayList<>();
            if (item.plannedDate() == null || !YearMonth.from(item.plannedDate()).equals(month)) fields.add("plannedDate");
            else planDates.add(item.plannedDate());
            if (item.sequence() < 1 || item.sequence() > 20) fields.add("sequence");
            else if (item.plannedDate() != null && !dateSequences.add(item.plannedDate() + ":" + item.sequence())) fields.add("sequence");
            if (item.id() != null && !itemIds.add(item.id())) fields.add("id");
            if (item.title() == null || item.title().isBlank() || item.title().trim().length() > 120) fields.add("title");
            if (item.objectives() == null || item.objectives().isEmpty() || item.objectives().size() > 5
                    || item.objectives().stream().anyMatch(value -> value == null || value.isBlank() || value.trim().length() > 200)) fields.add("objectives");
            if (item.activities() == null || item.activities().isEmpty() || item.activities().size() > 10
                    || item.activities().stream().anyMatch(value -> value == null || value.isBlank() || value.trim().length() > 300)) fields.add("activities");
            if (item.materials() == null || item.materials().size() > 20
                    || item.materials().stream().anyMatch(value -> value == null || value.isBlank() || value.trim().length() > 200)) fields.add("materials");
            if (item.preparations() == null || item.preparations().size() > 20
                    || item.preparations().stream().anyMatch(value -> value == null || value.isBlank() || value.trim().length() > 200)) fields.add("preparations");
            if (item.internalNote() != null && (item.internalNote().isBlank() || item.internalNote().trim().length() > 1000)) fields.add("internalNote");
            if (!fields.isEmpty()) invalid.add(new InvalidItem(item.id(), fields));
        }
        List<LocalDate> missing = scheduleDates.stream().filter(date -> !planDates.contains(date)).sorted().toList();
        List<LocalDate> orphan = planDates.stream().filter(date -> !scheduleDates.contains(date)).sorted().toList();
        return new Diff(missing, orphan, invalid);
    }

    private static boolean publishable(ScheduleSnapshot schedule, Diff diff) {
        return schedule.revision() != null && !schedule.dates().isEmpty() && diff.missingPlanDates().isEmpty()
                && diff.orphanPlanItems().isEmpty() && diff.invalidItems().isEmpty();
    }

    private static YearMonth parseMonth(String value) {
        try {
            if (value == null || !value.matches("^[0-9]{4}-(0[1-9]|1[0-2])$")) throw new IllegalArgumentException();
            return YearMonth.parse(value);
        } catch (RuntimeException exception) { throw new LessonPlanException("VALIDATION_ERROR"); }
    }

    private YearMonth parseMonthById(UUID planId) {
        return repository.monthForPlan(planId).map(LessonPlanService::parseMonth)
                .orElseThrow(() -> new LessonPlanException("LESSON_PLAN_DRAFT_NOT_FOUND"));
    }

    private static UUID actor(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated()) throw new LessonPlanException("SESSION_REQUIRED");
        if (!has(authentication, permission)) throw new LessonPlanException(permission + "_DENIED");
        UUID actor = actorId(authentication);
        if (actor == null) throw new LessonPlanException("SESSION_REQUIRED");
        return actor;
    }

    private static UUID actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return null;
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private void event(UUID actorId, RequestMetadata metadata, String action, UUID planId, Map<String, Object> details) {
        audit.record(new Event(Instant.now(), metadata.requestId(), "MGT-LESSON-PLAN", "OPERATION", "ADMIN", actorId,
                repository.adminDisplayName(actorId), action, "LESSON_PLAN", planId, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}

    public static final class LessonPlanException extends RuntimeException {
        private final String code;
        private final Map<String, Object> details;
        public LessonPlanException(String code) { this(code, Map.of()); }
        public LessonPlanException(String code, Map<String, Object> details) { this.code = code; this.details = Map.copyOf(details); }
        public String code() { return code; }
        public Map<String, Object> details() { return details; }
    }
}
