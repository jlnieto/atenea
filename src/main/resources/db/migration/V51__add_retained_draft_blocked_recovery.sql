ALTER TABLE work_session
    ADD COLUMN draft_fingerprint_sha256 VARCHAR(64),
    ADD COLUMN draft_retained_head VARCHAR(64),
    ADD COLUMN draft_staged_change_count INTEGER,
    ADD COLUMN draft_unstaged_change_count INTEGER,
    ADD COLUMN draft_untracked_change_count INTEGER,
    ADD COLUMN draft_blocked_at TIMESTAMPTZ,
    ADD COLUMN replacement_work_session_id BIGINT REFERENCES work_session(id);

ALTER TABLE work_session DROP CONSTRAINT ck_work_session_status;
ALTER TABLE work_session DROP CONSTRAINT ck_work_session_closed_at_consistency;

ALTER TABLE work_session
    ADD CONSTRAINT ck_work_session_status
        CHECK (status IN ('OPEN', 'CLOSING', 'DRAFT_BLOCKED', 'CLOSED')),
    ADD CONSTRAINT ck_work_session_closed_at_consistency
        CHECK (
            (status IN ('OPEN', 'CLOSING', 'DRAFT_BLOCKED') AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_work_session_retained_draft
        CHECK (
            (status <> 'DRAFT_BLOCKED'
                AND draft_fingerprint_sha256 IS NULL
                AND draft_retained_head IS NULL
                AND draft_staged_change_count IS NULL
                AND draft_unstaged_change_count IS NULL
                AND draft_untracked_change_count IS NULL
                AND draft_blocked_at IS NULL
                AND replacement_work_session_id IS NULL)
            OR (status = 'DRAFT_BLOCKED'
                AND draft_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
                AND draft_retained_head ~ '^[0-9a-f]{40}$'
                AND draft_staged_change_count >= 0
                AND draft_unstaged_change_count >= 0
                AND draft_untracked_change_count >= 0
                AND (draft_staged_change_count
                    + draft_unstaged_change_count
                    + draft_untracked_change_count) > 0
                AND draft_blocked_at IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_work_session_replacement
    ON work_session (replacement_work_session_id)
    WHERE replacement_work_session_id IS NOT NULL;
