-- Retain immutable legacy attachments; new admissions require exact change ownership.
ALTER TABLE work_session DROP CONSTRAINT ck_work_session_attachment_policy;
ALTER TABLE work_session ADD CONSTRAINT ck_work_session_attachment_policy CHECK (
    attachment_policy_revision IS NULL OR (
        execution_target='REMOTE' AND selected_worker_id='ax42-01'
        AND remote_session_id IS NOT NULL AND remote_workload_kind='project-codex-v1'
        AND (
            (attachment_policy_revision='atenea-real-attachments-v1'
                AND workspace_identity='remote:' || selected_worker_id || ':work-session:' || remote_session_id::text)
            OR (attachment_policy_revision='atenea-change-attachments-v1'
                AND development_change_id IS NOT NULL
                AND workspace_branch ~ '^atenea/change-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                AND workspace_identity='remote:' || selected_worker_id || ':change:' || substring(workspace_branch FROM 15))
        )
    )
);
ALTER TABLE work_session_attachment DROP CONSTRAINT ck_work_session_attachment_storage_scope;
ALTER TABLE work_session_attachment ADD CONSTRAINT ck_work_session_attachment_storage_scope CHECK (
    (storage_scope IS NULL AND remote_session_id IS NULL AND workspace_identity IS NULL)
    OR (storage_scope='REAL_SESSION' AND remote_session_id IS NOT NULL AND workspace_identity IS NOT NULL
        AND source='OPERATOR_UPLOAD' AND retention_class='SESSION'
        AND (workspace_identity='remote:' || worker_id || ':work-session:' || remote_session_id::text
            OR (worker_id='ax42-01' AND workspace_identity ~ '^remote:ax42-01:change:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')))
);
-- The existing composite FK still binds every attachment to its exact App owner.
CREATE TABLE work_session_attachment_enablement (
    session_id BIGINT PRIMARY KEY REFERENCES work_session(id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE RESTRICT,
    policy_revision VARCHAR(80) NOT NULL CHECK (policy_revision='atenea-change-attachments-v1'),
    workspace_identity VARCHAR(200) NOT NULL,
    remote_session_id UUID NOT NULL,
    enabled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
