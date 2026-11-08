# Tasky API (v1)

The server's HTTP + WebSocket contract, written for client authors (the Android app).
The Rust types in [`server/crates/tasky-api/src/lib.rs`](../server/crates/tasky-api/src/lib.rs) are the source of truth.
Kotlin models generated from them are in [`api/kotlin/TaskyApi.kt`](../api/kotlin/TaskyApi.kt) (regenerate with `scripts/gen-api.sh`).

## Conventions

- Base path: `https://<host>/api/v1`. JSON request and response bodies, with `snake_case` keys.
- **Auth:** send `Authorization: Bearer <session_token>`. Tokens never expire on their own. They end on logout, when the session is revoked, or when the password changes (which signs out every other session).
- **IDs** are UUID strings. Clients create the IDs of synced entities themselves (UUIDv7 recommended).
- **Timestamps** are Unix epoch milliseconds (`*_ms`, type `Long`).
- **Tagged unions** are always `{"type": "<variant>", "value": <payload>}`. This matches kotlinx.serialization's default polymorphic encoding (`classDiscriminator = "type"`), so the generated sealed classes decode as-is.
- Optional fields may be left out or sent as `null`. Use `Json { ignoreUnknownKeys = true }` on the client so it keeps working when new fields are added.
- **Errors:** every non-2xx response has the body `{"error": ErrorCode, "message": "human readable"}`.

| HTTP | `error` | When |
|---|---|---|
| 400 | `bad_request`, `validation` | Malformed or invalid input |
| 401 | `unauthorized` | Missing, unknown or revoked session token → go to sign-in |
| 401 | `invalid_credentials` | Wrong username/password |
| 403 | `invalid_setup_token` | Wrong household-creation token |
| 403 | `invalid_invite` | Invite code unknown, used or expired |
| 404 | `not_found` | Includes households you're not a member of |
| 409 | `username_taken` | |
| 500 | `internal` | |

## Endpoints

| Method & path | Auth | Request → Response |
|---|---|---|
| `GET /healthz` (no `/api/v1` prefix) | – | `ok` |
| `GET /server-info` | – | → `ServerInfo` (use to validate a server URL) |
| `POST /auth/create-household` | – | `CreateHouseholdRequest` → `AuthResponse` |
| `POST /auth/join` | – | `JoinHouseholdRequest` → `AuthResponse` |
| `POST /auth/login` | – | `LoginRequest` → `AuthResponse` |
| `POST /auth/logout` | ✓ | → empty 200 |
| `GET /me` | ✓ | → `MeResponse` |
| `PATCH /me` | ✓ | `UpdateMeRequest` → `MeResponse` |
| `POST /me/password` | ✓ | `ChangePasswordRequest` → 204 |
| `GET /sessions` | ✓ | → `[Session]` |
| `DELETE /sessions/{id}` | ✓ | → 204 (own sessions only) |
| `GET /households/{hid}` | member | → `HouseholdDetails` (name + members) |
| `PATCH /households/{hid}` | member | `UpdateHouseholdRequest` → `HouseholdDetails` |
| `POST /households/{hid}/invites` | member | (empty body) → `InviteResponse` |
| `POST /households/{hid}/sync` | member | `SyncRequest` → `SyncResponse` |
| `GET /households/{hid}/live` | member | WebSocket upgrade, streams `LiveEvent` |

Every member can edit everything in their household. There are no per-entity permissions.

### Onboarding

1. **Create a household:** the server writes a token to its log at startup (`household creation token (single use): tsk_…`). It works once; after use a new one is logged. If the admin set `TASKY_SETUP_TOKEN`, that fixed token works every time instead. The user enters server URL, token, household name, username, display name and password.
2. **Invite others:** any member calls `POST /households/{hid}/invites` and gets a code like `K7QM-2XRD`, valid for 48h and usable once. Show it as text and as a QR code. A suggested QR payload is `tasky://join?server=<url>&code=<code>`.
3. **Join:** the new person enters server URL, invite code, username, display name and password. Codes ignore case, dashes and spaces, and treat look-alike characters (O/0, I/L/1) as the same.
4. **Log in** on another device with username and password.

Rules:
- Usernames: 3–32 characters from `[A-Za-z0-9._-]`, case-insensitively unique.
- Passwords: at least 8 characters.
- Display names: 1–64 characters.

`AuthResponse.households` lists the user's households. v1 has exactly one household per user.

## Data model (synced entities)

| Kind | Fields | Notes |
|---|---|---|
| `item` | `name`, `category?`, `default_unit?` | Household catalog of groceries. Names are unique ignoring case and whitespace (see aliases). |
| `list` | `name`, `kind?` (`grocery`/`checklist`/`note`), `body?` | Every new household gets a "Groceries" grocery list. A missing `kind` means `grocery`. The kind is set on create and can't be changed. `body` is the text of a `note`. |
| `list_entry` | `list_id`, `text`, `checked`, `checked_by?` | One line of a `checklist` list. `checked_by` is set by the server. |
| `contribution` | `list_id`, `item_id`, `amount?`, `unit?`, `plan_entry_id?`, `note?`, `purchased`, `purchased_by?`, `archived` | One reason an item is on a list. |
| `meal` | `name`, `default_servings`, `notes?`, `url?` | Known-meals catalog. Recipes are out of scope for now. |
| `meal_ingredient` | `meal_id`, `item_id`, `amount?`, `unit?`, `note?` | Ingredients of a meal. |
| `plan_entry` | `date` (`YYYY-MM-DD`), `slot` (`breakfast`/`lunch`/`dinner`), `owner_user_id?`, `meal_id?`, `free_text?`, `servings?`, `note?` | `owner_user_id = null` means a household meal. Needs `meal_id` or `free_text`. |
| `expense` | `description`, `amount_minor`, `currency`, `date`, `paid_by`, `split_among`, `category?`, `note?`, `kind?` (`expense`/`settlement`) | One entry of the expense book. Money is an integer in the currency's minor unit (cents). A missing `kind` means `expense`; it's set on create and can't be changed. |

Validation limits:
- **Names:** 1–200 characters, and not blank.
- **Checklist entry text:** 1–1000 characters, and not blank. **Note body:** ≤100000 characters.
- **Category:** ≤64 characters.
- **Unit:** ≤32 characters.
- **Notes:** ≤1000 characters (meal notes ≤10000).
- **URL:** ≤2000 characters.
- **free_text:** ≤200 characters.
- **Amount:** finite, 0 to 1e9.
- **Servings:** 1–1000.
- **Expenses:** `description` 1–200 characters and not blank; `amount_minor` 1 to 10¹²; `currency` exactly three uppercase letters (ISO 4217); `split_among` 1–100 members without duplicates. A settlement has exactly one entry in `split_among`, and it isn't `paid_by`.
- **References:** must point to an entity of the right kind in the same household. `owner_user_id`, `paid_by` and everyone in `split_among` must be members.

### Shopping list semantics (computed on the client)

A **list line** is `(list_id, item_id)`. Its contributions are all `contribution`s with that pair where `deleted = false` and `archived = false`.

- **Open amount:** the sum of `amount` over contributions with `purchased = false`, grouped by unit dimension:
  - **Mass:** `g`, `kg` (base `g`).
  - **Volume:** `ml`, `l`, `tsp` (5 ml), `tbsp` (15 ml) (base `ml`).
  - **Count:** `pcs`.
  - **Anything else** (`can`, `pinch`, `bunch`…) is its own group, keyed by the exact unit text.
  - Show the groups side by side, e.g. `750 g + 2 pcs`. Convert to a nicer unit for display (`1.2 kg`). Contributions with no amount count toward the line but add no quantity.
- **Line is checked** when it has contributions and all of them are `purchased`. Checked lines go to the bottom.
- **Check a line:** send `set_contribution / purchased = true` for every currently unpurchased contribution of that line. If someone else adds a contribution at the same moment, the line reappears with only the new amount, which is the intended behaviour.
- **Uncheck a line:** send `purchased = false` for the line's purchased (non-archived) contributions.
- **Clear checked:** send `archived = true` for all purchased contributions of the list.
- **Already have it at home:** the user just checks the line off. There's no pantry tracking.
- **Sort:** by item `category`, then by name. There's no manual ordering in v1.
- **Show who checked:** `purchased_by` (set by the server).

### Checklists and notes

- **Checklist** (`kind = checklist`): its lines are `list_entry` records with free text, not catalog items. They have no amounts, they don't appear in grocery autocomplete, and they're never merged by name.
  - Check or uncheck an entry with `set_list_entry / checked`. The server records who checked it in `checked_by`.
  - "Clear checked" deletes the checked entries.
  - Order: unchecked first, in creation order (UUIDv7 ids sort by time), then checked.
- **Note** (`kind = note`): the text lives in the list's `body`, set with `set_list / body`. Edits are last-writer-wins on the whole text, so when two people edit the same note at the same moment, the later save wins. Save while typing with a debounce of about 1 s, and on leaving the screen.
- **Compatibility:** apps from before list kinds can't decode `list_entry` and treat every list as a grocery list. Update all phones together with the server.

### Meal planning (computed on the client)

- Weeks start on **Monday**.
- **Default servings:** `servings ?: (owner_user_id == null ? household member count : 1)`.
- **Add to list** (for one plan entry or a whole week): for each `meal_ingredient` of the entry's meal, create a contribution with:
  - `amount = ingredient.amount * servings / meal.default_servings`,
  - `unit = ingredient.unit ?: item.default_unit`,
  - `plan_entry_id = entry.id`.

  Personal meals go on the shared list too. Let the user untick ingredients they already have before adding.
- **Contribution IDs from plan entries are deterministic:**
  `uuidv5(CONTRIBUTION_NAMESPACE, "<plan_entry_id>:<meal_ingredient_id>")`, with `CONTRIBUTION_NAMESPACE = 6d1f3c2a-9b8e-4f6d-a1c3-5e7b9d2f4a10`.
  So two people adding the same entry produce the same contribution instead of doubling it.
  Test vector: entry `00000000-0000-0000-0000-000000000001`, ingredient `…0002` → `79f48edd-396a-5e27-b339-9c3997d57a61`.
- **Deleting a plan entry:** also delete its contributions that are not yet purchased.

### Expenses (computed on the client)

The server only stores entries; balances are derived from them. Every client must use these exact integer rules so all phones show the same numbers, to the cent.

- **Shares:** an entry's `amount_minor` is split equally between the distinct ids in `split_among`. Sort the ids ascending (as strings); each gets `amount / n`, and the first `amount % n` of them get one more. Shares always add up to the amount.
  Example: 1000 between `c`, `a`, `b` → `a` 334, `b` 333, `c` 333.
- **Balances** are kept per `currency` and never converted. For each entry, `paid_by` gets `+amount_minor` and each person in `split_among` gets `−share`. Positive means the household owes them; the balances of a currency sum to zero.
- **Settlements** (`kind = settlement`): `paid_by` paid `split_among[0]` back. They use the same balance rule, which is what moves debts back to zero, but they don't count as spending.
- **"The whole household"** is stored as the list of members at the time of entry, not as a flag, so people who join later don't owe old bills.
- **Suggested repayments** (optional, for a "settle up" screen): repeatedly let the member who owes most pay the member who is owed most, `min` of the two amounts, breaking ties by id.
- **Spending** for a period: the sum of `amount_minor` of non-settlement entries in it, per currency.
- **Editing a settlement's people:** the server validates after every op, so swapping payer and recipient can't be done field by field (the intermediate state pays someone back to themselves). Delete the entry and create a new one instead.
- **Compatibility:** apps from before expenses can't decode `expense` entities. Update all phones together with the server.

## Sync protocol

Design goals:
- No position-based identity: every change targets an entity by UUID.
- Field-level last-writer-wins, so concurrent edits to different fields never conflict.
- Offline-first clients: the result doesn't depend on the order ops reach the server.

### Ops

An op changes one entity:

```json
{
  "entity_id": "0193a4f1-c2d0-7000-8000-000000000001",
  "hlc": "0193a4f1c2d0-0000-7f3a9c01e5b24d88",
  "action": {"type": "create", "value": {"type": "item", "value": {"name": "Milk", "category": "Dairy", "default_unit": "l"}}}
}
```

| `action.type` | `action.value` | Effect |
|---|---|---|
| `create` | `EntityData` (`{"type": kind, "value": {...all fields}}`) | Creates the entity. If it exists, each field is set by LWW. Brings back a deleted entity if the create is newer than the delete. |
| `delete` | kind, e.g. `"contribution"` | Tombstones the entity, if newer than its last create/delete. |
| `set_item` | `{"type": "name" \| "category" \| "default_unit", "value": …}` | Sets one field (LWW). |
| `set_list` | `name`, `body` | |
| `set_list_entry` | `list_id`, `text`, `checked` | `checked_by` is set by the server. |
| `set_contribution` | `list_id`, `amount`, `unit`, `note`, `purchased`, `archived` | `purchased_by` is set by the server. |
| `set_meal` | `name`, `default_servings`, `notes`, `url` | |
| `set_meal_ingredient` | `item_id`, `amount`, `unit`, `note` | |
| `set_plan_entry` | `date`, `slot`, `owner_user_id`, `meal_id`, `free_text`, `servings`, `note` | |
| `set_expense` | `description`, `amount_minor`, `currency`, `date`, `paid_by`, `split_among`, `category`, `note` | `split_among` is replaced as a whole. |

To clear an optional field, send `"value": null`.

Set ops on a deleted entity are stored but stay hidden; they show up again if the entity is brought back.

### HLC (hybrid logical clock)

Format: `<12 lowercase hex: unix ms>-<4 lowercase hex: counter>-<node>`. `node` is 1–32 characters of `[a-z0-9]`, random per app install, e.g. `0193a4f1c2d0-0000-7f3a9c01e5b24d88`. Because the first two parts are fixed width, comparing the strings compares the timestamps.

Generate a timestamp for each local edit:
```
now = system ms + serverOffset          // serverOffset ≈ server_time_ms - local time, refreshed on every sync
if now > last.ms: last = (now, 0)
else:             last = (last.ms, last.counter + 1)
hlc = format(last.ms, last.counter, node)
```
Ops in a single user action, like "check line" or a create, can share one HLC per entity. Make sure each new edit to the same field gets a larger HLC than the previous one.

The server rejects ops more than 5 minutes ahead of its clock (`clock_skew`). Using the server offset above prevents that.

### `POST /households/{hid}/sync`

```json
{"since": 41, "ops": [ /* outbox, oldest first */ ], "limit": 500}
```

The server applies `ops` in order, all in one transaction, then returns every change with `seq > since`:

```json
{
  "changes": [{"id": "…", "seq": 42, "deleted": false, "data": {"type": "item", "value": {…}}, "updated_by": "…", "updated_at_ms": 1794054187732}],
  "aliases": [{"alias_id": "…", "target_id": "…", "seq": 43}],
  "rejected": [{"index": 3, "entity_id": "…", "code": "validation", "message": "name must not be empty"}],
  "cursor": 43,
  "has_more": false,
  "full_resync": false,
  "server_time_ms": 1794054187733
}
```

- `changes` hold the **full current state** of each entity, oldest first, including the effects of your own ops. Upsert each into the local store by `id`, and mark it deleted when `deleted` is true. A full sync (`since = 0`) never includes tombstones.
- Ops that aren't in `rejected` were applied, or ignored because they were stale. Either way the server has them, so **drop the whole sent batch from the outbox**. Rejected ops are dropped too. The next state from the server corrects any optimistic local value. Log them, and show a message for `validation` / `invalid_reference`.
- `has_more`: call again right away with `since = cursor`, and no ops needed.
- `full_resync`: the server's state is older than your cursor (e.g. it was restored from a backup). Wipe all synced local data but **keep the outbox**, then apply `changes` as a fresh full sync.
- `aliases`: someone else had already created an item with the same name. The server merged your item into theirs. Locally, replace `alias_id` with `target_id` in every `item_id` and delete the alias item. The server also maps alias IDs in any later ops, so queued ops that still use the old ID are fine.

`rejected.code` values: `invalid_hlc`, `clock_skew`, `not_found` (set op for an unknown entity), `kind_mismatch`, `invalid_reference`, `validation`.

Limits: 5000 ops per request. `limit` defaults to 500, max 2000.

### Recommended client algorithm

- **Local store** (Room) is the only thing the UI reads.
- **Outbox:** a table of pending ops, oldest first. Every user edit:
  1. updates the local entity optimistically,
  2. appends the op(s) to the outbox,
  3. schedules a sync.
- **Applying server state** without losing pending edits: after storing `changes`, re-apply the outbox ops still pending (sent after this request started) on top. A simple approach is to keep a "server state" row plus an "overlay" from pending ops, or to re-run the pending ops locally after each sync.
- **Sync triggers:**
  - app start and resume,
  - after local edits (debounce about 300 ms),
  - on `changed` live events with `cursor > local cursor`,
  - when the network comes back (WorkManager with a network constraint),
  - a slow periodic fallback while the app is open.
- **Live updates:** keep `GET /households/{hid}/live` open while the app is in the foreground, using OkHttp WebSocket with the `Authorization` header. Reconnect with backoff.

### Live events (WebSocket)

Text frames containing `LiveEvent`:

```json
{"kind": "hello", "cursor": 42}
{"kind": "changed", "cursor": 57}
{"kind": "household_changed", "cursor": 57}
```

- `hello` arrives once, right after connecting.
- `changed`: synced entities changed. Sync if `cursor` is greater than yours. Events don't carry data.
- `household_changed`: the name or member list changed. Refetch `GET /households/{hid}`.

The server pings every 30 seconds. Client messages are ignored.

## Recommended unit codes

`g`, `kg`, `ml`, `l`, `tsp`, `tbsp`, `pcs`. Anything else is stored as free text and not converted. Offer the codes in a picker and allow custom text.
