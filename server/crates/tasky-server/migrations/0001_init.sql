-- IDs are stored as hyphenated UUID text; timestamps as unix epoch milliseconds.

CREATE TABLE households (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    -- Last change sequence number handed out in this household.
    seq         INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

CREATE TABLE users (
    id             TEXT PRIMARY KEY,
    username       TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name   TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    created_at     INTEGER NOT NULL
);

CREATE TABLE memberships (
    household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role          TEXT NOT NULL,
    joined_at     INTEGER NOT NULL,
    PRIMARY KEY (household_id, user_id)
);
CREATE INDEX memberships_user ON memberships(user_id);

CREATE TABLE sessions (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    TEXT NOT NULL UNIQUE,
    device_name   TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE invites (
    code_hash     TEXT PRIMARY KEY,
    household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    created_by    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at    INTEGER NOT NULL,
    expires_at    INTEGER NOT NULL,
    used_at       INTEGER,
    used_by       TEXT REFERENCES users(id) ON DELETE SET NULL
);

-- All synced entities, stored generically. `data` is the JSON of the matching
-- tasky_api::*Data struct; `clocks` maps each field name to the HLC that last wrote it.
CREATE TABLE entities (
    id            TEXT PRIMARY KEY,
    household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL,
    data          TEXT NOT NULL,
    clocks        TEXT NOT NULL,
    -- HLC of the latest create/delete; decides whether the entity exists (LWW).
    alive_hlc     TEXT NOT NULL,
    deleted       INTEGER NOT NULL DEFAULT 0,
    -- Normalized name (items only), for deduplication.
    norm_name     TEXT,
    seq           INTEGER NOT NULL,
    updated_by    TEXT,
    updated_at    INTEGER NOT NULL
);
CREATE INDEX entities_household_seq ON entities(household_id, seq);
CREATE INDEX entities_item_name ON entities(household_id, norm_name) WHERE kind = 'item' AND deleted = 0;

-- Items created under an ID whose name already existed get merged into the existing item.
CREATE TABLE item_aliases (
    alias_id      TEXT PRIMARY KEY,
    household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    target_id     TEXT NOT NULL,
    seq           INTEGER NOT NULL
);
CREATE INDEX item_aliases_household_seq ON item_aliases(household_id, seq);
