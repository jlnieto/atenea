ALTER TABLE project
    ALTER COLUMN repo_path SET NOT NULL;

ALTER TABLE project
    ADD CONSTRAINT ck_project_repo_path_absolute
        CHECK (
            repo_path LIKE '/%'
        );
