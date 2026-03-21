ALTER TABLE task_execution
    DROP CONSTRAINT ck_task_execution_status;

UPDATE task_execution
SET status = 'RUNNING'
WHERE status = 'STARTED';

ALTER TABLE task_execution
    ADD CONSTRAINT ck_task_execution_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
