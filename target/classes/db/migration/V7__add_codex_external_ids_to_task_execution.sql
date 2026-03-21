ALTER TABLE task_execution
    ADD COLUMN external_thread_id VARCHAR(100),
    ADD COLUMN external_turn_id VARCHAR(100);
