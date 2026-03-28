ALTER TABLE work_session
    ADD COLUMN close_blocked_state VARCHAR(120),
    ADD COLUMN close_blocked_reason TEXT,
    ADD COLUMN close_blocked_action TEXT,
    ADD COLUMN close_retryable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE work_session DROP CONSTRAINT ck_work_session_status;
ALTER TABLE work_session DROP CONSTRAINT ck_work_session_closed_at_consistency;

ALTER TABLE work_session
    ADD CONSTRAINT ck_work_session_status
        CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED'));

ALTER TABLE work_session
    ADD CONSTRAINT ck_work_session_closed_at_consistency
        CHECK (
            (status IN ('OPEN', 'CLOSING') AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        );

DROP INDEX uk_work_session_open_project;

CREATE UNIQUE INDEX uk_work_session_active_project
    ON work_session (project_id) WHERE status IN ('OPEN', 'CLOSING');
