ALTER TABLE work_session
    ADD COLUMN published_change_key UUID,
    ADD COLUMN published_source_revision BIGINT,
    ADD COLUMN published_source_fingerprint_sha256 VARCHAR(64),
    ADD COLUMN published_workspace_ownership_fingerprint_sha256 VARCHAR(64),
    ADD COLUMN published_repository VARCHAR(160),
    ADD COLUMN published_base_branch VARCHAR(120),
    ADD COLUMN published_head_branch VARCHAR(180),
    ADD COLUMN publication_receipt_sha256 VARCHAR(64),
    ADD CONSTRAINT fk_work_session_published_change_key
        FOREIGN KEY (published_change_key)
        REFERENCES development_change (change_key) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_work_session_change_publication_identity CHECK (
        (published_change_key IS NULL
            AND published_source_revision IS NULL
            AND published_source_fingerprint_sha256 IS NULL
            AND published_workspace_ownership_fingerprint_sha256 IS NULL
            AND published_repository IS NULL
            AND published_base_branch IS NULL
            AND published_head_branch IS NULL
            AND publication_receipt_sha256 IS NULL)
        OR
        (development_change_id IS NOT NULL
            AND published_change_key IS NOT NULL
            AND published_source_revision IS NOT NULL
            AND published_source_revision >= 0
            AND published_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND published_workspace_ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND published_repository = 'jlnieto/atenea'
            AND published_base_branch = 'main'
            AND published_head_branch = 'atenea/change-' || published_change_key::text
            AND publication_receipt_sha256 ~ '^[0-9a-f]{64}$'
            AND final_commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'))
    ;
