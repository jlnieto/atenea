ALTER TABLE work_session
    ADD COLUMN pull_request_url VARCHAR(500),
    ADD COLUMN pull_request_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CREATED',
    ADD COLUMN final_commit_sha VARCHAR(64),
    ADD COLUMN published_at TIMESTAMPTZ;
