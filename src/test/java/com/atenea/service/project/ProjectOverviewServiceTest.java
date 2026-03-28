package com.atenea.service.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.SessionOperationalSnapshotService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ProjectOverviewServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionOperationalSnapshotService sessionOperationalSnapshotService;

    @Test
    void getOverviewReturnsCanonicalOpenSessionPerProject() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(
                        projectRepository,
                        workSessionRepository,
                        sessionOperationalSnapshotService);
        ProjectEntity project = project(1L, "Atenea", "/workspace/repos/internal/atenea");
        WorkSessionEntity session = openSession(project, 50L, "Fix launch conversationally");

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(workSessionRepository.findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
                1L,
                java.util.Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING))).thenReturn(Optional.of(session));
        when(sessionOperationalSnapshotService.snapshot(session)).thenReturn(
                new SessionOperationalSnapshotResponse(true, true, "feature/session", true));

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertEquals(1, response.size());
        assertEquals("Atenea", response.get(0).project().name());
        assertEquals(50L, response.get(0).workSession().sessionId());
        assertEquals(true, response.get(0).workSession().current());
        assertEquals(true, response.get(0).workSession().runInProgress());
        assertEquals(WorkSessionPullRequestStatus.NOT_CREATED, response.get(0).workSession().pullRequestStatus());
        assertNull(response.get(0).workSession().closeBlockedState());
    }

    @Test
    void getOverviewReturnsLatestClosedSessionWhenNoOpenSessionExists() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(
                        projectRepository,
                        workSessionRepository,
                        sessionOperationalSnapshotService);
        ProjectEntity project = project(1L, "WAB", "/workspace/repos/internal/wab");
        WorkSessionEntity session = closedSession(project, 70L, "Closed session");

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(workSessionRepository.findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
                1L,
                java.util.Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING))).thenReturn(Optional.empty());
        when(workSessionRepository.findFirstByProjectIdOrderByLastActivityAtDesc(1L)).thenReturn(Optional.of(session));
        when(sessionOperationalSnapshotService.snapshot(session)).thenReturn(
                new SessionOperationalSnapshotResponse(true, false, "main", false));

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertEquals(70L, response.get(0).workSession().sessionId());
        assertEquals(false, response.get(0).workSession().current());
        assertEquals(WorkSessionStatus.CLOSED, response.get(0).workSession().status());
    }

    @Test
    void getOverviewReturnsNullWorkSessionWhenProjectHasNoSessions() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(
                        projectRepository,
                        workSessionRepository,
                        sessionOperationalSnapshotService);
        ProjectEntity project = project(1L, "WAB", "/workspace/repos/internal/wab");

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(workSessionRepository.findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
                1L,
                java.util.Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING))).thenReturn(Optional.empty());
        when(workSessionRepository.findFirstByProjectIdOrderByLastActivityAtDesc(1L)).thenReturn(Optional.empty());

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertNull(response.get(0).workSession());
    }

    @Test
    void getOverviewPrioritizesClosingSessionAsCurrentWhenPresent() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(
                        projectRepository,
                        workSessionRepository,
                        sessionOperationalSnapshotService);
        ProjectEntity project = project(1L, "Atenea", "/workspace/repos/internal/atenea");
        WorkSessionEntity closingSession = openSession(project, 51L, "Closing session");
        closingSession.setStatus(WorkSessionStatus.CLOSING);
        closingSession.setCloseBlockedState("dirty_worktree");
        closingSession.setCloseBlockedReason("Repository working tree is not clean");
        closingSession.setCloseBlockedAction("Clean or discard local changes manually before retrying close");
        closingSession.setCloseRetryable(false);

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(workSessionRepository.findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
                1L,
                java.util.Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING))).thenReturn(Optional.of(closingSession));
        when(sessionOperationalSnapshotService.snapshot(closingSession)).thenReturn(
                new SessionOperationalSnapshotResponse(true, true, "main", false));

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertEquals(51L, response.get(0).workSession().sessionId());
        assertEquals(true, response.get(0).workSession().current());
        assertEquals(WorkSessionStatus.CLOSING, response.get(0).workSession().status());
        assertEquals("dirty_worktree", response.get(0).workSession().closeBlockedState());
        assertEquals("Repository working tree is not clean", response.get(0).workSession().closeBlockedReason());
        assertEquals(
                "Clean or discard local changes manually before retrying close",
                response.get(0).workSession().closeBlockedAction());
        assertEquals(false, response.get(0).workSession().closeRetryable());
    }

    private static ProjectEntity project(Long id, String name, String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName(name);
        project.setDescription("desc");
        project.setRepoPath(repoPath);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.parse("2026-03-22T08:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-22T08:05:00Z"));
        return project;
    }

    private static WorkSessionEntity openSession(ProjectEntity project, Long id, String title) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(id);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle(title);
        session.setBaseBranch("main");
        session.setWorkspaceBranch(null);
        session.setExternalThreadId("thread-stable");
        session.setPullRequestUrl(null);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setFinalCommitSha(null);
        session.setOpenedAt(Instant.parse("2026-03-22T10:00:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-22T10:10:00Z"));
        session.setPublishedAt(null);
        session.setClosedAt(null);
        session.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-22T10:10:00Z"));
        return session;
    }

    private static WorkSessionEntity closedSession(ProjectEntity project, Long id, String title) {
        WorkSessionEntity session = openSession(project, id, title);
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(Instant.parse("2026-03-22T09:30:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-22T09:30:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-22T09:30:00Z"));
        return session;
    }
}
