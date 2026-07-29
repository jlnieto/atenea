CREATE TABLE phase7_items (
    id BIGINT PRIMARY KEY,
    code VARCHAR(96) NOT NULL UNIQUE,
    generation INTEGER NOT NULL,
    checksum VARCHAR(160) NOT NULL
);
