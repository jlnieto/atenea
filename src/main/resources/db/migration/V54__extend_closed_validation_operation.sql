ALTER TABLE validation_operation
    DROP CONSTRAINT ck_validation_operation_kind;

ALTER TABLE validation_operation
    ADD CONSTRAINT ck_validation_operation_kind
        CHECK (operation IN (
            'BACKEND_TEST',
            'WEB_BUILD',
            'ANDROID_BUILD',
            'PLAYWRIGHT_ACCEPTANCE'
        ));
