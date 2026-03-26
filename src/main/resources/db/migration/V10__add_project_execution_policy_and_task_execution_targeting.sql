ALTER TABLE project
    ADD COLUMN default_environment VARCHAR(16) NOT NULL DEFAULT 'PROD',
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN sandbox_repo_path VARCHAR(500);

ALTER TABLE task_execution
    ADD COLUMN environment VARCHAR(16),
    ADD COLUMN execution_mode VARCHAR(16),
    ADD COLUMN target_repo_path VARCHAR(500);

UPDATE task_execution te
SET environment = p.default_environment,
    execution_mode = p.execution_mode,
    target_repo_path = CASE
        WHEN p.execution_mode = 'SANDBOX' AND p.sandbox_repo_path IS NOT NULL THEN p.sandbox_repo_path
        ELSE p.repo_path
    END
FROM task t
         JOIN project p ON p.id = t.project_id
WHERE te.task_id = t.id;

ALTER TABLE task_execution
    ALTER COLUMN environment SET NOT NULL,
    ALTER COLUMN execution_mode SET NOT NULL,
    ALTER COLUMN target_repo_path SET NOT NULL;
