ALTER TABLE core_command
    DROP CONSTRAINT ck_core_command_status,
    DROP CONSTRAINT ck_core_command_result_type,
    DROP CONSTRAINT ck_core_command_target_type;

ALTER TABLE core_command
    ADD CONSTRAINT ck_core_command_status
        CHECK (status IN ('RECEIVED', 'SUCCEEDED', 'NEEDS_CONFIRMATION', 'NEEDS_CLARIFICATION', 'REJECTED', 'FAILED')),
    ADD CONSTRAINT ck_core_command_result_type
        CHECK (
            result_type IS NULL
            OR result_type IN (
                'PROJECT_OVERVIEW_LIST',
                'PROJECT_OVERVIEW',
                'PROJECT_CONTEXT',
                'WORK_SESSION_SUMMARY',
                'SESSION_DELIVERABLES_VIEW',
                'SESSION_DELIVERABLE',
                'WORK_SESSION',
                'WORK_SESSION_VIEW',
                'WORK_SESSION_CONVERSATION_VIEW'
            )
        ),
    ADD CONSTRAINT ck_core_command_target_type
        CHECK (
            target_type IS NULL
            OR target_type IN (
                'PROJECT',
                'WORK_SESSION',
                'SESSION_TURN',
                'SESSION_DELIVERABLE',
                'OPERATOR_CONTEXT'
            )
        );
