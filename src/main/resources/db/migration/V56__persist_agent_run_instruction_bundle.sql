ALTER TABLE agent_run
    ADD COLUMN instruction_bundle_revision VARCHAR(80),
    ADD COLUMN instruction_bundle_sha256 VARCHAR(64),
    ADD COLUMN platform_instruction_sha256 VARCHAR(64),
    ADD COLUMN project_instruction_path VARCHAR(80),
    ADD COLUMN project_instruction_sha256 VARCHAR(64);

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_instruction_bundle_complete
    CHECK (
        (instruction_bundle_revision IS NULL
            AND instruction_bundle_sha256 IS NULL
            AND platform_instruction_sha256 IS NULL
            AND project_instruction_path IS NULL
            AND project_instruction_sha256 IS NULL)
        OR
        (instruction_bundle_revision IS NOT NULL
            AND instruction_bundle_sha256 ~ '^[0-9a-f]{64}$'
            AND platform_instruction_sha256 ~ '^[0-9a-f]{64}$'
            AND project_instruction_path = 'AGENTS.md'
            AND project_instruction_sha256 ~ '^[0-9a-f]{64}$')
    );
