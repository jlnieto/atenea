package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.ProjectOverviewService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileProjectOverviewServiceTest {

    @Mock
    private ProjectOverviewService projectOverviewService;

    @Test
    void mobileOverviewDoesNotExposeClosedWorkSessionAsActiveSession() {
        when(projectOverviewService.getOverview()).thenReturn(List.of(new ProjectOverviewResponse(
                project(),
                new ProjectOverviewResponse.WorkSessionOverviewResponse(
                        12L,
                        false,
                        WorkSessionStatus.CLOSED,
                        "Sesion cerrada",
                        "master",
                        null,
                        "https://github.test/pr/1",
                        WorkSessionPullRequestStatus.MERGED,
                        null,
                        null,
                        null,
                        false,
                        true,
                        true,
                        "master",
                        false,
                        Instant.parse("2026-05-19T10:00:00Z"),
                        Instant.parse("2026-05-19T09:00:00Z"),
                        Instant.parse("2026-05-19T10:41:49Z"),
                        Instant.parse("2026-05-19T10:41:49Z")))));
        MobileProjectOverviewService service = new MobileProjectOverviewService(projectOverviewService);

        var response = service.getOverview();

        assertEquals(1, response.size());
        assertEquals(7L, response.get(0).projectId());
        assertNull(response.get(0).session());
    }

    private ProjectResponse project() {
        return new ProjectResponse(
                7L,
                "Fomasys",
                null,
                "/workspace/repos/internal/fomasys",
                "master",
                Instant.parse("2026-05-19T09:00:00Z"),
                Instant.parse("2026-05-19T09:00:00Z"));
    }
}
