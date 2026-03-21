CREATE TABLE project (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_name UNIQUE (name)
);

CREATE TABLE task (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    command TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT uk_task_project_name UNIQUE (project_id, name)
);

CREATE TABLE task_execution (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_execution_task
        FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
);

CREATE INDEX idx_task_project_id ON task (project_id);
CREATE INDEX idx_task_execution_task_id ON task_execution (task_id);
CREATE INDEX idx_task_execution_status ON task_execution (status);
CREATE INDEX idx_task_execution_started_at ON task_execution (started_at);
