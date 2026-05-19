ALTER TABLE managed_website
    ADD COLUMN degraded_threshold_millis INTEGER;

UPDATE managed_website
SET degraded_threshold_millis = LEAST(2500, timeout_millis);

ALTER TABLE managed_website
    ALTER COLUMN degraded_threshold_millis SET NOT NULL,
    ALTER COLUMN degraded_threshold_millis SET DEFAULT 2500;

ALTER TABLE managed_website
    ADD CONSTRAINT ck_managed_website_degraded_threshold
        CHECK (degraded_threshold_millis >= 200 AND degraded_threshold_millis <= timeout_millis);
