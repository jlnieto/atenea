ALTER TABLE task
    ADD COLUMN base_branch VARCHAR(120) NOT NULL DEFAULT 'main',
    ADD COLUMN branch_name VARCHAR(180),
    ADD COLUMN branch_status VARCHAR(32) NOT NULL DEFAULT 'PLANNED';

UPDATE task
SET branch_name = 'task/' || id || '-' || regexp_replace(
        regexp_replace(
                regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g'),
                '(^-+|-+$)',
                '',
                'g'
        ),
        '-{2,}',
        '-',
        'g'
    );

ALTER TABLE task
    ALTER COLUMN branch_name SET NOT NULL;

ALTER TABLE task
    ADD CONSTRAINT uk_task_branch_name UNIQUE (branch_name),
    ADD CONSTRAINT ck_task_branch_status
        CHECK (branch_status IN ('PLANNED', 'ACTIVE', 'REVIEW_PENDING', 'CLOSED'));
