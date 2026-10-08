-- Technical reports are private operator-owned data, not WorkSession attachments.
-- Keep the exact uploaded bytes so receipts/downloads can be verified by SHA-256.
CREATE TABLE mobile_diagnostic_report (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE RESTRICT,
    received_at TIMESTAMPTZ NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    app_version_name VARCHAR(80) NOT NULL,
    app_version_code INTEGER NOT NULL CHECK (app_version_code > 0),
    device_model VARCHAR(150) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 4194304),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    report_bytes BYTEA NOT NULL,
    CHECK (octet_length(report_bytes) = size_bytes),
    UNIQUE (operator_id, sha256)
);
CREATE INDEX mobile_diagnostic_report_operator_latest
    ON mobile_diagnostic_report(operator_id, received_at DESC, id DESC);
COMMENT ON TABLE mobile_diagnostic_report IS
    'Authenticated Android diagnostic evidence. No WorkSession, AgentRun, worker dispatch or client-selected host path. Byte-identical retries return the original receipt.';
