package com.ramiart.admin.classprogram.application;

import static com.ramiart.admin.classprogram.application.ClassProgramModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.media.application.MediaUrlResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ClassProgramServiceTest {
    private static final UUID COURSE_ID = UUID.fromString("037d10be-bc14-47f3-b674-06295baa6a42");
    private static final UUID PROGRAM_ID = UUID.fromString("137d10be-bc14-47f3-b674-06295baa6a42");
    private static final UUID ASSET_ID = UUID.fromString("237d10be-bc14-47f3-b674-06295baa6a42");
    private static final UUID ACTOR_ID = UUID.fromString("337d10be-bc14-47f3-b674-06295baa6a42");

    @Test
    void listReturnsAbsoluteBackendMediaUrlForSavedClassProgramImage() {
        ClassProgramRepository repository = mock(ClassProgramRepository.class);
        Stored program = new Stored(PROGRAM_ID, COURSE_ID, 3, "PUBLISHED", true, "5~7세",
                "그리며 시작하는 첫 미술", "설명", List.of("활동"), 80, 1, 2,
                "5~7세", 80, 1, true, ASSET_ID,
                "/media/2026/10/b57034ea-3a0d-41ca-838b-a4a6e76c18b0.jpg", "대체 텍스트", 0, 5,
                Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"),
                "COURSE_78ACF196", "유치부", true, "image/jpeg", 128000, 800, 600,
                Instant.parse("2026-10-29T00:00:00Z"));
        when(repository.findAll()).thenReturn(List.of(new CoursePrograms(COURSE_ID, "COURSE_78ACF196",
                "유치부", "5~7세", 80, 1, 2, true, program, null,
                new Actions(false, false, false))));
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), "",
                List.of(new SimpleGrantedAuthority("CONTENT_PROGRAM_READ")));
        var service = new ClassProgramService(repository, mock(AuditRecorder.class),
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC),
                new MediaUrlResolver("https://rami-art-backend.onrender.com/"));

        ProgramItem item = service.list(authentication).items().getFirst();

        assertThat(item.media()).isInstanceOfSatisfying(java.util.Map.class, media ->
                assertThat(media.get("publicUrl")).isEqualTo(
                        "https://rami-art-backend.onrender.com/media/2026/10/b57034ea-3a0d-41ca-838b-a4a6e76c18b0.jpg"));
    }
}
