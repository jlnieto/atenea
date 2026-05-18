CREATE TABLE project_database_refresh_run (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    runtime_contract_path VARCHAR(600),
    database_engine VARCHAR(40),
    local_database VARCHAR(160),
    source_host VARCHAR(160),
    source_database VARCHAR(160),
    decision_brief TEXT,
    technical_summary TEXT,
    blocker_type VARCHAR(80),
    blocker_summary TEXT,
    recommended_action TEXT,
    command_exit_code INTEGER,
    command_output_summary TEXT,
    duration_millis BIGINT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_project_database_refresh_run_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_project_database_refresh_run_status
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED', 'BLOCKED'))
);

CREATE INDEX idx_project_database_refresh_run_project_created
    ON project_database_refresh_run (project_id, created_at DESC);

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
                'PROJECT_DATABASE_REFRESH_RUN',
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
                'PROJECT_DATABASE_REFRESH_RUN',
                'MANAGED_HOST',
                'MANAGED_SERVICE',
                'OPERATIONS_INCIDENT',
                'OPERATIONS_ACTION_RUN'
            )
        );
