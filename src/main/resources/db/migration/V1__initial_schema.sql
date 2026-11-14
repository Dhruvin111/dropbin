-- =============================================================================
-- Flyway Database Migration: V1 Initial Schema
-- =============================================================================

CREATE TABLE IF NOT EXISTS pastes (
    id               VARCHAR(255) PRIMARY KEY,
    content          TEXT         NOT NULL,
    title            VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 1,
    expires_at       TIMESTAMPTZ  NOT NULL,
    syntax_language  VARCHAR(50)  DEFAULT 'plaintext',
    read_only_key    VARCHAR(64)  UNIQUE
);

-- Fast expiry-based lookups and bulk purge
CREATE INDEX IF NOT EXISTS idx_pastes_expires_at ON pastes(expires_at);
CREATE INDEX IF NOT EXISTS idx_pastes_read_only_key ON pastes(read_only_key);

CREATE TABLE IF NOT EXISTS bin_files (
    id                VARCHAR(64)  PRIMARY KEY,
    paste_id          VARCHAR(255) NOT NULL REFERENCES pastes(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    file_size         BIGINT       NOT NULL,
    storage_path      VARCHAR(512) NOT NULL,
    file_category     VARCHAR(32)  NOT NULL,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Fast lookups by parent paste ID
CREATE INDEX IF NOT EXISTS idx_bin_files_paste_id ON bin_files(paste_id);
