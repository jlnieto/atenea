package com.atenea.codexoperations;

import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerClient.CodexCatalog;
import com.atenea.remoteworker.RemoteWorkerClient.CodexModel;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerCodexCatalogSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerCodexCatalogSyncService.class);
    private static final String SCHEMA_VERSION = "codex-model-catalog-v1";
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,79}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> EFFORTS = Set.of("none", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "DEPRECATED", "BLOCKED");

    private final CodexSessionOperationsProperties operationsProperties;
    private final RemoteWorkerProperties workerProperties;
    private final RemoteWorkerClient workerClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkerCodexCatalogSyncService(
            CodexSessionOperationsProperties operationsProperties,
            RemoteWorkerProperties workerProperties,
            RemoteWorkerClient workerClient,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.operationsProperties = operationsProperties;
        this.workerProperties = workerProperties;
        this.workerClient = workerClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${ATENEA_CODEX_CATALOG_SYNC_INITIAL_DELAY_MS:1000}",
            fixedDelayString = "${ATENEA_CODEX_CATALOG_SYNC_DELAY_MS:30000}")
    @Transactional
    public void synchronizeIfEnabled() {
        if (!operationsProperties.isProfilesEnabled() || !workerProperties.isEnabled()) {
            return;
        }
        try {
            synchronize(workerClient.codexCatalog());
        } catch (RuntimeException exception) {
            LOGGER.warn("Codex catalog synchronization failed closed: {}", exception.getMessage());
        }
    }

    @Transactional
    public void synchronize(CodexCatalog catalog) {
        validate(catalog);
        Instant observedAt = Instant.now();
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_catalog
                 WHERE worker_id = ? AND catalog_revision = ?
                """, Integer.class, catalog.workerId(), catalog.catalogRevision());
        if (existing != null && existing == 1) {
            verifyPersisted(catalog);
            jdbcTemplate.update("""
                    UPDATE worker_codex_catalog SET observed_at = ?
                     WHERE worker_id = ? AND catalog_revision = ?
                    """, Timestamp.from(observedAt), catalog.workerId(), catalog.catalogRevision());
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO worker_codex_catalog (
                    worker_id, catalog_revision, schema_version, codex_version,
                    generated_at, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, catalog.workerId(), catalog.catalogRevision(), catalog.schemaVersion(),
                catalog.codexVersion(), Timestamp.from(catalog.generatedAt()), Timestamp.from(observedAt));
        List<CodexModel> models = sortedModels(catalog);
        for (int modelPosition = 0; modelPosition < models.size(); modelPosition++) {
            CodexModel model = models.get(modelPosition);
            jdbcTemplate.update("""
                    INSERT INTO worker_codex_model (
                        worker_id, catalog_revision, model_id, display_name,
                        default_effort, availability, position
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, catalog.workerId(), catalog.catalogRevision(), model.modelId(),
                    model.displayName(), model.defaultEffort(), model.availability(), modelPosition);
            for (int effortPosition = 0; effortPosition < model.supportedEfforts().size(); effortPosition++) {
                jdbcTemplate.update("""
                        INSERT INTO worker_codex_model_effort (
                            worker_id, catalog_revision, model_id, effort, position
                        ) VALUES (?, ?, ?, ?, ?)
                        """, catalog.workerId(), catalog.catalogRevision(), model.modelId(),
                        model.supportedEfforts().get(effortPosition), effortPosition);
            }
        }
    }

    void validate(CodexCatalog catalog) {
        if (catalog == null
                || !SCHEMA_VERSION.equals(catalog.schemaVersion())
                || !workerProperties.getWorkerId().equals(catalog.workerId())
                || catalog.codexVersion() == null || !VERSION.matcher(catalog.codexVersion()).matches()
                || catalog.catalogRevision() == null || !DIGEST.matcher(catalog.catalogRevision()).matches()
                || catalog.generatedAt() == null
                || catalog.models() == null || catalog.models().isEmpty() || catalog.models().size() > 32) {
            throw new IllegalArgumentException("Worker Codex catalog identity is invalid");
        }
        Set<String> modelIds = new HashSet<>();
        for (CodexModel model : catalog.models()) {
            if (model == null || model.modelId() == null || !ID.matcher(model.modelId()).matches()
                    || !modelIds.add(model.modelId())
                    || model.displayName() == null || model.displayName().isBlank()
                    || model.displayName().length() > 80
                    || model.supportedEfforts() == null || model.supportedEfforts().isEmpty()
                    || model.supportedEfforts().size() > EFFORTS.size()
                    || new HashSet<>(model.supportedEfforts()).size() != model.supportedEfforts().size()
                    || !EFFORTS.containsAll(model.supportedEfforts())
                    || !model.supportedEfforts().contains(model.defaultEffort())
                    || !AVAILABILITY.contains(model.availability())) {
                throw new IllegalArgumentException("Worker Codex model catalog is invalid");
            }
        }
        if (!catalog.catalogRevision().equals(revision(catalog))) {
            throw new IllegalArgumentException("Worker Codex catalog revision is invalid");
        }
    }

    private String revision(CodexCatalog catalog) {
        try {
            List<Map<String, Object>> models = new ArrayList<>();
            for (CodexModel model : sortedModels(catalog)) {
                Map<String, Object> value = new TreeMap<>();
                value.put("availability", model.availability());
                value.put("defaultEffort", model.defaultEffort());
                value.put("displayName", model.displayName());
                value.put("modelId", model.modelId());
                value.put("supportedEfforts", model.supportedEfforts());
                models.add(value);
            }
            Map<String, Object> payload = new TreeMap<>();
            payload.put("codexVersion", catalog.codexVersion());
            payload.put("models", models);
            payload.put("schemaVersion", catalog.schemaVersion());
            byte[] encoded = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot canonicalize worker Codex catalog", exception);
        }
    }

    private List<CodexModel> sortedModels(CodexCatalog catalog) {
        return catalog.models().stream().sorted(Comparator.comparing(CodexModel::modelId)).toList();
    }

    private void verifyPersisted(CodexCatalog catalog) {
        Map<String, Object> header = jdbcTemplate.queryForMap("""
                SELECT schema_version, codex_version FROM worker_codex_catalog
                 WHERE worker_id = ? AND catalog_revision = ?
                """, catalog.workerId(), catalog.catalogRevision());
        if (!catalog.schemaVersion().equals(header.get("schema_version"))
                || !catalog.codexVersion().equals(header.get("codex_version"))) {
            throw new IllegalStateException("Persisted worker Codex catalog conflicts with observation");
        }
        List<CodexModel> models = sortedModels(catalog);
        List<PersistedModel> persistedModels = jdbcTemplate.query("""
                SELECT model_id, display_name, default_effort, availability, position
                  FROM worker_codex_model
                 WHERE worker_id = ? AND catalog_revision = ?
                 ORDER BY position
                """, (resultSet, rowNumber) -> new PersistedModel(
                        resultSet.getString("model_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("default_effort"),
                        resultSet.getString("availability"),
                        resultSet.getInt("position")),
                catalog.workerId(), catalog.catalogRevision());
        List<PersistedModel> expectedModels = new ArrayList<>();
        List<PersistedEffort> expectedEfforts = new ArrayList<>();
        for (int modelPosition = 0; modelPosition < models.size(); modelPosition++) {
            CodexModel model = models.get(modelPosition);
            expectedModels.add(new PersistedModel(model.modelId(), model.displayName(),
                    model.defaultEffort(), model.availability(), modelPosition));
            for (int effortPosition = 0; effortPosition < model.supportedEfforts().size(); effortPosition++) {
                expectedEfforts.add(new PersistedEffort(model.modelId(),
                        model.supportedEfforts().get(effortPosition), effortPosition));
            }
        }
        List<PersistedEffort> persistedEfforts = jdbcTemplate.query("""
                SELECT model_id, effort, position
                  FROM worker_codex_model_effort
                 WHERE worker_id = ? AND catalog_revision = ?
                 ORDER BY model_id, position
                """, (resultSet, rowNumber) -> new PersistedEffort(
                        resultSet.getString("model_id"),
                        resultSet.getString("effort"),
                        resultSet.getInt("position")),
                catalog.workerId(), catalog.catalogRevision());
        expectedEfforts.sort(Comparator.comparing(PersistedEffort::modelId)
                .thenComparingInt(PersistedEffort::position));
        if (!expectedModels.equals(persistedModels) || !expectedEfforts.equals(persistedEfforts)) {
            throw new IllegalStateException("Persisted worker Codex catalog conflicts with observation");
        }
    }

    private record PersistedModel(
            String modelId,
            String displayName,
            String defaultEffort,
            String availability,
            int position) {
    }

    private record PersistedEffort(String modelId, String effort, int position) {
    }
}
