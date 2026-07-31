package com.atenea.codexoperations;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.ExecutionProfileSource;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CodexExecutionProfileSnapshotService {

    private final CodexSessionOperationsProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public CodexExecutionProfileSnapshotService(
            CodexSessionOperationsProperties properties,
            JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void applyCurrentProfile(AgentRunEntity run) {
        if (!properties.isProfilesEnabled()) {
            return;
        }
        String workerId = run.getSelectedWorkerId();
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("Profile-enabled remote AgentRun requires a selected worker");
        }
        CatalogHeader catalog = jdbcTemplate.query("""
                SELECT catalog_revision, codex_version
                  FROM worker_codex_catalog
                 WHERE worker_id = ?
                 ORDER BY generated_at DESC, catalog_revision DESC
                 LIMIT 1
                """, (rs, row) -> new CatalogHeader(
                rs.getString("catalog_revision"), rs.getString("codex_version")), workerId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected worker Codex catalog is unavailable"));

        List<Model> models = jdbcTemplate.query("""
                SELECT model_id, default_effort
                  FROM worker_codex_model
                 WHERE worker_id = ? AND catalog_revision = ? AND availability = 'AVAILABLE'
                 ORDER BY position
                """, (rs, row) -> new Model(rs.getString("model_id"), rs.getString("default_effort")),
                workerId, catalog.revision());
        if (models.isEmpty()) {
            throw new IllegalStateException("Selected worker has no available Codex model");
        }

        WorkSessionEntity session = run.getSession();
        String modelId = firstNonBlank(
                session.getDefaultCodexModelId(),
                session.getProject().getDefaultCodexModelId(),
                models.getFirst().id());
        ExecutionProfileSource modelSource = session.getDefaultCodexModelId() != null
                ? ExecutionProfileSource.WORK_SESSION
                : session.getProject().getDefaultCodexModelId() != null
                        ? ExecutionProfileSource.PROJECT
                        : ExecutionProfileSource.WORKER_DEFAULT;
        Model model = models.stream().filter(candidate -> candidate.id().equals(modelId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Configured Codex model is unavailable in the current catalog"));

        CodexReasoningEffort effort = firstNonNull(
                session.getDefaultCodexReasoningEffort(),
                session.getProject().getDefaultCodexReasoningEffort(),
                CodexReasoningEffort.fromCanonicalValue(model.defaultEffort()));
        ExecutionProfileSource effortSource = session.getDefaultCodexReasoningEffort() != null
                ? ExecutionProfileSource.WORK_SESSION
                : session.getProject().getDefaultCodexReasoningEffort() != null
                        ? ExecutionProfileSource.PROJECT
                        : ExecutionProfileSource.WORKER_DEFAULT;
        Integer accepted = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_model_effort
                 WHERE worker_id = ? AND catalog_revision = ? AND model_id = ? AND effort = ?
                """, Integer.class, workerId, catalog.revision(), modelId, effort.canonicalValue());
        if (accepted == null || accepted != 1) {
            throw new IllegalStateException("Configured Codex effort is incompatible with the effective model");
        }

        run.setCodexModelId(modelId);
        run.setCodexModelSource(modelSource);
        run.setCodexReasoningEffort(effort);
        run.setCodexEffortSource(effortSource);
        run.setCodexCatalogRevision(catalog.revision());
        run.setCodexVersion(catalog.codexVersion());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }

    private record CatalogHeader(String revision, String codexVersion) {}
    private record Model(String id, String defaultEffort) {}
}
