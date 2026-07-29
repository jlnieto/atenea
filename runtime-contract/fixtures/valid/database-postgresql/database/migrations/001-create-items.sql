CREATE TABLE phase7_items (
    id BIGINT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    generation INTEGER NOT NULL,
    checksum TEXT NOT NULL
);
