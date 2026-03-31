ALTER TABLE core_command
    ADD COLUMN interpreter_source VARCHAR(32),
    ADD COLUMN interpreter_detail TEXT;

ALTER TABLE core_command
    ADD CONSTRAINT ck_core_command_interpreter_source
        CHECK (
            interpreter_source IS NULL
            OR interpreter_source IN ('DETERMINISTIC', 'LLM', 'DETERMINISTIC_FALLBACK')
        );

CREATE INDEX idx_core_command_interpreter_source_created_at_desc
    ON core_command (interpreter_source, created_at DESC);
