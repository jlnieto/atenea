ALTER TABLE task
    ADD CONSTRAINT ck_task_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    ADD CONSTRAINT ck_task_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'));

ALTER TABLE task_execution
    ADD CONSTRAINT ck_task_execution_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
