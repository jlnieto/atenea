DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'operator_push_device'
          AND column_name = 'expo_push_token'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'operator_push_device'
          AND column_name = 'push_token'
    ) THEN
        ALTER TABLE operator_push_device RENAME COLUMN expo_push_token TO push_token;
    END IF;
END $$;
