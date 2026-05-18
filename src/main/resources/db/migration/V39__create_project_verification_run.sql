CREATE TABLE project_verification_run (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    work_session_id BIGINT,
    status VARCHAR(32) NOT NULL,
    runtime_contract_path VARCHAR(600),
    runtime_profile VARCHAR(80),
    base_url VARCHAR(500),
    decision_brief TEXT,
    technical_summary TEXT,
    blocker_type VARCHAR(80),
    blocker_summary TEXT,
    recommended_action TEXT,
    tests_json TEXT,
    artifacts_json TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_verification_run_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_verification_run_work_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE SET NULL,
    CONSTRAINT ck_project_verification_run_status
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED', 'BLOCKED'))
);

CREATE INDEX idx_project_verification_run_project_created
    ON project_verification_run (project_id, created_at DESC);

CREATE INDEX idx_project_verification_run_session_created
    ON project_verification_run (work_session_id, created_at DESC);

ALTER TABLE core_command
    DROP CONSTRAINT ck_core_command_result_type,
    DROP CONSTRAINT ck_core_command_target_type;

ALTER TABLE core_command
    ADD CONSTRAINT ck_core_command_result_type
        CHECK (
            result_type IS NULL
            OR result_type IN (
                'PROJECT_OVERVIEW_LIST',
                'PROJECT_OVERVIEW',
                'PROJECT_CONTEXT',
                'WORK_SESSION_SUMMARY',
                'SESSION_DELIVERABLES_VIEW',
                'SESSION_DELIVERABLE',
                'WORK_SESSION',
                'WORK_SESSION_VIEW',
                'WORK_SESSION_CONVERSATION_VIEW',
                'PROJECT_VERIFICATION_RUN',
                'OPERATIONS_HOST_LIST',
                'OPERATIONS_HOST_STATUS',
                'OPERATIONS_SERVICE_CHECK',
                'OPERATIONS_WEBSITE_CHECK',
                'OPERATIONS_INCIDENT',
                'OPERATIONS_ACTION_RUN'
            )
        ),
    ADD CONSTRAINT ck_core_command_target_type
        CHECK (
            target_type IS NULL
            OR target_type IN (
                'PROJECT',
                'WORK_SESSION',
                'SESSION_TURN',
                'SESSION_DELIVERABLE',
                'OPERATOR_CONTEXT',
                'PROJECT_VERIFICATION_RUN',
                'MANAGED_HOST',
                'MANAGED_SERVICE',
                'OPERATIONS_INCIDENT',
                'OPERATIONS_ACTION_RUN'
            )
        );
