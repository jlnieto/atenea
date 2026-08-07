package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.ValidationOperationKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RemoteWorkerClient {

    private static final String WORKER_ERROR_SCHEMA = "worker-error-v1";
    private static final String WORKSPACE_RELEASE_SCHEMA = "project-workspace-release-v1";
    private static final String WORKSPACE_CAPACITY_OWNER_SCHEMA =
            "project-workspace-capacity-owner-v1";
    private static final String WORKSPACE_RELEASE_PREFLIGHT_SCHEMA =
            "project-workspace-release-diagnosis-v1";
    private static final long WORKSPACE_RELEASE_REVISION = 6;
    private static final int MAX_WORKER_ERROR_BYTES = 1024;
    private static final String PROTOCOL_FAILURE_CODE = "REMOTE_WORKER_PROTOCOL_FAILURE";
    private static final Set<String> WORKSPACE_RELEASE_RECEIPT_FIELDS = Set.of(
            "schemaVersion", "state", "operationId", "idempotencyKey", "sessionId",
            "workspaceIdentity", "projectId", "repository", "branch", "commit",
            "manifestSha256", "workspaceBranch", "workerId", "requestFingerprintSha256",
            "revision", "removed", "released", "retained", "ownershipFingerprintSha256",
            "receiptSha256", "valuesExposed");
    private static final Set<String> WORKSPACE_RELEASE_REMOVED_FIELDS = Set.of(
            "runtimeContainers", "runtimeNetworks", "sessionImages", "previewResources",
            "brokerResources", "browserProcesses");
    private static final Set<String> WORKSPACE_RELEASE_RELEASED_FIELDS = Set.of(
            "registration", "normalAdmission", "heavyAdmission", "allocation");
    private static final Set<String> WORKSPACE_RELEASE_RETAINED_FIELDS = Set.of(
            "workspaceRecord", "worktree", "git", "turns", "agentRuns", "attachments",
            "logs", "artifacts", "backups", "policyVolumes");
    private static final Set<AgentRunRecoveryNextAction> WORKER_ERROR_ACTIONS = Set.of(
            AgentRunRecoveryNextAction.NONE,
            AgentRunRecoveryNextAction.WAIT,
            AgentRunRecoveryNextAction.RETRY,
            AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
            AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR);

    private final RemoteWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectCodexAttachmentManifestService attachmentManifestService;
    private final HttpClient httpClient;

    public RemoteWorkerClient(
            RemoteWorkerProperties properties,
            ObjectMapper objectMapper,
            ProjectCodexAttachmentManifestService attachmentManifestService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.attachmentManifestService = attachmentManifestService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public Health health() {
        return exchange("GET", "/v1/health", null, Health.class);
    }

    public CodexCatalog codexCatalog() {
        return exchange("GET", "/v1/codex/catalog", null, CodexCatalog.class);
    }

    public CodexUpdateStage stageCodexUpdate(
            UUID planId, UUID candidateId, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "operation", "STAGE_CODEX_UPDATE",
                "planId", planId.toString(),
                "candidateId", candidateId.toString(),
                "idempotencyKey", idempotencyKey.toString());
        return exchange(
                "POST", "/v1/codex/update/stage", body,
                CodexUpdateStage.class, idempotencyKey.toString(),
                Duration.ofMinutes(5));
    }

    public CodexUpdateActivation activateCodexUpdate(
            UUID planId, UUID candidateId, UUID authorizationId, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "operation", "ACTIVATE_CODEX_UPDATE",
                "planId", planId.toString(),
                "candidateId", candidateId.toString(),
                "authorizationId", authorizationId.toString(),
                "idempotencyKey", idempotencyKey.toString());
        return exchange(
                "POST", "/v1/codex/update/activate", body,
                CodexUpdateActivation.class, idempotencyKey.toString(),
                Duration.ofMinutes(5));
    }

    public CodexUpdateRollback rollbackCodexUpdate(
            UUID planId, UUID candidateId, UUID activationId,
            UUID authorizationId, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "operation", "ROLLBACK_CODEX_UPDATE",
                "planId", planId.toString(),
                "candidateId", candidateId.toString(),
                "activationId", activationId.toString(),
                "authorizationId", authorizationId.toString(),
                "idempotencyKey", idempotencyKey.toString());
        return exchange(
                "POST", "/v1/codex/update/rollback", body,
                CodexUpdateRollback.class, idempotencyKey.toString(),
                Duration.ofMinutes(1));
    }

    public Execution dispatch(AgentRunEntity run, String message) {
        Map<String, Object> workload = workload(run, message);
        Map<String, Object> body = Map.of(
                "dispatchId", run.getDispatchId().toString(),
                "sessionId", run.getRemoteSessionId().toString(),
                "workspaceIdentity", run.getWorkspaceIdentity(),
                "workloadClass", run.getWorkloadClass().name(),
                "leaseGeneration", run.getLeaseGeneration(),
                "workload", workload);
        return exchange(
                "POST",
                "/v1/executions",
                body,
                Execution.class,
                run.getDispatchId().toString());
    }

    public Workspace ensureWorkspace(AgentRunEntity run) {
        if ((!ProjectCodexIdentity.matches(run) && !BeautipsProjectCodexIdentity.matches(run))
                || run.getSession().getWorkspaceBranch() == null) {
            throw new RemoteWorkerException(
                    "Persisted project workspace identity is incomplete or incompatible",
                    409);
        }
        Map<String, Object> body = Map.of(
                "sessionId", run.getRemoteSessionId().toString(),
                "workspaceIdentity", run.getWorkspaceIdentity(),
                "projectId", run.getProjectIdentity(),
                "repository", run.getRepositoryUrl(),
                "branch", run.getRepositoryBranch(),
                "commit", run.getRepositoryCommit(),
                "manifestSha256", run.getManifestSha256(),
                "workspaceBranch", run.getSession().getWorkspaceBranch());
        return exchange(
                "POST",
                "/v1/project-workspaces/ensure",
                body,
                Workspace.class,
                run.getDispatchId().toString(),
                properties.getWorkspaceProvisionTimeout());
    }

    public WorkspaceRelease releaseWorkspace(WorkSessionEntity session) {
        return releaseWorkspace(session, session);
    }

    public WorkspaceRelease releaseWorkspace(
            WorkSessionEntity session,
            WorkSessionEntity canonicalSourceWitness
    ) {
        Map<String, Object> body = workspaceReleaseRequest(
                session, canonicalSourceWitness);
        String operationId = session.getRemoteCloseOperationId().toString();
        JsonNode receipt = exchange(
                "POST",
                "/v1/project-workspaces/release",
                body,
                JsonNode.class,
                operationId,
                properties.getWorkspaceProvisionTimeout());
        return validateWorkspaceReleaseReceipt(session, objectMapper.valueToTree(body), receipt);
    }

    public WorkspaceReleasePreflight diagnoseWorkspaceReleasePreflight(
            WorkSessionEntity session,
            WorkSessionEntity canonicalSourceWitness
    ) {
        Map<String, Object> body = workspaceReleaseRequest(
                session, canonicalSourceWitness);
        String operationId = session.getRemoteCloseOperationId().toString();
        WorkspaceReleasePreflight response = exchange(
                "POST",
                "/v1/project-workspaces/release-preflight",
                body,
                WorkspaceReleasePreflight.class,
                operationId,
                properties.getWorkspaceProvisionTimeout());
        String requestFingerprint = canonicalSha256(objectMapper.valueToTree(body));
        if (!WORKSPACE_RELEASE_PREFLIGHT_SCHEMA.equals(response.schemaVersion())
                || !"PREFLIGHT_ACCEPTED".equals(response.state())
                || !operationId.equals(response.operationId())
                || !session.getRemoteSessionId().toString().equals(response.sessionId())
                || !session.getWorkspaceIdentity().equals(response.workspaceIdentity())
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(response.projectId())
                || !ProjectCodexIdentity.WORKER_ID.equals(response.workerId())
                || !requestFingerprint.equals(response.requestFingerprintSha256())
                || !isSha256(response.ownershipFingerprintSha256())
                || !isSha256(response.allocationFingerprintSha256())
                || response.valuesExposed()) {
            throw new RemoteWorkerException(
                    "Remote worker release preflight diagnosis was invalid",
                    502,
                    PROTOCOL_FAILURE_CODE,
                    RemoteWorkerFailureCategory.PROTOCOL,
                    false,
                    AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                    null);
        }
        return response;
    }

    private Map<String, Object> workspaceReleaseRequest(
            WorkSessionEntity session,
            WorkSessionEntity canonicalSourceWitness
    ) {
        String canonicalCommit = exactOwnerCommit(session, canonicalSourceWitness);
        if (canonicalCommit == null || !hasExactReleaseLifecycle(session)) {
            throw new RemoteWorkerException(
                    "Persisted remote workspace release identity is incomplete or incompatible",
                    409);
        }
        String operationId = session.getRemoteCloseOperationId().toString();
        return Map.ofEntries(
                Map.entry("operationId", operationId),
                Map.entry("idempotencyKey", operationId),
                Map.entry("sessionId", session.getRemoteSessionId().toString()),
                Map.entry("workspaceIdentity", session.getWorkspaceIdentity()),
                Map.entry("projectId", ProjectCodexIdentity.PROJECT_IDENTITY),
                Map.entry("repository", ProjectCodexIdentity.REPOSITORY),
                Map.entry("branch", ProjectCodexIdentity.BRANCH),
                Map.entry("commit", canonicalCommit),
                Map.entry("manifestSha256", ProjectCodexIdentity.MANIFEST_SHA256),
                Map.entry("workspaceBranch", session.getWorkspaceBranch()));
    }

    public WorkspaceCapacityOwner diagnoseWorkspaceCapacityOwner(
            WorkSessionEntity session
    ) {
        return diagnoseWorkspaceCapacityOwner(session, session);
    }

    public WorkspaceCapacityOwner diagnoseWorkspaceCapacityOwner(
            WorkSessionEntity session,
            WorkSessionEntity canonicalSourceWitness
    ) {
        String canonicalCommit = exactOwnerCommit(session, canonicalSourceWitness);
        if (canonicalCommit == null) {
            throw new RemoteWorkerException(
                    "Persisted capacity-owner identity is incomplete or incompatible",
                    409);
        }
        Map<String, Object> body = Map.ofEntries(
                Map.entry("sessionId", session.getRemoteSessionId().toString()),
                Map.entry("workspaceIdentity", session.getWorkspaceIdentity()),
                Map.entry("projectId", ProjectCodexIdentity.PROJECT_IDENTITY),
                Map.entry("repository", ProjectCodexIdentity.REPOSITORY),
                Map.entry("branch", ProjectCodexIdentity.BRANCH),
                Map.entry("commit", canonicalCommit),
                Map.entry("manifestSha256", ProjectCodexIdentity.MANIFEST_SHA256),
                Map.entry("workspaceBranch", session.getWorkspaceBranch()));
        WorkspaceCapacityOwner response = exchange(
                "POST",
                "/v1/project-workspaces/capacity-owner",
                body,
                WorkspaceCapacityOwner.class,
                null,
                properties.getWorkspaceProvisionTimeout());
        String requestFingerprint = canonicalSha256(objectMapper.valueToTree(body));
        if (!WORKSPACE_CAPACITY_OWNER_SCHEMA.equals(response.schemaVersion())
                || !"OWNED".equals(response.state())
                || !session.getRemoteSessionId().toString().equals(response.sessionId())
                || !session.getWorkspaceIdentity().equals(response.workspaceIdentity())
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(response.projectId())
                || !ProjectCodexIdentity.WORKER_ID.equals(response.workerId())
                || !requestFingerprint.equals(response.requestFingerprintSha256())
                || !isSha256(response.ownershipFingerprintSha256())
                || response.valuesExposed()) {
            throw new RemoteWorkerException(
                    "Remote worker capacity-owner diagnosis was invalid",
                    502,
                    PROTOCOL_FAILURE_CODE,
                    RemoteWorkerFailureCategory.PROTOCOL,
                    false,
                    AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                    null);
        }
        return response;
    }

    private String exactOwnerCommit(
            WorkSessionEntity owner,
            WorkSessionEntity canonicalSourceWitness
    ) {
        if (!isExactRemoteOwnerIdentity(owner)
                || !isExactRemoteOwnerIdentity(canonicalSourceWitness)
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(
                        canonicalSourceWitness)
                || owner.getProject() == null
                || canonicalSourceWitness.getProject() == null
                || owner.getProject().getId() == null
                || canonicalSourceWitness.getProject().getId() == null
                || !owner.getProject().getId().equals(
                        canonicalSourceWitness.getProject().getId())) {
            return null;
        }
        if (ProjectCodexIdentity.hasCanonicalSourceObservation(owner)) {
            return owner.getId() != null
                            && owner.getId().equals(canonicalSourceWitness.getId())
                    ? owner.getCanonicalSourceCommit() : null;
        }
        if (!hasNoCanonicalSourceObservation(owner)
                || owner.getId() == null
                || canonicalSourceWitness.getId() == null
                || owner.getId().equals(canonicalSourceWitness.getId())
                || owner.getRemoteSessionId().equals(
                        canonicalSourceWitness.getRemoteSessionId())
                || owner.getCreatedAt() == null
                || canonicalSourceWitness.getCreatedAt() == null
                || !owner.getCreatedAt().isBefore(canonicalSourceWitness.getCreatedAt())) {
            return null;
        }
        return canonicalSourceWitness.getCanonicalSourceCommit();
    }

    private boolean isExactRemoteOwnerIdentity(WorkSessionEntity session) {
        if (!ProjectCodexIdentity.matches(session)
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKER_ID.equals(session.getSelectedWorkerId())
                || !ProjectCodexIdentity.WORKER_ID.equals(properties.getWorkerId())
                || session.getRemoteSessionId() == null
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())) {
            return false;
        }
        String sessionId = session.getRemoteSessionId().toString();
        return ("remote:" + ProjectCodexIdentity.WORKER_ID + ":work-session:" + sessionId)
                        .equals(session.getWorkspaceIdentity())
                && ("atenea/session-" + sessionId).equals(session.getWorkspaceBranch());
    }

    private boolean hasExactReleaseLifecycle(WorkSessionEntity session) {
        return session.getRemoteCloseOperationId() != null
                && session.getRemoteCloseRevision() >= 1
                && session.getRemoteCloseRequestedAt() != null
                && session.getRemoteCloseUpdatedAt() != null
                && Set.of(
                        RemoteCloseState.REQUESTED,
                        RemoteCloseState.RECONCILING,
                        RemoteCloseState.BLOCKED).contains(session.getRemoteCloseState());
    }

    private static boolean hasNoCanonicalSourceObservation(WorkSessionEntity session) {
        return session.getCanonicalSourceRef() == null
                && session.getCanonicalSourceCommit() == null
                && session.getCanonicalSourceObservationSha256() == null
                && session.getCanonicalSourceObservedAt() == null;
    }

    private WorkspaceRelease validateWorkspaceReleaseReceipt(
            WorkSessionEntity session,
            JsonNode request,
            JsonNode receipt
    ) {
        if (!hasExactFields(receipt, WORKSPACE_RELEASE_RECEIPT_FIELDS)
                || !WORKSPACE_RELEASE_SCHEMA.equals(textValue(receipt, "schemaVersion"))
                || !"RELEASED".equals(textValue(receipt, "state"))
                || !ProjectCodexIdentity.WORKER_ID.equals(textValue(receipt, "workerId"))
                || !request.get("operationId").asText().equals(textValue(receipt, "operationId"))
                || !request.get("idempotencyKey").asText()
                        .equals(textValue(receipt, "idempotencyKey"))
                || !requestOwnershipMatchesReceipt(request, receipt)
                || !canonicalSha256(request).equals(
                        textValue(receipt, "requestFingerprintSha256"))
                || !isFinalReleaseRevision(receipt.get("revision"))
                || !receipt.get("valuesExposed").isBoolean()
                || receipt.get("valuesExposed").booleanValue()
                || !validRemovedProjection(receipt.get("removed"))
                || !validReleasedProjection(receipt.get("released"))
                || !validRetainedProjection(receipt.get("retained"))
                || !isSha256(textValue(receipt, "ownershipFingerprintSha256"))
                || !isSha256(textValue(receipt, "receiptSha256"))) {
            throw invalidWorkspaceReleaseReceipt();
        }
        ObjectNode sealed = ((ObjectNode) receipt).deepCopy();
        sealed.remove("receiptSha256");
        if (!canonicalSha256(sealed).equals(receipt.get("receiptSha256").asText())) {
            throw invalidWorkspaceReleaseReceipt();
        }
        try {
            WorkspaceRelease result = objectMapper.treeToValue(receipt, WorkspaceRelease.class);
            if (!session.getRemoteCloseOperationId().toString().equals(result.operationId())) {
                throw invalidWorkspaceReleaseReceipt();
            }
            return result;
        } catch (IOException exception) {
            throw invalidWorkspaceReleaseReceipt();
        }
    }

    private boolean requestOwnershipMatchesReceipt(JsonNode request, JsonNode receipt) {
        for (String field : List.of(
                "sessionId", "workspaceIdentity", "projectId", "repository", "branch",
                "commit", "manifestSha256", "workspaceBranch")) {
            if (!request.get(field).equals(receipt.get(field))) {
                return false;
            }
        }
        return true;
    }

    private boolean validRemovedProjection(JsonNode value) {
        if (!hasExactFields(value, WORKSPACE_RELEASE_REMOVED_FIELDS)) {
            return false;
        }
        for (String field : WORKSPACE_RELEASE_REMOVED_FIELDS) {
            JsonNode count = value.get(field);
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean validReleasedProjection(JsonNode value) {
        if (!hasExactFields(value, WORKSPACE_RELEASE_RELEASED_FIELDS)) {
            return false;
        }
        return WORKSPACE_RELEASE_RELEASED_FIELDS.stream()
                .allMatch(field -> value.get(field).isBoolean()
                        && value.get(field).booleanValue());
    }

    private boolean validRetainedProjection(JsonNode value) {
        if (!hasExactFields(value, WORKSPACE_RELEASE_RETAINED_FIELDS)) {
            return false;
        }
        return WORKSPACE_RELEASE_RETAINED_FIELDS.stream()
                .allMatch(field -> value.get(field).isBoolean() && value.get(field).booleanValue());
    }

    private boolean hasExactFields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private String textValue(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        return child != null && child.isTextual() ? child.textValue() : null;
    }

    private boolean isFinalReleaseRevision(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() == WORKSPACE_RELEASE_REVISION;
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private String canonicalSha256(JsonNode value) {
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(canonicalize(value));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot seal workspace release projection", exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Set<String> fields = new TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                result.set(field, canonicalize(value.get(field)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        return value;
    }

    private RemoteWorkerException invalidWorkspaceReleaseReceipt() {
        return new RemoteWorkerException(
                "Remote worker returned an invalid workspace release receipt",
                502,
                PROTOCOL_FAILURE_CODE,
                RemoteWorkerFailureCategory.PROTOCOL,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    public DraftFingerprint fingerprintRetainedDraft(WorkSessionEntity session) {
        if (!ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || session.getRemoteSessionId() == null
                || session.getWorkspaceIdentity() == null) {
            throw new RemoteWorkerException(
                    "Persisted draft ownership or canonical source observation is incomplete",
                    409);
        }
        Map<String, Object> body = Map.of(
                "sessionId", session.getRemoteSessionId().toString(),
                "workspaceIdentity", session.getWorkspaceIdentity(),
                "projectId", ProjectCodexIdentity.PROJECT_IDENTITY,
                "repository", ProjectCodexIdentity.REPOSITORY,
                "branch", ProjectCodexIdentity.BRANCH,
                "acceptedCommit", session.getCanonicalSourceCommit(),
                "manifestSha256", ProjectCodexIdentity.MANIFEST_SHA256);
        return exchange(
                "POST",
                "/v1/project-workspaces/draft-fingerprint",
                body,
                DraftFingerprint.class,
                session.getRemoteSessionId().toString(),
                properties.getWorkspaceProvisionTimeout());
    }

    public SourceTreeFingerprint fingerprintSourceTree(WorkSessionEntity session) {
        if (!ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || session.getRemoteSessionId() == null
                || session.getWorkspaceIdentity() == null) {
            throw new RemoteWorkerException(
                    "Persisted source tree ownership or canonical observation is incomplete",
                    409);
        }
        Map<String, Object> body = Map.of(
                "sessionId", session.getRemoteSessionId().toString(),
                "workspaceIdentity", session.getWorkspaceIdentity(),
                "projectId", ProjectCodexIdentity.PROJECT_IDENTITY,
                "repository", ProjectCodexIdentity.REPOSITORY,
                "branch", ProjectCodexIdentity.BRANCH,
                "commit", session.getCanonicalSourceCommit(),
                "manifestSha256", ProjectCodexIdentity.MANIFEST_SHA256);
        return exchange(
                "POST",
                "/v1/project-workspaces/source-tree-fingerprint",
                body,
                SourceTreeFingerprint.class,
                session.getRemoteSessionId().toString(),
                properties.getWorkspaceProvisionTimeout());
    }

    public ValidationResult runValidation(
            WorkSessionEntity session,
            ValidationOperationKind operation,
            String sourceTreeFingerprintSha256,
            String validationId
    ) {
        if (!ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || session.getRemoteSessionId() == null
                || sourceTreeFingerprintSha256 == null
                || !sourceTreeFingerprintSha256.matches("^[0-9a-f]{64}$")) {
            throw new RemoteWorkerException(
                    "Persisted validation ownership or source tree fingerprint is incomplete",
                    409);
        }
        Map<String, Object> body = Map.ofEntries(
                Map.entry("validationId", validationId),
                Map.entry("sessionId", session.getRemoteSessionId().toString()),
                Map.entry("workspaceIdentity", session.getWorkspaceIdentity()),
                Map.entry("projectId", ProjectCodexIdentity.PROJECT_IDENTITY),
                Map.entry("repository", ProjectCodexIdentity.REPOSITORY),
                Map.entry("branch", ProjectCodexIdentity.BRANCH),
                Map.entry("commit", session.getCanonicalSourceCommit()),
                Map.entry("manifestSha256", ProjectCodexIdentity.MANIFEST_SHA256),
                Map.entry("operation", operation.name()),
                Map.entry("definitionRevision", operation.definitionRevision()),
                Map.entry("sourceTreeFingerprintSha256", sourceTreeFingerprintSha256));
        return exchange(
                "POST",
                "/v1/project-workspaces/validations",
                body,
                ValidationResult.class,
                validationId,
                validationTimeout(operation));
    }

    public RepositoryRoleSet ensureRepositoryRoles(
            WorkSessionEntity session,
            String changeIdentity
    ) {
        if (!ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || session.getRemoteSessionId() == null
                || session.getWorkspaceIdentity() == null) {
            throw new RemoteWorkerException(
                    "Persisted multi-repository ownership is incomplete", 409);
        }
        Map<String, Object> body = Map.of(
                "sessionId", session.getRemoteSessionId().toString(),
                "workspaceIdentity", session.getWorkspaceIdentity(),
                "changeIdentity", changeIdentity,
                "codeCommit", session.getCanonicalSourceCommit());
        return exchange(
                "POST",
                "/v1/project-workspaces/repository-roles/ensure",
                body,
                RepositoryRoleSet.class,
                changeIdentity,
                properties.getWorkspaceProvisionTimeout());
    }

    private Duration validationTimeout(ValidationOperationKind operation) {
        return switch (operation) {
            case BACKEND_TEST -> Duration.ofMinutes(15);
            case WEB_BUILD -> Duration.ofMinutes(10);
            case ANDROID_BUILD -> Duration.ofMinutes(20);
            case PLAYWRIGHT_ACCEPTANCE -> Duration.ofMinutes(10);
        };
    }

    private Map<String, Object> workload(AgentRunEntity run, String message) {
        if ("synthetic-routing-v1".equals(run.getWorkloadKind())) {
            return Map.of(
                    "kind", "synthetic-routing-v1",
                    "message", message,
                    "durationMs", properties.getSyntheticDuration().toMillis(),
                    "steps", 10);
        }
        if (!ProjectCodexIdentity.matches(run)
                && !BeautipsProjectCodexIdentity.matches(run)) {
            throw new RemoteWorkerException(
                    "Persisted project workload identity is incomplete or incompatible",
                    409);
        }
        Map<String, Object> workload = new LinkedHashMap<>();
        boolean hasProfile = run.getCodexModelId() != null
                || run.getCodexReasoningEffort() != null
                || run.getCodexCatalogRevision() != null
                || run.getCodexVersion() != null;
        if (hasProfile && (run.getCodexModelId() == null
                || run.getCodexReasoningEffort() == null
                || run.getCodexCatalogRevision() == null
                || run.getCodexVersion() == null)) {
            throw new RemoteWorkerException("Persisted Codex profile is incomplete", 409);
        }
        boolean imageWorkload = ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(
                run.getWorkloadKind());
        if (imageWorkload && !hasProfile) {
            throw new RemoteWorkerException(
                    "Persisted image workload has no complete Codex profile", 409);
        }
        workload.put("kind", imageWorkload
                ? ProjectCodexIdentity.IMAGE_WORKLOAD_KIND
                : hasProfile ? "project-codex-v2" : ProjectCodexIdentity.WORKLOAD_KIND);
        workload.put("projectId", run.getProjectIdentity());
        workload.put("repository", run.getRepositoryUrl());
        workload.put("branch", run.getRepositoryBranch());
        workload.put("commit", run.getRepositoryCommit());
        workload.put("manifestSha256", run.getManifestSha256());
        workload.put("instructionBundleRevision", run.getInstructionBundleRevision());
        workload.put("instructionBundleSha256", run.getInstructionBundleSha256());
        workload.put("platformInstructionSha256", run.getPlatformInstructionSha256());
        workload.put("projectInstructionPath", run.getProjectInstructionPath());
        workload.put("projectInstructionSha256", run.getProjectInstructionSha256());
        workload.put("message", message);
        workload.put("threadId", run.getSession().getExternalThreadId());
        if (hasProfile) {
            workload.put("modelId", run.getCodexModelId());
            workload.put("reasoningEffort", run.getCodexReasoningEffort().canonicalValue());
            workload.put("catalogRevision", run.getCodexCatalogRevision());
            workload.put("codexVersion", run.getCodexVersion());
        }
        if (imageWorkload) {
            workload.put("attachments", attachmentManifestService.exactReferences(run).stream()
                    .map(reference -> {
                        Map<String, Object> serialized = new LinkedHashMap<>();
                        serialized.put("attachmentId", reference.attachmentId().toString());
                        serialized.put("contentType", reference.contentType());
                        serialized.put("sizeBytes", reference.sizeBytes());
                        serialized.put("sha256", reference.sha256());
                        return serialized;
                    })
                    .toList());
        }
        return workload;
    }

    public Execution get(AgentRunEntity run) {
        return exchange("GET", "/v1/executions/" + run.getDispatchId(), null, Execution.class);
    }

    public Execution renew(AgentRunEntity run) {
        Map<String, Object> body = Map.of(
                "executionId", run.getRemoteExecutionId(),
                "leaseGeneration", run.getLeaseGeneration());
        return exchange("POST", "/v1/executions/" + run.getDispatchId() + "/lease", body, Execution.class);
    }

    public Execution cancel(AgentRunEntity run) {
        Map<String, Object> body = Map.of("executionId", run.getRemoteExecutionId());
        return exchange("POST", "/v1/executions/" + run.getDispatchId() + "/cancel", body, Execution.class);
    }

    public Execution cancelExact(AgentRunEntity run) {
        return exchange(
                "POST",
                "/v1/executions/" + run.getDispatchId() + "/cancel-exact",
                exactExecutionOperation(run),
                Execution.class,
                run.getDispatchId() + ":cancel");
    }

    public Execution inspectReconciliation(AgentRunEntity run) {
        return exchange(
                "POST",
                "/v1/executions/" + run.getDispatchId() + "/reconcile",
                exactExecutionOperation(run),
                Execution.class,
                run.getDispatchId() + ":reconcile");
    }

    public ExecutionDoctor doctor(AgentRunEntity run) {
        return exchange(
                "POST",
                "/v1/executions/" + run.getDispatchId() + "/doctor",
                exactExecutionOperation(run),
                ExecutionDoctor.class,
                run.getDispatchId() + ":doctor");
    }

    private Map<String, Object> exactExecutionOperation(AgentRunEntity run) {
        if (run.getRemoteExecutionId() == null
                || run.getRemoteSessionId() == null
                || run.getWorkspaceIdentity() == null) {
            throw new RemoteWorkerException(
                    "Persisted exact execution ownership is incomplete", 409);
        }
        return Map.of(
                "executionId", run.getRemoteExecutionId(),
                "sessionId", run.getRemoteSessionId().toString(),
                "workspaceIdentity", run.getWorkspaceIdentity(),
                "leaseGeneration", run.getLeaseGeneration());
    }

    private <T> T exchange(String method, String path, Object body, Class<T> responseType) {
        return exchange(method, path, body, responseType, null);
    }

    private <T> T exchange(
            String method,
            String path,
            Object body,
            Class<T> responseType,
            String idempotencyKey
    ) {
        return exchange(method, path, body, responseType, idempotencyKey, properties.getRequestTimeout());
    }

    private <T> T exchange(
            String method,
            String path,
            Object body,
            Class<T> responseType,
            String idempotencyKey,
            Duration timeout
    ) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + readToken())
                    .header("Accept", "application/json");
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw decodeWorkerRejection(response, responseBody);
                }
                return objectMapper.readValue(responseBody, responseType);
            }
        } catch (RemoteWorkerException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RemoteWorkerException("Remote worker I/O failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteWorkerException("Remote worker request was interrupted", exception);
        } catch (RuntimeException exception) {
            throw new RemoteWorkerException("Remote worker request failed", exception);
        }
    }

    private RemoteWorkerException decodeWorkerRejection(
            HttpResponse<InputStream> response,
            InputStream responseBody
    ) throws IOException {
        int statusCode = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.equalsIgnoreCase("application/json")) {
            return protocolFailure(statusCode);
        }
        byte[] encoded = responseBody.readNBytes(MAX_WORKER_ERROR_BYTES + 1);
        if (encoded.length > MAX_WORKER_ERROR_BYTES) {
            Arrays.fill(encoded, (byte) 0);
            return protocolFailure(statusCode);
        }
        try {
            WorkerErrorEnvelope envelope = objectMapper.readValue(encoded, WorkerErrorEnvelope.class);
            if (!WORKER_ERROR_SCHEMA.equals(envelope.schemaVersion())
                    || envelope.code() == null
                    || !envelope.code().matches("^[A-Z][A-Z0-9_]{2,79}$")
                    || envelope.category() == null
                    || envelope.retryable() == null
                    || envelope.nextAction() == null) {
                return protocolFailure(statusCode);
            }
            RemoteWorkerFailureCategory category = RemoteWorkerFailureCategory.valueOf(
                    envelope.category());
            AgentRunRecoveryNextAction nextAction = AgentRunRecoveryNextAction.valueOf(
                    envelope.nextAction());
            if (!WORKER_ERROR_ACTIONS.contains(nextAction)) {
                return protocolFailure(statusCode);
            }
            UUID blockerSessionId = canonicalBlocker(envelope.blockerSessionId(), category, nextAction);
            if (envelope.blockerSessionId() != null && blockerSessionId == null) {
                return protocolFailure(statusCode);
            }
            if (!validFailureSemantics(
                    statusCode, category, envelope.retryable(), nextAction, blockerSessionId)) {
                return protocolFailure(statusCode);
            }
            return new RemoteWorkerException(
                    "Remote worker rejected request with HTTP " + statusCode
                            + " (" + envelope.code() + ")",
                    statusCode,
                    envelope.code(),
                    category,
                    envelope.retryable(),
                    nextAction,
                    blockerSessionId);
        } catch (IOException | IllegalArgumentException exception) {
            return protocolFailure(statusCode);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private boolean validFailureSemantics(
            int statusCode,
            RemoteWorkerFailureCategory category,
            boolean retryable,
            AgentRunRecoveryNextAction nextAction,
            UUID blockerSessionId
    ) {
        if (statusCode >= 500 && statusCode < 600) {
            return category == RemoteWorkerFailureCategory.TRANSPORT
                    && retryable
                    && nextAction == AgentRunRecoveryNextAction.REQUEST_RECONCILIATION
                    && blockerSessionId == null;
        }
        if (statusCode < 400 || statusCode >= 600
                || category == RemoteWorkerFailureCategory.TRANSPORT) {
            return false;
        }
        if (category == RemoteWorkerFailureCategory.CAPACITY) {
            return retryable && nextAction == AgentRunRecoveryNextAction.WAIT;
        }
        return !retryable
                && blockerSessionId == null
                && (nextAction == AgentRunRecoveryNextAction.NONE
                    || nextAction == AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR);
    }

    private UUID canonicalBlocker(
            String value,
            RemoteWorkerFailureCategory category,
            AgentRunRecoveryNextAction nextAction
    ) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)
                    || category != RemoteWorkerFailureCategory.CAPACITY
                    || nextAction != AgentRunRecoveryNextAction.WAIT) {
                return null;
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private RemoteWorkerException protocolFailure(int statusCode) {
        return new RemoteWorkerException(
                "Remote worker returned an invalid error response",
                statusCode,
                PROTOCOL_FAILURE_CODE,
                RemoteWorkerFailureCategory.PROTOCOL,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    private String readToken() throws IOException {
        if (properties.getTokenFile() == null || properties.getTokenFile().isBlank()) {
            throw new IOException("remote worker token file is not configured");
        }
        String token = Files.readString(Path.of(properties.getTokenFile())).trim();
        if (token.length() < 32) {
            throw new IOException("remote worker token file is invalid");
        }
        return token;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record WorkerErrorEnvelope(
            String schemaVersion,
            String code,
            String category,
            Boolean retryable,
            String nextAction,
            String blockerSessionId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Health(
            String protocolVersion,
            String workerId,
            boolean healthy,
            List<String> capabilities,
            int normalCapacity,
            int heavyCapacity,
            int normalInUse,
            int heavyInUse,
            int queued,
            Instant serverTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CodexCatalog(
            String schemaVersion,
            String catalogRevision,
            String workerId,
            String codexVersion,
            Instant generatedAt,
            List<CodexModel> models
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CodexModel(
            String modelId,
            String displayName,
            List<String> supportedEfforts,
            String defaultEffort,
            String availability
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Execution(
            String dispatchId,
            String executionId,
            String sessionId,
            String workspaceIdentity,
            String workloadClass,
            long leaseGeneration,
            String status,
            String statusReason,
            long revision,
            int progress,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            Result result,
            List<ProgressEvent> progressEvents
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProgressEvent(
            String dispatchId,
            String executionId,
            long sequence,
            String category,
            Instant occurredAt,
            String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExecutionDoctor(
            String schemaVersion,
            String workerId,
            String dispatchId,
            String executionId,
            String sessionId,
            String workspaceIdentity,
            long leaseGeneration,
            String status,
            long revision,
            String observation,
            boolean cancelRequested,
            boolean reconcileRequired,
            Long latestProgressSequence,
            int retainedProgressCount,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Workspace(
            String state,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String workspaceBranch,
            String slot,
            String canonicalCommit,
            boolean selectionEnabled,
            boolean executionEnabled,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WorkspaceRelease(
            String schemaVersion,
            String state,
            String operationId,
            String idempotencyKey,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String repository,
            String branch,
            String commit,
            String manifestSha256,
            String workspaceBranch,
            String workerId,
            String requestFingerprintSha256,
            long revision,
            Map<String, Integer> removed,
            Map<String, Boolean> released,
            Map<String, Boolean> retained,
            String ownershipFingerprintSha256,
            String receiptSha256,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WorkspaceCapacityOwner(
            String schemaVersion,
            String state,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String workerId,
            String requestFingerprintSha256,
            String ownershipFingerprintSha256,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WorkspaceReleasePreflight(
            String schemaVersion,
            String state,
            String operationId,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String workerId,
            String requestFingerprintSha256,
            String ownershipFingerprintSha256,
            String allocationFingerprintSha256,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftFingerprint(
            String state,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String retainedHead,
            String acceptedCommit,
            String fingerprintSha256,
            int stagedChangeCount,
            int unstagedChangeCount,
            int untrackedChangeCount,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceTreeFingerprint(
            String state,
            String sessionId,
            String workspaceIdentity,
            String projectId,
            String headCommit,
            String fingerprintSha256,
            int stagedChangeCount,
            int unstagedChangeCount,
            int untrackedChangeCount,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ValidationResult(
            String validationId,
            String sessionId,
            String workspaceIdentity,
            String operation,
            String definitionRevision,
            String sourceTreeFingerprintSha256,
            String status,
            Integer exitCode,
            long durationMillis,
            String artifactManifestSha256,
            String summary,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RepositoryRoleSet(
            String sessionId,
            String workspaceIdentity,
            String changeIdentity,
            List<RepositoryRole> roles,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RepositoryRole(
            String role,
            String authority,
            String repository,
            String branch,
            String commit,
            String mirrorIdentitySha256,
            String worktreeIdentitySha256,
            String validationProfile,
            String readiness
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CodexUpdateStage(
            String schemaVersion,
            String operation,
            String workerId,
            UUID planId,
            UUID candidateId,
            UUID idempotencyKey,
            String state,
            String codexVersion,
            String releaseDigestSha256,
            String catalogRevision,
            String releaseManifestSha256,
            String schemaManifestSha256,
            String releaseVerification,
            String schemaGeneration,
            String retention,
            String currentLinkFingerprint,
            String previousLinkFingerprint,
            boolean linksChanged,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CodexUpdateActivation(
            String schemaVersion,
            String operation,
            String workerId,
            UUID planId,
            UUID candidateId,
            UUID authorizationId,
            UUID idempotencyKey,
            String state,
            String codexVersion,
            String releaseDigestSha256,
            String catalogRevision,
            String schemaComparison,
            String focusedContracts,
            String workerHealth,
            String canary,
            String currentBeforeFingerprint,
            String previousBeforeFingerprint,
            String currentAfterFingerprint,
            String previousAfterFingerprint,
            String automaticRestore,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CodexUpdateRollback(
            String schemaVersion,
            String operation,
            String workerId,
            UUID planId,
            UUID candidateId,
            UUID activationId,
            UUID authorizationId,
            UUID idempotencyKey,
            String state,
            String linkRestore,
            String workerServiceRestart,
            java.util.List<String> affectedServices,
            int appServerServicesRestarted,
            String currentBeforeFingerprint,
            String previousBeforeFingerprint,
            String currentAfterFingerprint,
            String previousAfterFingerprint,
            boolean valuesExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Result(String threadId, String turnId, String finalAnswer, String outputSummary) {
    }
}
