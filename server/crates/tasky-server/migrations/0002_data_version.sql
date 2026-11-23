-- Version of the household's synced data, for one-time upgrades the server runs at startup
-- (see src/categories.rs). 0 = from before categories.
ALTER TABLE households ADD COLUMN data_version INTEGER NOT NULL DEFAULT 0;
