UPDATE task_execution
SET runner_type = 'CODEX'
WHERE runner_type = 'MANUAL';

ALTER TABLE task_execution
    DROP CONSTRAINT ck_task_execution_runner_type;

ALTER TABLE task_execution
    ADD CONSTRAINT ck_task_execution_runner_type
        CHECK (runner_type IN ('CODEX'));
