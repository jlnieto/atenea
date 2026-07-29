package com.atenea.remoteworker;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RemoteRoutingSelector {

    private static final Logger log = LoggerFactory.getLogger(RemoteRoutingSelector.class);

    private final RemoteWorkerProperties properties;
    private final RemoteWorkerClient client;
    private final WorkerNodeRepository workerNodeRepository;

    public RemoteRoutingSelector(
            RemoteWorkerProperties properties,
            RemoteWorkerClient client,
            WorkerNodeRepository workerNodeRepository
    ) {
        this.properties = properties;
        this.client = client;
        this.workerNodeRepository = workerNodeRepository;
    }

    public void pinNewSession(WorkSessionEntity session) {
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setSelectedWorkerId(null);
        session.setWorkspaceIdentity("local:work-session:" + session.getId());
        session.setRemoteSessionId(null);
        session.setRemoteWorkloadKind(null);
        String workloadKind = selectedWorkloadKind(session);
        if (workloadKind == null) {
            return;
        }

        try {
            RemoteWorkerClient.Health health = client.health();
            WorkerNodeEntity worker = recordHealth(health, null);
            if (!health.healthy()
                    || !RemoteWorkerProperties.PROTOCOL.equals(health.protocolVersion())
                    || !health.capabilities().contains(workloadKind)
                    || !properties.getWorkerId().equals(health.workerId())) {
                worker.setEnabled(false);
                worker.setUnavailableReason("Worker is unhealthy, incompatible or has an unexpected identity");
                workerNodeRepository.save(worker);
                return;
            }
            worker.setEnabled(true);
            workerNodeRepository.save(worker);
            session.setExecutionTarget(ExecutionTarget.REMOTE);
            session.setSelectedWorkerId(health.workerId());
            UUID remoteSessionId = UUID.randomUUID();
            session.setRemoteSessionId(remoteSessionId);
            session.setRemoteWorkloadKind(workloadKind);
            session.setWorkspaceIdentity(
                    "remote:" + health.workerId() + ":work-session:" + remoteSessionId);
        } catch (RemoteWorkerException exception) {
            recordUnavailable(exception.getMessage());
            log.warn("new WorkSession remains local because remote worker selection failed: {}", exception.getMessage());
        }
    }

    private String selectedWorkloadKind(WorkSessionEntity session) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (properties.isBeautipsProjectCodexEnabled()
                && BeautipsProjectCodexIdentity.matchesNewSession(session)) {
            return ProjectCodexIdentity.WORKLOAD_KIND;
        }
        if (properties.isProjectCodexEnabled() && ProjectCodexIdentity.matches(session)) {
            return ProjectCodexIdentity.WORKLOAD_KIND;
        }
        if (properties.getSyntheticProjectAllowlist().contains(session.getProject().getName())) {
            return "synthetic-routing-v1";
        }
        return null;
    }

    private WorkerNodeEntity recordHealth(RemoteWorkerClient.Health health, String unavailableReason) {
        Instant now = Instant.now();
        WorkerNodeEntity worker = workerNodeRepository.findById(health.workerId()).orElseGet(WorkerNodeEntity::new);
        if (worker.getId() == null) {
            worker.setId(health.workerId());
            worker.setCreatedAt(now);
        }
        worker.setProtocolVersion(health.protocolVersion());
        worker.setEndpoint(properties.getEndpoint());
        worker.setHealthy(health.healthy());
        worker.setNormalCapacity(health.normalCapacity());
        worker.setHeavyCapacity(health.heavyCapacity());
        worker.setNormalInUse(health.normalInUse());
        worker.setHeavyInUse(health.heavyInUse());
        worker.setCapabilities(String.join(",", health.capabilities()));
        worker.setLastHeartbeatAt(now);
        worker.setUnavailableReason(unavailableReason);
        worker.setUpdatedAt(now);
        return worker;
    }

    private void recordUnavailable(String reason) {
        Instant now = Instant.now();
        WorkerNodeEntity worker = workerNodeRepository.findById(properties.getWorkerId()).orElseGet(WorkerNodeEntity::new);
        if (worker.getId() == null) {
            worker.setId(properties.getWorkerId());
            worker.setCreatedAt(now);
            worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
            worker.setEndpoint(properties.getEndpoint());
            worker.setNormalCapacity(4);
            worker.setHeavyCapacity(2);
            worker.setCapabilities("synthetic-routing-v1");
        }
        worker.setEnabled(false);
        worker.setHealthy(false);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setUnavailableReason(reason);
        worker.setUpdatedAt(now);
        workerNodeRepository.save(worker);
    }
}
