package com.ramiart.admin.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.schedule.application.ScheduleModels.AttendanceGeneration;
import com.ramiart.admin.schedule.application.ScheduleModels.ClassGroupView;
import com.ramiart.admin.schedule.application.ScheduleModels.Item;
import com.ramiart.admin.schedule.application.ScheduleModels.PublishWrite;
import com.ramiart.admin.schedule.application.ScheduleModels.Publication;
import com.ramiart.admin.schedule.application.ScheduleRepository.AttendanceOccurrence;
import com.ramiart.admin.schedule.application.ScheduleRepository.Claim;
import com.ramiart.admin.schedule.application.ScheduleRepository.ScheduleRecord;
import com.ramiart.admin.schedule.application.ScheduleService.RequestMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduleServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DRAFT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PREVIOUS = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ITEM = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SLOT = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID GROUP = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID KEY = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final RequestMetadata METADATA = new RequestMetadata("req-test", "127.0.0.1", "test-agent");

    private ScheduleRepository repository;
    private ScheduleService service;
    private ScheduleRecord draft;
    private ScheduleRecord published;
    private Item item;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduleRepository.class);
        service = new ScheduleService(repository, mock(AuditRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC));
        draft = new ScheduleRecord(DRAFT, "2026-09", 2, "DRAFT", 3, PREVIOUS, null, null, null, "change");
        published = new ScheduleRecord(DRAFT, "2026-09", 2, "PUBLISHED", 4, PREVIOUS, ACTOR, "원장", NOW, "change");
        item = new Item(ITEM, SLOT, GROUP, 1, LocalTime.of(15, 30), LocalTime.of(16, 30),
                "Chrome 출결 수업", "ROOM-A", 0, new ClassGroupView(GROUP, "ART_A", "A반", UUID.randomUUID(), "미술", 10));
        when(repository.claim(any(), eq(KEY), any())).thenReturn(new Claim(true, null));
        when(repository.findById(DRAFT, true)).thenReturn(Optional.of(draft));
        when(repository.findById(DRAFT, false)).thenReturn(Optional.of(published));
        when(repository.find("2026-09", "PUBLISHED", true)).thenReturn(Optional.of(
                new ScheduleRecord(PREVIOUS, "2026-09", 1, "PUBLISHED", 1, null, ACTOR, "원장", NOW, null)));
        when(repository.items(DRAFT)).thenReturn(List.of(item));
        when(repository.overrides(DRAFT)).thenReturn(List.of());
        when(repository.publish(DRAFT, 3, ACTOR, NOW)).thenReturn(1);
        when(repository.cancelOpenAttendance(PREVIOUS, LocalDate.of(2026, 9, 28))).thenReturn(1);
        when(repository.createAttendance(any(), any())).thenReturn(1);
        when(repository.publicationResult(DRAFT)).thenReturn(Optional.of(
                new AttendanceGeneration(LocalDate.of(2026, 9, 28), 1, 1, 1)));
    }

    @Test
    void publicationCancelsOldSessionAndCreatesTargetSnapshotForFutureOccurrence() {
        Publication result = service.publish("2026-09", new PublishWrite(DRAFT, 3), ACTOR, KEY, METADATA);

        ArgumentCaptor<AttendanceOccurrence> occurrence = ArgumentCaptor.forClass(AttendanceOccurrence.class);
        verify(repository).createAttendance(occurrence.capture(), any());
        assertThat(occurrence.getValue().attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(occurrence.getValue().startTime()).isEqualTo(LocalTime.of(15, 30));
        assertThat(occurrence.getValue().scheduleSlotId()).isEqualTo(SLOT);
        verify(repository).savePublicationResult(DRAFT, LocalDate.of(2026, 9, 28), 1, 1, 1);
        assertThat(result.attendanceGeneration().createdTargetCount()).isEqualTo(1);
    }

    @Test
    void publicationStopsBeforeArchivingWhenAttendanceWasAlreadyRecorded() {
        when(repository.hasRecordedAttendance(PREVIOUS, LocalDate.of(2026, 9, 28))).thenReturn(true);

        assertThatThrownBy(() -> service.publish("2026-09", new PublishWrite(DRAFT, 3), ACTOR, KEY, METADATA))
                .isInstanceOf(ScheduleException.class)
                .extracting("code")
                .isEqualTo("SCHEDULE_ATTENDANCE_ALREADY_RECORDED");
        verify(repository, never()).archivePublished(any());
        verify(repository, never()).publish(any(), anyLong(), any(), any());
    }
}
