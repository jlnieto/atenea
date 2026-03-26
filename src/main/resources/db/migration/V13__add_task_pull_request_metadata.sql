ALTER TABLE task
    ADD COLUMN pull_request_url VARCHAR(500),
    ADD COLUMN pull_request_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CREATED';

ALTER TABLE task
    ADD CONSTRAINT ck_task_pull_request_status
        CHECK (pull_request_status IN ('NOT_CREATED', 'OPEN', 'APPROVED', 'MERGED', 'DECLINED'));
