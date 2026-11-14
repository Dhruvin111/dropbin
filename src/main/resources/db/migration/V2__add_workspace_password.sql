-- =============================================================================
-- Flyway Database Migration: V2 Add Workspace Password Lock
-- =============================================================================

ALTER TABLE pastes ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
