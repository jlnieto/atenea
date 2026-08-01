package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class SessionTurnAttachmentRepositoryIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private SessionTurnRepository sessionTurnRepository;

    @Autowired
    private WorkSessionAttachmentRepository attachmentRepository;

    @Autowired
    private WorkerNodeRepository workerNodeRepository;

    @Autowired
    private SessionTurnAttachmentRepository bindingRepository;

    @Test
    void insertsOnceAndReadsExactBindingsInPersistedOrder() {
        Instant now = Instant.parse("2026-08-01T22:50:00Z");
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, now);
        SessionTurnEntity firstTurn = createTurn(session, "First image turn", now);
        SessionTurnEntity secondTurn = createTurn(session, "Second image turn", now.plusSeconds(1));
        String workerId = createWorker(now).getId();
        WorkSessionAttachmentEntity firstAttachment = createAttachment(
                session, project, workerId, now, "a".repeat(64));
        WorkSessionAttachmentEntity secondAttachment = createAttachment(
                session, project, workerId, now, "b".repeat(64));

        assertEquals(1, bindingRepository.insert(
                session.getId(), firstTurn.getId(), secondAttachment.getId(), (short) 1));
        assertEquals(1, bindingRepository.insert(
                session.getId(), firstTurn.getId(), firstAttachment.getId(), (short) 0));
        assertEquals(1, bindingRepository.insert(
                session.getId(), secondTurn.getId(), secondAttachment.getId(), (short) 0));

        List<SessionTurnAttachmentEntity> firstTurnBindings = bindingRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
                        session.getId(), firstTurn.getId());
        assertEquals(List.of((short) 0, (short) 1), firstTurnBindings.stream()
                .map(SessionTurnAttachmentEntity::getPosition)
                .toList());
        assertEquals(List.of(firstAttachment.getId(), secondAttachment.getId()), firstTurnBindings.stream()
                .map(SessionTurnAttachmentEntity::getAttachmentId)
                .toList());

        List<SessionTurnAttachmentEntity> allBindings = bindingRepository
                .findByWorkSessionIdAndSessionTurnIdInOrderBySessionTurnIdAscPositionAsc(
                        session.getId(), List.of(secondTurn.getId(), firstTurn.getId()));
        assertEquals(List.of(firstTurn.getId(), firstTurn.getId(), secondTurn.getId()), allBindings.stream()
                .map(SessionTurnAttachmentEntity::getSessionTurnId)
                .toList());
        assertEquals(List.of((short) 0, (short) 1, (short) 0), allBindings.stream()
                .map(SessionTurnAttachmentEntity::getPosition)
                .toList());
    }

    @Test
    void exposesNoApplicationSaveUpdateOrDeleteMethod() {
        Set<String> methodNames = Arrays.stream(SessionTurnAttachmentRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "insert",
                        "findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc",
                        "findByWorkSessionIdAndSessionTurnIdInOrderBySessionTurnIdAscPositionAsc"),
                methodNames);
        assertFalse(methodNames.contains("save"));
        assertFalse(methodNames.stream().anyMatch(name -> name.startsWith("update")));
        assertFalse(methodNames.stream().anyMatch(name -> name.startsWith("delete")));
    }

    private ProjectEntity createProject(Instant now) {
        ProjectEntity project = new ProjectEntity();
        project.setName("turn-binding-" + UUID.randomUUID());
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        return projectRepository.saveAndFlush(project);
    }

    private WorkSessionEntity createSession(ProjectEntity project, Instant now) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Immutable ordered attachment binding");
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:work-session:" + UUID.randomUUID());
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return workSessionRepository.saveAndFlush(session);
    }

    private SessionTurnEntity createTurn(WorkSessionEntity session, String message, Instant createdAt) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText(message);
        turn.setInternal(false);
        turn.setCreatedAt(createdAt);
        return sessionTurnRepository.saveAndFlush(turn);
    }

    private WorkerNodeEntity createWorker(Instant now) {
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId("binding-worker-" + UUID.randomUUID());
        worker.setProtocolVersion("agent-run-worker/v1");
        worker.setEndpoint("https://worker.invalid");
        worker.setEnabled(false);
        worker.setHealthy(false);
        worker.setNormalCapacity(0);
        worker.setHeavyCapacity(0);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities("");
        worker.setCreatedAt(now);
        worker.setUpdatedAt(now);
        return workerNodeRepository.saveAndFlush(worker);
    }

    private WorkSessionAttachmentEntity createAttachment(
            WorkSessionEntity session,
            ProjectEntity project,
            String workerId,
            Instant now,
            String sha256
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(UUID.randomUUID());
        attachment.setWorkSession(session);
        attachment.setProject(project);
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("synthetic.png");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(1024);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(now.plus(1, ChronoUnit.DAYS));
        attachment.setSha256(sha256);
        attachment.setWorkerId(workerId);
        attachment.setStorageIdentity("synthetic-storage:" + attachment.getId());
        attachment.setCreatedAt(now);
        attachment.setIndexedAt(now);
        return attachmentRepository.saveAndFlush(attachment);
    }
}
