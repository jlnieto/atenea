package com.atenea.service.worksession;

import com.atenea.api.worksession.RepositoryRoleSetResponse;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryRoleSetService {
    private static final String PROGRAM_BRANCH = "program/remote-codex-worker-platform";
    private static final Map<RepositoryRoleKind, String> PROFILES = Map.of(
            RepositoryRoleKind.ATENEA_CODE, "atenea-code-v1",
            RepositoryRoleKind.PROGRAMME_OPENSPEC, "openspec-strict-v1",
            RepositoryRoleKind.WORKER_SOURCE, "worker-contract-v1");

    private final WorkSessionRepository sessions;
    private final AgentRunRepository agentRuns;
    private final WorkSessionRepositoryRoleRepository roles;
    private final RemoteWorkerClient worker;

    public RepositoryRoleSetService(
            WorkSessionRepository sessions,
            AgentRunRepository agentRuns,
            WorkSessionRepositoryRoleRepository roles,
            RemoteWorkerClient worker
    ) {
        this.sessions = sessions;
        this.agentRuns = agentRuns;
        this.roles = roles;
        this.worker = worker;
    }

    @Transactional
    public RepositoryRoleSetResponse ensure(Long sessionId) {
        WorkSessionEntity session = sessions.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        requireIdleExact(session);
        List<WorkSessionRepositoryRoleEntity> existing =
                roles.findByWorkSessionIdOrderByRoleAsc(sessionId);
        if (!existing.isEmpty()) {
            requireCompleteSet(existing);
            return response(existing);
        }
        UUID changeIdentity = UUID.randomUUID();
        RemoteWorkerClient.RepositoryRoleSet observed =
                worker.ensureRepositoryRoles(session, changeIdentity.toString());
        validateObservation(session, changeIdentity, observed);
        Instant now = Instant.now();
        List<WorkSessionRepositoryRoleEntity> created = new ArrayList<>();
        for (RemoteWorkerClient.RepositoryRole observedRole : observed.roles()) {
            RepositoryRoleKind kind = RepositoryRoleKind.valueOf(observedRole.role());
            WorkSessionRepositoryRoleEntity entity = new WorkSessionRepositoryRoleEntity();
            entity.setId(UUID.randomUUID());
            entity.setWorkSession(session);
            entity.setChangeIdentity(changeIdentity);
            entity.setRole(kind);
            entity.setAuthority("READ_WRITE");
            entity.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
            entity.setBranch(expectedBranch(kind));
            entity.setCommit(observedRole.commit());
            entity.setMirrorIdentitySha256(observedRole.mirrorIdentitySha256());
            entity.setWorktreeIdentitySha256(observedRole.worktreeIdentitySha256());
            entity.setValidationProfile(PROFILES.get(kind));
            entity.setReadiness(RepositoryRoleReadiness.DRAFT);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            created.add(roles.save(entity));
        }
        created.sort(Comparator.comparing(WorkSessionRepositoryRoleEntity::getRole));
        return response(created);
    }

    @Transactional
    public RepositoryRoleSetResponse markValidated(
            Long sessionId, RepositoryRoleKind role, String source, String projection
    ) {
        requireHash(source);
        requireHash(projection);
        WorkSessionRepositoryRoleEntity entity = roles.findByWorkSessionIdAndRole(sessionId, role)
                .orElseThrow(() -> new WorkSessionOperationBlockedException(
                        "Repository role is not declared for this WorkSession"));
        entity.setSourceFingerprintSha256(source);
        entity.setValidationProjectionSha256(projection);
        entity.setReadiness(RepositoryRoleReadiness.VALIDATED);
        entity.setValidatedAt(Instant.now());
        entity.setIntegrationReadyAt(null);
        entity.setUpdatedAt(Instant.now());
        roles.save(entity);
        return response(roles.findByWorkSessionIdOrderByRoleAsc(sessionId));
    }

    @Transactional
    public RepositoryRoleSetResponse markIntegrationReady(Long sessionId, RepositoryRoleKind role) {
        List<WorkSessionRepositoryRoleEntity> linked =
                roles.findByWorkSessionIdOrderByRoleAsc(sessionId);
        requireCompleteSet(linked);
        if (linked.stream().anyMatch(value -> value.getReadiness() == RepositoryRoleReadiness.DRAFT)) {
            throw new WorkSessionOperationBlockedException(
                    "Every linked repository role must validate before integration readiness");
        }
        WorkSessionRepositoryRoleEntity entity = linked.stream()
                .filter(value -> value.getRole() == role)
                .findFirst().orElseThrow();
        entity.setReadiness(RepositoryRoleReadiness.INTEGRATION_READY);
        entity.setIntegrationReadyAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        roles.save(entity);
        return response(linked);
    }

    private void validateObservation(
            WorkSessionEntity session, UUID changeIdentity,
            RemoteWorkerClient.RepositoryRoleSet observed
    ) {
        if (observed == null || observed.valuesExposed()
                || !session.getRemoteSessionId().toString().equals(observed.sessionId())
                || !session.getWorkspaceIdentity().equals(observed.workspaceIdentity())
                || !changeIdentity.toString().equals(observed.changeIdentity())
                || observed.roles() == null || observed.roles().size() != 3) {
            throw blocked();
        }
        EnumSet<RepositoryRoleKind> seen = EnumSet.noneOf(RepositoryRoleKind.class);
        for (RemoteWorkerClient.RepositoryRole value : observed.roles()) {
            RepositoryRoleKind kind;
            try {
                kind = RepositoryRoleKind.valueOf(value.role());
            } catch (RuntimeException exception) {
                throw blocked();
            }
            if (!seen.add(kind)
                    || !"READ_WRITE".equals(value.authority())
                    || !ProjectCodexIdentity.REPOSITORY.equals(value.repository())
                    || !expectedBranch(kind).equals(value.branch())
                    || !PROFILES.get(kind).equals(value.validationProfile())
                    || !"DRAFT".equals(value.readiness())
                    || !isCommit(value.commit())
                    || !isHash(value.mirrorIdentitySha256())
                    || !isHash(value.worktreeIdentitySha256())
                    || (kind == RepositoryRoleKind.ATENEA_CODE
                        && !session.getCanonicalSourceCommit().equals(value.commit()))) {
                throw blocked();
            }
        }
        if (!seen.equals(EnumSet.allOf(RepositoryRoleKind.class))) throw blocked();
    }

    private void requireIdleExact(WorkSessionEntity session) {
        if (session.getStatus() != WorkSessionStatus.OPEN
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || agentRuns.existsBySessionIdAndStatusIn(
                        session.getId(), AgentRunStatus.nonTerminalStatuses())) {
            throw new WorkSessionOperationBlockedException(
                    "Repository roles require an idle exactly owned Atenea WorkSession");
        }
    }

    private void requireCompleteSet(List<WorkSessionRepositoryRoleEntity> values) {
        if (values.size() != 3
                || values.stream().map(WorkSessionRepositoryRoleEntity::getRole)
                    .collect(java.util.stream.Collectors.toSet()).size() != 3
                || values.stream().map(WorkSessionRepositoryRoleEntity::getChangeIdentity)
                    .distinct().count() != 1) {
            throw blocked();
        }
    }

    private RepositoryRoleSetResponse response(List<WorkSessionRepositoryRoleEntity> values) {
        requireCompleteSet(values);
        RepositoryRoleReadiness linked = values.stream().allMatch(value ->
                value.getReadiness() == RepositoryRoleReadiness.INTEGRATION_READY)
                ? RepositoryRoleReadiness.INTEGRATION_READY
                : values.stream().allMatch(value ->
                    value.getReadiness() != RepositoryRoleReadiness.DRAFT)
                    ? RepositoryRoleReadiness.VALIDATED
                    : RepositoryRoleReadiness.DRAFT;
        return new RepositoryRoleSetResponse(
                values.get(0).getWorkSession().getId(),
                values.get(0).getChangeIdentity(),
                linked,
                values.stream().map(value -> new RepositoryRoleSetResponse.Role(
                        value.getRole(), value.getAuthority(), value.getRepositoryUrl(),
                        value.getBranch(), value.getCommit(), value.getMirrorIdentitySha256(),
                        value.getWorktreeIdentitySha256(), value.getValidationProfile(),
                        value.getReadiness())).toList(),
                false);
    }

    private String expectedBranch(RepositoryRoleKind role) {
        return role == RepositoryRoleKind.ATENEA_CODE
                ? ProjectCodexIdentity.BRANCH : PROGRAM_BRANCH;
    }
    private void requireHash(String value) {
        if (!isHash(value)) {
            throw new IllegalArgumentException("SHA-256 is invalid");
        }
    }
    private boolean isHash(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }
    private boolean isCommit(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$");
    }
    private WorkSessionOperationBlockedException blocked() {
        return new WorkSessionOperationBlockedException(
                "Multi-repository ownership is incomplete or conflicting");
    }
}
