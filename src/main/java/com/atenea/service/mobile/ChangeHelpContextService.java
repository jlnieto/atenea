package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryStore;
import com.atenea.delivery.ReleaseControlClient;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ValidationOperationRepository;
import com.atenea.persistence.worksession.ValidationOperationKind;
import com.atenea.persistence.worksession.ValidationOperationStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeHelpContextService {
    private final WorkSessionRepository sessions;
    private final AgentRunRepository runs;
    private final ValidationOperationRepository validations;
    private final OperatorRepository operators;
    private final DeliveryStore deliveries;
    private final ReleaseControlClient releaseControl;

    public ChangeHelpContextService(WorkSessionRepository sessions, AgentRunRepository runs,
            ValidationOperationRepository validations, OperatorRepository operators, DeliveryStore deliveries,
            ReleaseControlClient releaseControl) {
        this.sessions = sessions; this.runs = runs; this.validations = validations;
        this.operators = operators; this.deliveries = deliveries;
        this.releaseControl = releaseControl;
    }

    @Transactional(readOnly = true)
    public ChangeHelpContext read(Long id, AuthenticatedOperator actor) {
        var operator = actor == null || actor.operatorId() == null ? null
                : operators.findById(actor.operatorId()).orElse(null);
        if (operator == null || !operator.isActive()) {
            throw new WorkSessionOperationBlockedException("Authenticated active operator required");
        }
        var session = sessions.findWithProjectAndDevelopmentChangeById(id)
                .orElseThrow(() -> new WorkSessionNotFoundException(id));
        var change = session.getDevelopmentChange();
        if (change == null || change.getProject() == null || session.getProject() == null
                || change.getChangeKey() == null
                || change.getWorkspaceIdentity() == null || change.getWorkspaceBranch() == null
                || change.getSelectedWorkerId() == null
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || !Objects.equals(change.getWorkspaceIdentity(), session.getWorkspaceIdentity())
                || !Objects.equals(change.getWorkspaceBranch(), session.getWorkspaceBranch())
                || !Objects.equals(change.getSelectedWorkerId(), session.getSelectedWorkerId())) {
            throw new WorkSessionOperationBlockedException("Help requires the exact bound DevelopmentChange");
        }
        var run = runs.findFirstBySessionIdOrderByCreatedAtDesc(id).orElse(null);
        var latest = new EnumMap<ValidationOperationKind, com.atenea.persistence.worksession.ValidationOperationEntity>(ValidationOperationKind.class);
        if (change.getSourceFingerprintSha256() != null) {
            for (var item : validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                    id, change.getSourceFingerprintSha256())) {
                if (item.getOperation() != null && item.getOperation().definitionRevision().equals(item.getDefinitionRevision())
                        && Objects.equals(change.getSourceFingerprintSha256(), item.getSourceTreeFingerprintSha256())) {
                    latest.put(item.getOperation(), item);
                }
            }
        }
        var failed = latest.values().stream().filter(v -> v.getStatus() == ValidationOperationStatus.FAILED
                || v.getStatus() == ValidationOperationStatus.BLOCKED).findFirst().orElse(null);
        int passed = (int) latest.values().stream().filter(v -> v.getStatus() == ValidationOperationStatus.SUCCEEDED).count();
        var delivery = deliveries.list(id).stream().filter(d -> Objects.equals(d.operatorId(), actor.operatorId())
                && Objects.equals(d.sessionId(), id) && session.getFinalCommitSha() != null
                && Objects.equals(d.sourceCommit(), session.getFinalCommitSha()))
                .findFirst().orElse(null);
        String response = run == null || run.getResultTurn() == null ? "" : run.getResultTurn().getMessageText();
        boolean gitInstructions = response != null && response.contains("github/main")
                && response.toLowerCase(Locale.ROOT).contains("origin");
        return new ChangeHelpContext(id, session.getProject().getId(), change.getChangeKey(), change.getSourceRevision(),
                Instant.now(), name(change.getStatus()), name(session.getStatus()), name(change.getWorkspaceState()),
                name(change.getSourceState()), name(change.getValidationState()), name(session.getPullRequestStatus()),
                name(change.getIntegrationState()), name(change.getReleaseState()),
                runs.existsBySessionIdAndStatusIn(id, AgentRunStatus.nonTerminalStatuses()),
                validations.existsByWorkSessionIdAndStatus(id, ValidationOperationStatus.RUNNING),
                sessions.existsActiveSourceUpdateBySessionId(id) || sessions.existsActiveSourceFinalizationBySessionId(id),
                name(session.getRemoteCloseState()), run == null ? null : run.getId(),
                run == null ? null : name(run.getStatus()), run == null ? null : safeCode(run.getFailureCode()),
                gitInstructions, passed, Long.valueOf(1).equals(session.getProject().getId()) ? ValidationOperationKind.values().length : 0,
                failed == null ? null : name(failed.getOperation()), failed == null ? null : safeText(failed.getSummary()),
                delivery == null ? null : delivery.kind(), delivery == null ? null : delivery.state(),
                delivery == null ? null : safeCode(delivery.errorCode()),
                name(operator.getCodexOperationsRole()), releaseControl.enabled(),
                run != null && run.getStatus() == AgentRunStatus.FAILED ? safeText(run.getErrorSummary()) : null);
    }

    private static String name(Enum<?> value) { return value == null ? "UNKNOWN" : value.name(); }
    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : null;
    }
    static String safeText(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.replaceAll("(?is)```.*?```", "[detalle omitido]")
                .replaceAll("(?i)Bearer\\s+\\S+|gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+", "[secreto omitido]")
                .replaceAll("(?i)\\b(?:password|passwd|token|secret|api[_-]?key|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+", "[secreto omitido]")
                .replaceAll("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{15,}", "[secreto omitido]")
                .replaceAll("https?://\\S+|(?<!\\w)/(?:[A-Za-z0-9_.-]+/)+[^\\s]*", "[referencia omitida]")
                .replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(value.length(), 400));
    }
}
