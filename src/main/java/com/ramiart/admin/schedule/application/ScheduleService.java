package com.ramiart.admin.schedule.application;

import static com.ramiart.admin.schedule.application.ScheduleModels.*;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.schedule.application.ScheduleRepository.ScheduleRecord;
import com.ramiart.admin.schedule.application.ScheduleRepository.AttendanceOccurrence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private final ScheduleRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public ScheduleService(ScheduleRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ScheduleView get(String yearMonth, String mode) {
        validateMonth(yearMonth);
        String status = "DRAFT".equalsIgnoreCase(mode) ? "DRAFT" : "PUBLISHED";
        ScheduleRecord record = repository.find(yearMonth, status, false).orElseThrow(() ->
                new ScheduleException("DRAFT".equals(status) ? "MONTHLY_SCHEDULE_DRAFT_NOT_FOUND" : "MONTHLY_SCHEDULE_NOT_FOUND"));
        return view(record);
    }

    @Transactional
    public ScheduleView createDraft(String yearMonth, UUID actor, UUID key, RequestMetadata metadata) {
        validateMonth(yearMonth);
        String scope = actor + ":POST:/admin/monthly-schedules/{yearMonth}/drafts";
        ScheduleRepository.Claim claim = repository.claim(scope, key, hash(yearMonth));
        if (!claim.claimed()) return view(repository.findById(claim.resourceId(), false).orElseThrow());
        repository.find(yearMonth, "DRAFT", true).ifPresent(existing -> {
            throw new ScheduleException("MONTHLY_SCHEDULE_DRAFT_EXISTS", Map.of("draftId", existing.id()));
        });
        ScheduleRecord published = repository.find(yearMonth, "PUBLISHED", true).orElse(null);
        UUID draftId = repository.insertDraft(yearMonth, repository.nextRevision(yearMonth), published == null ? null : published.id(), actor);
        if (published != null) cloneContents(published.id(), draftId);
        event(actor, metadata, "MONTHLY_SCHEDULE_DRAFT_CREATED", draftId, Map.of("yearMonth", yearMonth));
        repository.complete(scope, key, draftId, 201);
        return view(repository.findById(draftId, false).orElseThrow());
    }

    @Transactional
    public ScheduleView save(String yearMonth, UUID draftId, DraftWrite command, UUID actor, RequestMetadata metadata) {
        validateMonth(yearMonth);
        if (command == null || command.items() == null || command.overrides() == null || command.items().size() > 200
                || command.overrides().size() > 300 || command.version() < 0) throw new ScheduleException("VALIDATION_ERROR");
        ScheduleRecord draft = repository.findById(draftId, true).orElseThrow(() -> new ScheduleException("MONTHLY_SCHEDULE_DRAFT_NOT_FOUND"));
        if (!yearMonth.equals(draft.yearMonth()) || !"DRAFT".equals(draft.status())) throw new ScheduleException("PUBLISHED_SCHEDULE_IMMUTABLE");
        List<Item> items = normalizeItems(command.items(), actor);
        List<ScheduleOverride> overrides = normalizeOverrides(command.overrides(), YearMonth.parse(yearMonth), items);
        List<Issue> issues = validate(items, overrides);
        if (!issues.isEmpty()) throw new ScheduleException("SCHEDULE_CONFLICT", Map.of("issues", issues));
        if (repository.updateDraft(draftId, command.version(), normalizeSummary(command.changeSummary())) != 1)
            throw new ScheduleException("SCHEDULE_VERSION_CONFLICT");
        repository.replaceOverrides(draftId, List.of());
        repository.replaceItems(draftId, items);
        repository.replaceOverrides(draftId, overrides);
        event(actor, metadata, "MONTHLY_SCHEDULE_DRAFT_SAVED", draftId, Map.of("itemCount", items.size(), "overrideCount", overrides.size()));
        return view(repository.findById(draftId, false).orElseThrow());
    }

    @Transactional
    public Publication publish(String yearMonth, PublishWrite command, UUID actor, UUID key, RequestMetadata metadata) {
        validateMonth(yearMonth);
        if (command == null || command.draftId() == null || command.draftVersion() < 0) throw new ScheduleException("VALIDATION_ERROR");
        String scope = actor + ":POST:/admin/monthly-schedules/{yearMonth}/publications";
        String requestHash = hash(yearMonth + ':' + command.draftId() + ':' + command.draftVersion());
        ScheduleRepository.Claim claim = repository.claim(scope, key, requestHash);
        if (!claim.claimed()) return publication(repository.findById(claim.resourceId(), false).orElseThrow());
        ScheduleRecord draft = repository.findById(command.draftId(), true).orElseThrow(() -> new ScheduleException("MONTHLY_SCHEDULE_DRAFT_NOT_FOUND"));
        if (!yearMonth.equals(draft.yearMonth()) || !"DRAFT".equals(draft.status())) throw new ScheduleException("MONTHLY_SCHEDULE_DRAFT_NOT_FOUND");
        List<Issue> issues = validate(repository.items(draft.id()), repository.overrides(draft.id()));
        if (!issues.isEmpty()) throw new ScheduleException("SCHEDULE_NOT_PUBLISHABLE", Map.of("issues", issues));
        ScheduleRecord previous = repository.find(yearMonth, "PUBLISHED", true).orElse(null);
        Instant now = clock.instant();
        LocalDate generatedFrom = LocalDate.now(clock.withZone(STUDIO_ZONE));
        if (previous != null && repository.hasRecordedAttendance(previous.id(), generatedFrom))
            throw new ScheduleException("SCHEDULE_ATTENDANCE_ALREADY_RECORDED");
        repository.archivePublished(yearMonth);
        if (repository.publish(draft.id(), command.draftVersion(), actor, now) != 1) throw new ScheduleException("SCHEDULE_VERSION_CONFLICT");
        int cancelled = previous == null ? 0 : repository.cancelOpenAttendance(previous.id(), generatedFrom);
        List<AttendanceOccurrence> occurrences = attendanceOccurrences(YearMonth.parse(yearMonth), repository.items(draft.id()),
                repository.overrides(draft.id()), generatedFrom);
        int targets = 0;
        for (AttendanceOccurrence occurrence : occurrences) targets += repository.createAttendance(occurrence, STUDIO_ZONE);
        repository.savePublicationResult(draft.id(), generatedFrom, occurrences.size(), targets, cancelled);
        event(actor, metadata, "MONTHLY_SCHEDULE_PUBLISHED", draft.id(), Map.of("yearMonth", yearMonth));
        repository.complete(scope, key, draft.id(), 201);
        return publication(repository.findById(draft.id(), false).orElseThrow());
    }

    private void cloneContents(UUID sourceId, UUID draftId) {
        List<Item> source = repository.items(sourceId);
        Map<UUID, UUID> ids = new HashMap<>();
        List<Item> items = source.stream().map(item -> {
            UUID id = UUID.randomUUID(); ids.put(item.id(), id);
            return new Item(id, item.scheduleSlotId(), item.classGroupId(), item.dayOfWeek(), item.startTime(), item.endTime(),
                    item.title(), item.roomCode(), item.displayOrder(), item.classGroup());
        }).toList();
        List<ScheduleOverride> overrides = repository.overrides(sourceId).stream().map(value -> new ScheduleOverride(UUID.randomUUID(),
                value.scheduleItemId() == null ? null : ids.get(value.scheduleItemId()), value.classGroupId(), value.targetDate(),
                value.type(), value.startTime(), value.endTime(), value.title(), value.roomCode(), value.reason())).toList();
        repository.replaceItems(draftId, items); repository.replaceOverrides(draftId, overrides);
    }

    private List<Item> normalizeItems(List<Item> values, UUID actor) {
        Set<UUID> ids = new HashSet<>(); Set<UUID> slots = new HashSet<>(); List<Item> result = new ArrayList<>();
        for (Item value : values) {
            if (value == null || value.id() == null || value.classGroupId() == null || !ids.add(value.id())
                    || value.dayOfWeek() < 1 || value.dayOfWeek() > 7 || !validTime(value.startTime(), value.endTime())
                    || value.displayOrder() < 0 || text(value.title(), 1, 100) == null || text(value.roomCode(), 1, 30) == null)
                throw new ScheduleException("VALIDATION_ERROR");
            UUID slot = value.scheduleSlotId();
            if (slot == null) {
                if (!repository.activeClassGroup(value.classGroupId())) throw new ScheduleException("SCHEDULE_SLOT_CLASS_GROUP_MISMATCH");
                slot = repository.createSlot(value.classGroupId(), actor);
            } else if (!repository.slotClassGroup(slot).filter(value.classGroupId()::equals).isPresent()) {
                throw new ScheduleException("SCHEDULE_SLOT_CLASS_GROUP_MISMATCH");
            }
            if (!slots.add(slot)) throw new ScheduleException("VALIDATION_ERROR");
            result.add(new Item(value.id(), slot, value.classGroupId(), value.dayOfWeek(), value.startTime(), value.endTime(),
                    value.title().trim(), value.roomCode().trim().toUpperCase(Locale.ROOT), value.displayOrder(), value.classGroup()));
        }
        return result;
    }

    private static List<ScheduleOverride> normalizeOverrides(List<ScheduleOverride> values, YearMonth month, List<Item> items) {
        Set<UUID> ids = new HashSet<>(); Set<UUID> itemIds = new HashSet<>(); items.forEach(item -> itemIds.add(item.id()));
        List<ScheduleOverride> result = new ArrayList<>();
        for (ScheduleOverride value : values) {
            if (value == null || value.id() == null || !ids.add(value.id()) || value.targetDate() == null
                    || !YearMonth.from(value.targetDate()).equals(month) || text(value.reason(), 1, 200) == null)
                throw new ScheduleException("VALIDATION_ERROR");
            boolean itemBased = value.scheduleItemId() != null && itemIds.contains(value.scheduleItemId());
            if (("CANCEL".equals(value.type()) && (!itemBased || value.startTime() != null || value.endTime() != null))
                    || ("TIME_CHANGE".equals(value.type()) && (!itemBased || !validTime(value.startTime(), value.endTime()) || text(value.roomCode(), 1, 30) == null))
                    || ("MAKEUP".equals(value.type()) && (!validTime(value.startTime(), value.endTime()) || text(value.roomCode(), 1, 30) == null || (!itemBased && text(value.title(), 1, 100) == null)))
                    || !Set.of("CANCEL", "TIME_CHANGE", "MAKEUP").contains(value.type())) throw new ScheduleException("VALIDATION_ERROR");
            result.add(new ScheduleOverride(value.id(), value.scheduleItemId(), value.classGroupId(), value.targetDate(), value.type(),
                    value.startTime(), value.endTime(), value.title() == null ? null : value.title().trim(),
                    value.roomCode() == null ? null : value.roomCode().trim().toUpperCase(Locale.ROOT), value.reason().trim()));
        }
        return result;
    }

    private static List<Issue> validate(List<Item> items, List<ScheduleOverride> overrides) {
        List<Issue> issues = new ArrayList<>();
        for (int left = 0; left < items.size(); left++) for (int right = left + 1; right < items.size(); right++) {
            Item a = items.get(left), b = items.get(right);
            if (a.dayOfWeek() == b.dayOfWeek() && a.roomCode().equals(b.roomCode()) && overlaps(a.startTime(), a.endTime(), b.startTime(), b.endTime()))
                issues.add(new Issue("ROOM_TIME_OVERLAP", "ITEM", a.id(), "startTime", b.id(), a.roomCode() + " 수업 시간이 겹칩니다."));
            if (a.dayOfWeek() == b.dayOfWeek() && a.displayOrder() == b.displayOrder())
                issues.add(new Issue("DISPLAY_ORDER_DUPLICATED", "ITEM", a.id(), "displayOrder", b.id(), "요일 내 표시 순서가 중복됩니다."));
        }
        Map<String, ScheduleOverride> actions = new HashMap<>();
        for (ScheduleOverride value : overrides) if (value.scheduleItemId() != null && !"MAKEUP".equals(value.type())) {
            String key = value.scheduleItemId() + ":" + value.targetDate(); ScheduleOverride previous = actions.putIfAbsent(key, value);
            if (previous != null) issues.add(new Issue("OVERRIDE_CONFLICT", "OVERRIDE", value.id(), "type", previous.id(), "같은 수업과 날짜에 예외가 중복됩니다."));
        }
        return issues;
    }

    private ScheduleView view(ScheduleRecord record) {
        List<Item> items = repository.items(record.id()); List<ScheduleOverride> overrides = repository.overrides(record.id());
        List<Issue> issues = validate(items, overrides);
        return new ScheduleView(record.id(), record.yearMonth(), record.revision(), record.status(), record.version(), record.basedOn(),
                record.publishedAt(), record.publishedBy() == null ? null : new AdminView(record.publishedBy(), record.publishedByName()),
                items, overrides, expand(YearMonth.parse(record.yearMonth()), items, overrides),
                new Validation(issues.isEmpty(), issues, List.of(), new ChangeSummary(items.size(), 0, 0, overrides.size())),
                "DRAFT".equals(record.status()) ? List.of("EDIT_DRAFT", "PUBLISH_DRAFT") : List.of("CREATE_DRAFT"));
    }

    private Publication publication(ScheduleRecord record) {
        AttendanceGeneration generation = repository.publicationResult(record.id()).orElse(new AttendanceGeneration(
                LocalDate.now(clock.withZone(STUDIO_ZONE)), 0, 0, 0));
        return new Publication(record.id(), record.yearMonth(), record.revision(), record.status(), record.publishedAt(),
                new AdminView(record.publishedBy(), record.publishedByName()), new ChangeSummary(repository.items(record.id()).size(), 0, 0,
                repository.overrides(record.id()).size()), generation);
    }

    private static List<Day> expand(YearMonth month, List<Item> items, List<ScheduleOverride> overrides) {
        Map<LocalDate, List<Session>> sessions = new LinkedHashMap<>(); Map<LocalDate, List<Cancelled>> cancelled = new LinkedHashMap<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day); sessions.put(date, new ArrayList<>()); cancelled.put(date, new ArrayList<>());
            for (Item item : items) if (item.dayOfWeek() == date.getDayOfWeek().getValue()) sessions.get(date).add(regular(item));
        }
        Map<UUID, Item> byId = new HashMap<>(); items.forEach(item -> byId.put(item.id(), item));
        for (ScheduleOverride override : overrides) {
            Item item = override.scheduleItemId() == null ? null : byId.get(override.scheduleItemId());
            if ("CANCEL".equals(override.type()) && item != null) {
                sessions.get(override.targetDate()).removeIf(value -> item.id().equals(value.sourceItemId()));
                cancelled.get(override.targetDate()).add(new Cancelled(item.id(), item.scheduleSlotId(), override.id(), item.startTime(), item.endTime(), item.title(), item.roomCode(), override.reason()));
            } else if ("TIME_CHANGE".equals(override.type()) && item != null) {
                sessions.get(override.targetDate()).removeIf(value -> item.id().equals(value.sourceItemId()));
                sessions.get(override.targetDate()).add(new Session(item.id(), item.scheduleSlotId(), override.id(), override.startTime(), override.endTime(), item.title(), override.roomCode(), "TIME_CHANGE", item.startTime(), item.endTime()));
            } else if ("MAKEUP".equals(override.type())) {
                sessions.get(override.targetDate()).add(new Session(item == null ? null : item.id(), item == null ? null : item.scheduleSlotId(), override.id(), override.startTime(), override.endTime(), item == null ? override.title() : item.title(), override.roomCode(), "MAKEUP", null, null));
            }
        }
        Comparator<Session> order = Comparator.comparing(Session::startTime).thenComparing(Session::roomCode).thenComparing(value -> String.valueOf(value.sourceItemId()));
        List<Day> result = new ArrayList<>(); sessions.forEach((date, values) -> { values.sort(order); result.add(new Day(date, List.copyOf(values), List.copyOf(cancelled.get(date)))); }); return result;
    }

    private static Session regular(Item item) { return new Session(item.id(), item.scheduleSlotId(), null, item.startTime(), item.endTime(), item.title(), item.roomCode(), "REGULAR", null, null); }
    private static List<AttendanceOccurrence> attendanceOccurrences(YearMonth month, List<Item> items,
            List<ScheduleOverride> overrides, LocalDate from) {
        Map<UUID, Item> itemById = new HashMap<>(); items.forEach(item -> itemById.put(item.id(), item));
        Map<UUID, ScheduleOverride> overrideById = new HashMap<>(); overrides.forEach(value -> overrideById.put(value.id(), value));
        List<AttendanceOccurrence> result = new ArrayList<>();
        for (Day day : expand(month, items, overrides)) {
            if (day.date().isBefore(from)) continue;
            for (Session session : day.sessions()) {
                Item item = session.sourceItemId() == null ? null : itemById.get(session.sourceItemId());
                ScheduleOverride override = session.overrideId() == null ? null : overrideById.get(session.overrideId());
                UUID groupId = item == null ? override.classGroupId() : item.classGroupId();
                result.add(new AttendanceOccurrence(session.sourceItemId(), session.overrideId(), session.scheduleSlotId(), groupId,
                        day.date(), session.title(), session.roomCode(), session.startTime(), session.endTime()));
            }
        }
        return result;
    }
    private void validateMonth(String value) { try { YearMonth month = YearMonth.parse(value); YearMonth current = YearMonth.now(clock.withZone(STUDIO_ZONE)); if (month.isBefore(current.minusMonths(24)) || month.isAfter(current.plusMonths(12))) throw new Exception(); } catch (Exception exception) { throw new ScheduleException("YEAR_MONTH_INVALID"); } }
    private static boolean validTime(LocalTime start, LocalTime end) { return start != null && end != null && end.isAfter(start) && start.getMinute() % 5 == 0 && end.getMinute() % 5 == 0 && start.getSecond() == 0 && end.getSecond() == 0; }
    private static boolean overlaps(LocalTime a, LocalTime b, LocalTime c, LocalTime d) { return a.isBefore(d) && c.isBefore(b); }
    private static String text(String value, int min, int max) { if (value == null) return null; String result = value.trim(); return result.length() < min || result.length() > max ? null : result; }
    private static String normalizeSummary(String value) { if (value == null || value.isBlank()) return null; String result = value.trim(); if (result.length() > 500) throw new ScheduleException("VALIDATION_ERROR"); return result; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private void event(UUID actor, RequestMetadata metadata, String action, UUID target, Map<String, Object> details) { audit.record(new Event(clock.instant(), metadata.requestId(), "MGT-SCHEDULE-EDIT", "OPERATION", "ADMIN", actor, null, action, "MONTHLY_SCHEDULE", target, "SUCCESS", null, metadata.ipAddress(), metadata.userAgent(), details)); }
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
