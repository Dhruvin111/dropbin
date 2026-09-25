-- Run once on DB creation (docker-compose maps this automatically)
-- Hibernate's ddl-auto=update will also handle this, but explicit SQL is safer for prod.

CREATE TABLE IF NOT EXISTS pastes (
    id               VARCHAR(255) PRIMARY KEY,
    content          TEXT         NOT NULL,
    title            VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 1,
    expires_at       TIMESTAMPTZ  NOT NULL,
    syntax_language  VARCHAR(50)  DEFAULT 'plaintext'
);

-- Fast expiry-based lookups and bulk deletes
CREATE INDEX IF NOT EXISTS idx_pastes_expires_at ON pastes(expires_at);

CREATE TABLE IF NOT EXISTS bin_files (
    id                VARCHAR(64)  PRIMARY KEY,
    paste_id          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    file_size         BIGINT       NOT NULL,
    storage_path      VARCHAR(512) NOT NULL,
    file_category     VARCHAR(32)  NOT NULL,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bin_files_paste_id ON bin_files(paste_id);

-- Optional: pg_cron job (if pg_cron extension is installed on your server)
-- This is an alternative to Spring @Scheduled — runs entirely inside Postgres.
-- Uncomment if you have pg_cron available:
--
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- SELECT cron.schedule('delete-expired-pastes', '* * * * *',
--   $$DELETE FROM pastes WHERE expires_at < NOW()$$);
