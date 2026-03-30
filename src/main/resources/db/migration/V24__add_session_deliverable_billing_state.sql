ALTER TABLE session_deliverable
    ADD COLUMN billing_status VARCHAR(32),
    ADD COLUMN billing_reference VARCHAR(160),
    ADD COLUMN billed_at TIMESTAMP WITH TIME ZONE;
