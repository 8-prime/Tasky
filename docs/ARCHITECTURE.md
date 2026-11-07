# Tasky architecture

Tasky is a shared weekly meal planner and shopping list for a household. It has a self-hosted Rust server and an Android app.

## Product decisions

| Topic | Decision |
|---|---|
| Households | A household has any number of members. The schema allows a user in several households; v1 assumes one. |
| Permissions | Every member can see and edit everything in the household. |
| Creating a household | Needs a token from the server log: random and single-use (rotated after each use), or fixed via `TASKY_SETUP_TOKEN`. |
| Joining | Invite codes created by any member: `XXXX-XXXX`, valid for 48h, single-use. |
| Meal plan | Per day and slot (breakfast/lunch/dinner). Each entry is for one person or for the whole household. Weeks start on Monday. |
| Meals | A household catalog of known meals with optional ingredients (item + amount + unit). Recipes come later. |
| Lists | Shared lists of three kinds: grocery ("Groceries" by default), checklist and text note. A line is an item, and its amount is the sum of its contributions, so amounts from different meals add up. Personal meals feed the shared list too. |
| Adding to the list | Explicit: per plan entry or for a whole week, and the user can untick what they already have. No pantry tracking; people check off what they already have at home. |
| Units | Metric. Converted within a dimension (g/kg, ml/l/tsp/tbsp, pcs); different dimensions are shown side by side. |
| Language | English. All strings live in Android resources. |

## Sync design

The core requirement is that concurrent editing of the list works. (Keep's position-based checking breaks when two people edit at once.)

- **Stable IDs.** Every entity has a UUID created on the client. Nothing is identified by its position.
- **Field-level last-writer-wins** on hybrid logical clocks (HLC). Concurrent edits to different fields both survive. For the same field, the later edit wins no matter which reaches the server first.
- **Existence is an LWW register.** Deletes leave tombstones, a newer create brings an entity back, and field writes under a tombstone are kept but hidden. This makes the final state independent of arrival order, which `final_state_is_independent_of_arrival_order` checks with randomized op orders.
- **The server orders changes.** Every change gets a per-household `seq`. Clients send their outbox and pull `seq > cursor` in one request. The WebSocket only signals that something changed (`changed` + cursor), so a missed event costs nothing: the next sync catches up.
- **Additive amounts without counters.** Amounts are never edited concurrently: each meal adds its own `contribution` entity, and the client sums them. Checking off a line marks the contributions that exist at that moment, so pasta added a second later stays open.
- **Duplicates:**
  - Contributions from a planned meal use deterministic UUIDv5 IDs, so adding the same entry twice does nothing.
  - Items with the same name (ignoring case and whitespace) are merged on the server through aliases, which clients apply.
- **Clock safety.** The server rejects HLCs more than 5 minutes ahead of it, and clients adjust their clock using `server_time_ms`.

A CRDT library (Automerge/Yrs) was deliberately not used: this data is records with fields, not text documents, and an ordering server makes everything simpler.

## Server

- Rust 2024, axum 0.8, tokio, sqlx 0.9 + SQLite (WAL), argon2id passwords, and sha256-hashed opaque session tokens.
- SQLite with one write connection (no write contention) plus a read pool. Entities are stored generically (`entities` table: JSON `data` + per-field `clocks`), and each write is validated against the typed structs in `tasky-api`.
- `server/crates/tasky-api`: wire types, the single source of truth. Kotlin models are generated with typeshare (`scripts/gen-api.sh` → `api/kotlin/TaskyApi.kt`).
- `server/crates/tasky-server`: the binary plus a library, so integration tests start the real app over TCP.

```
server/crates/tasky-server/src/
  main.rs         CLI/env config, tracing, graceful shutdown
  lib.rs          AppState + build()
  routes/         auth, account (me/sessions), household (+invites), sync
  sync.rs         op application (LWW), validation, change feed
  hlc.rs          HLC validation
  live.rs         per-household broadcast + WebSocket
  auth.rs         passwords, sessions, AuthUser extractor
  setup_token.rs  household creation token
  invite.rs       invite codes
migrations/       SQLite schema
tests/            auth, sync and live integration tests
```

## Android (next)

Native Kotlin + Jetpack Compose (Material 3), Room as the offline-first local store, an outbox of ops, OkHttp for REST and WebSocket, kotlinx.serialization with the generated models, and WorkManager for background sync. No Firebase. Distributed as an APK (GitHub releases / Obtainium). See [API.md](API.md) for the protocol and the logic the client has to implement.
