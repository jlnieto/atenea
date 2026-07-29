package com.atenea.previews;

import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.service.worksession.WorkSessionPreviewMetadataService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PreviewReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PreviewReconciliationService.class);
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(2);

    private final PreviewProperties properties;
    private final PreviewWorkerClient workerClient;
    private final WorkSessionPreviewService previewService;
    private final WorkSessionPreviewMetadataService metadataService;

    public PreviewReconciliationService(
            PreviewProperties properties,
            PreviewWorkerClient workerClient,
            WorkSessionPreviewService previewService,
            WorkSessionPreviewMetadataService metadataService
    ) {
        this.properties = properties;
        this.workerClient = workerClient;
        this.previewService = previewService;
        this.metadataService = metadataService;
    }

    public int reconcilePersisted() {
        if (!properties.isEnabled()) {
            return 0;
        }
        int limit = Math.max(1, Math.min(properties.getReconciliationBatchSize(), 100));
        List<WorkSessionPreviewEntity> candidates = metadataService.reconcilable()
                .stream()
                .limit(limit)
                .toList();
        int reconciled = 0;
        for (WorkSessionPreviewEntity preview : candidates) {
            try {
                reconcile(preview);
                reconciled++;
            } catch (RuntimeException exception) {
                log.warn(
                        "preview reconciliation retained {} unchanged: {}",
                        preview.getId(),
                        exception.getMessage());
            }
        }
        return reconciled;
    }

    @Scheduled(fixedDelayString = "${ATENEA_PREVIEWS_RECONCILIATION_DELAY_MS:30000}")
    public void reconcilePeriodically() {
        reconcilePersisted();
    }

    private void reconcile(WorkSessionPreviewEntity preview) {
        Instant now = Instant.now();
        if (preview.getState() == PreviewState.READY
                && now.plus(RENEW_BEFORE).isAfter(preview.getLeaseExpiresAt())
                && now.isBefore(preview.getHardExpiresAt())) {
            PreviewWorkerClient.Projection renewed = workerClient.renew(
                    previewService.ownership(preview));
            previewService.validateProjection(
                    preview, renewed, "READY", preview.getLifecycleRevision() + 1);
            metadataService.renewFromWorker(
                    preview.getId(),
                    preview.getLifecycleRevision(),
                    renewed.leaseExpiresAt(),
                    renewed.hardExpiresAt(),
                    now);
            return;
        }

        PreviewWorkerClient.Projection projection = workerClient.inspect(
                previewService.ownership(preview));
        long workerRevision = projection.lifecycleRevision();
        String state = projection.state();
        if ("READY".equals(state)
                && (preview.getState() == PreviewState.STARTING
                || preview.getState() == PreviewState.RECONCILING)) {
            previewService.validateProjection(
                    preview, projection, "READY", preview.getLifecycleRevision() + 1);
            metadataService.markReady(
                    preview.getId(),
                    preview.getLifecycleRevision(),
                    projection.privateUrl(),
                    projection.localhostCompatible(),
                    projection.leaseExpiresAt(),
                    projection.hardExpiresAt(),
                    now);
            return;
        }
        if ("READY".equals(state) && preview.getState() == PreviewState.READY) {
            previewService.validateProjection(
                    preview, projection, "READY", preview.getLifecycleRevision());
            return;
        }
        if ("STOPPED".equals(state) && workerRevision == preview.getLifecycleRevision() + 1) {
            previewService.validateProjection(
                    preview, projection, "STOPPED", workerRevision);
            metadataService.stop(preview.getId(), preview.getLifecycleRevision(), now);
            return;
        }
        if ("EXPIRED".equals(state) && workerRevision == preview.getLifecycleRevision()) {
            previewService.validateProjection(
                    preview, projection, "EXPIRED", workerRevision);
            metadataService.expire(preview.getId(), preview.getLifecycleRevision(), now);
            return;
        }
        if ("BLOCKED".equals(state)
                && (workerRevision == preview.getLifecycleRevision()
                || workerRevision == preview.getLifecycleRevision() + 1)) {
            previewService.validateProjection(
                    preview, projection, "BLOCKED", workerRevision);
            metadataService.block(
                    preview.getId(),
                    preview.getLifecycleRevision(),
                    "worker_projection_blocked",
                    "AX42 no pudo restaurar la misma proyección privada.",
                    "Revisa el runtime y vuelve a iniciar el preview.",
                    now);
            return;
        }
        throw new PreviewWorkerException(
                "AX42 devolvió un estado o revisión que no coincide con el preview persistido.",
                409,
                "preview_reconciliation_conflict");
    }
}
