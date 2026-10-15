package com.atenea.attachments;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, audited, idempotent recovery; a capability GET never mutates a session. */
@Service
public class ChangeAttachmentEnablementService {
    private final JdbcTemplate jdbc;
    private final WorkSessionRepository sessions;
    private final NewWorkSessionAttachmentPolicySnapshotter snapshotter;
    private final AttachmentCapabilityService capabilities;

    public ChangeAttachmentEnablementService(JdbcTemplate jdbc, WorkSessionRepository sessions,
            NewWorkSessionAttachmentPolicySnapshotter snapshotter, AttachmentCapabilityService capabilities) {
        this.jdbc=jdbc; this.sessions=sessions; this.snapshotter=snapshotter; this.capabilities=capabilities;
    }

    @Transactional
    public AttachmentCapability enable(Long id, AuthenticatedOperator actor) {
        if (actor == null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM operator_account "
                + "WHERE id=? AND active AND codex_operations_role='PLATFORM_ADMINISTRATOR')", Boolean.class, actor.operatorId()))) {
            throw new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED");
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        jdbc.queryForList("SELECT id FROM work_session WHERE id=? FOR UPDATE", id);
        var session=sessions.findWithProjectAndDevelopmentChangeById(id)
                .orElseThrow(() -> new DeliveryRejectedException("WORKSESSION_NOT_FOUND"));
        if (session.getStatus()!=WorkSessionStatus.OPEN || !AttachmentOwnership.changeOwned(session)
                || session.getDevelopmentChange().getStatus()!=DevelopmentChangeStatus.OPEN
                || session.getDevelopmentChange().getWorkspaceState()!=com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState.READY) {
            throw new DeliveryRejectedException("OPEN_CHANGE_WORKSESSION_REQUIRED");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM agent_run WHERE session_id=? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))", Boolean.class, id))) {
            throw new DeliveryRejectedException("ACTIVE_AGENT_RUN");
        }
        if (session.getAttachmentPolicyRevision()!=null
                && !AttachmentOwnership.CHANGE_POLICY.equals(session.getAttachmentPolicyRevision())) {
            throw new DeliveryRejectedException("ATTACHMENT_OWNERSHIP_MISMATCH");
        }
        snapshotter.snapshotNewSession(session);
        if (!AttachmentOwnership.exact(session)) throw new DeliveryRejectedException("ATTACHMENTS_DISABLED");
        sessions.saveAndFlush(session);
        var capability=capabilities.get(id);
        if (capability.state()!=AttachmentCapability.State.READY) throw new DeliveryRejectedException("ATTACHMENT_WORKER_NOT_READY");
        jdbc.update("""
                INSERT INTO work_session_attachment_enablement
                (session_id,operation_id,operator_id,policy_revision,workspace_identity,remote_session_id)
                VALUES (?,?,?,?,?,?) ON CONFLICT (session_id) DO NOTHING
                """, id, UUID.randomUUID(), actor.operatorId(), AttachmentOwnership.CHANGE_POLICY,
                session.getWorkspaceIdentity(), session.getRemoteSessionId());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM work_session_attachment_enablement WHERE session_id=? "
                + "AND policy_revision=? AND workspace_identity=? AND remote_session_id=?)",Boolean.class,id,
                AttachmentOwnership.CHANGE_POLICY,session.getWorkspaceIdentity(),session.getRemoteSessionId()))) {
            throw new DeliveryRejectedException("ATTACHMENT_OWNERSHIP_MISMATCH");
        }
        return capability;
    }
}
