ALTER TABLE task_execution
    DROP COLUMN IF EXISTS environment,
    DROP COLUMN IF EXISTS execution_mode;

ALTER TABLE project
    DROP COLUMN IF EXISTS default_environment,
    DROP COLUMN IF EXISTS execution_mode,
    DROP COLUMN IF EXISTS sandbox_repo_path;
