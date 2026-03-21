ALTER TABLE task_execution
    ADD COLUMN runner_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN output_summary TEXT,
    ADD COLUMN error_summary TEXT;

ALTER TABLE task_execution
    ADD CONSTRAINT ck_task_execution_runner_type
        CHECK (runner_type IN ('MANUAL', 'CODEX'));

ALTER TABLE task_execution
    ALTER COLUMN runner_type DROP DEFAULT;
