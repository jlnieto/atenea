package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atenea.AteneaApplication;
import com.atenea.remoteworker.RemoteWorkerClient.CodexCatalog;
import com.atenea.remoteworker.RemoteWorkerClient.CodexModel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = AteneaApplication.class)
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.remote-worker.worker-id=catalog-sync-test"
})
@Transactional
class WorkerCodexCatalogSyncServiceIntegrationTest {
    private static final String REVISION =
            "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187";

    @Autowired
    private WorkerCodexCatalogSyncService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities
                ) VALUES ('catalog-sync-test', 'agent-run-worker/v1',
                    'http://127.0.0.1:1', false, false, 4, 2, '')
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void persistsValidatedCatalogExactlyAndRepeatsIdempotently() {
        CodexCatalog catalog = catalog(REVISION);

        service.synchronize(catalog);
        service.synchronize(new CodexCatalog(
                catalog.schemaVersion(),
                catalog.catalogRevision(),
                catalog.workerId(),
                catalog.codexVersion(),
                catalog.generatedAt().plusSeconds(30),
                catalog.models()));

        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_catalog
                 WHERE worker_id = 'catalog-sync-test' AND catalog_revision = ?
                """, Integer.class, REVISION));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_model
                 WHERE worker_id = 'catalog-sync-test' AND catalog_revision = ?
                """, Integer.class, REVISION));
        assertEquals(6, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_model_effort
                 WHERE worker_id = 'catalog-sync-test' AND catalog_revision = ?
                """, Integer.class, REVISION));
        assertEquals("medium", jdbcTemplate.queryForObject("""
                SELECT default_effort FROM worker_codex_model
                 WHERE worker_id = 'catalog-sync-test' AND catalog_revision = ?
                   AND model_id = 'gpt-5.6-sol'
                """, String.class, REVISION));
    }

    @Test
    void rejectsCatalogWhoseRevisionDoesNotMatchCanonicalContent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.synchronize(catalog("a".repeat(64))));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_catalog
                 WHERE worker_id = 'catalog-sync-test'
                """, Integer.class));
    }

    private CodexCatalog catalog(String revision) {
        return new CodexCatalog(
                "codex-model-catalog-v1",
                revision,
                "catalog-sync-test",
                "0.145.0",
                Instant.parse("2026-07-31T23:00:00Z"),
                List.of(new CodexModel(
                        "gpt-5.6-sol",
                        "GPT-5.6 Sol",
                        List.of("none", "low", "medium", "high", "xhigh", "max"),
                        "medium",
                        "AVAILABLE")));
    }
}
