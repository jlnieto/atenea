CREATE TABLE managed_host (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    description TEXT,
    environment VARCHAR(40) NOT NULL,
    ssh_host VARCHAR(255) NOT NULL,
    ssh_port INTEGER NOT NULL DEFAULT 22,
    ssh_user VARCHAR(120) NOT NULL,
    ssh_key_path VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_managed_host_ssh_port
        CHECK (ssh_port > 0 AND ssh_port <= 65535)
);

CREATE TABLE managed_service (
    id BIGSERIAL PRIMARY KEY,
    host_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    service_type VARCHAR(60) NOT NULL,
    systemd_unit VARCHAR(120),
    process_pattern VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_managed_service_host
        FOREIGN KEY (host_id) REFERENCES managed_host (id) ON DELETE CASCADE,
    CONSTRAINT uq_managed_service_host_name
        UNIQUE (host_id, name),
    CONSTRAINT ck_managed_service_type
        CHECK (service_type IN ('WEB_SERVER', 'DATABASE', 'APPLICATION', 'SYSTEM'))
);

CREATE TABLE managed_website (
    id BIGSERIAL PRIMARY KEY,
    host_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    url VARCHAR(500) NOT NULL,
    expected_status INTEGER NOT NULL DEFAULT 200,
    timeout_millis INTEGER NOT NULL DEFAULT 10000,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_managed_website_host
        FOREIGN KEY (host_id) REFERENCES managed_host (id) ON DELETE CASCADE,
    CONSTRAINT uq_managed_website_host_name
        UNIQUE (host_id, name),
    CONSTRAINT ck_managed_website_expected_status
        CHECK (expected_status >= 100 AND expected_status <= 599),
    CONSTRAINT ck_managed_website_timeout
        CHECK (timeout_millis >= 1000 AND timeout_millis <= 60000)
);

CREATE TABLE operations_incident (
    id BIGSERIAL PRIMARY KEY,
    host_id BIGINT NOT NULL,
    service_id BIGINT,
    status VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    title VARCHAR(220) NOT NULL,
    summary TEXT,
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_operations_incident_host
        FOREIGN KEY (host_id) REFERENCES managed_host (id),
    CONSTRAINT fk_operations_incident_service
        FOREIGN KEY (service_id) REFERENCES managed_service (id),
    CONSTRAINT ck_operations_incident_status
        CHECK (status IN ('OPEN', 'MITIGATING', 'RESOLVED', 'FAILED')),
    CONSTRAINT ck_operations_incident_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX idx_operations_incident_status_activity
    ON operations_incident (status, last_activity_at DESC);

CREATE TABLE operations_action_run (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT,
    host_id BIGINT NOT NULL,
    service_id BIGINT,
    action VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    stdout_summary TEXT,
    stderr_summary TEXT,
    result_json TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_operations_action_run_incident
        FOREIGN KEY (incident_id) REFERENCES operations_incident (id),
    CONSTRAINT fk_operations_action_run_host
        FOREIGN KEY (host_id) REFERENCES managed_host (id),
    CONSTRAINT fk_operations_action_run_service
        FOREIGN KEY (service_id) REFERENCES managed_service (id),
    CONSTRAINT ck_operations_action_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_operations_action_run_host_created
    ON operations_action_run (host_id, created_at DESC);

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
                'MANAGED_HOST',
                'MANAGED_SERVICE',
                'OPERATIONS_INCIDENT',
                'OPERATIONS_ACTION_RUN'
            )
        );
