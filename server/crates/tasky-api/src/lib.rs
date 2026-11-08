//! Wire types shared between the Tasky server and its clients.
//!
//! Every type here is annotated with `#[typeshare]`, so Kotlin models for the
//! Android app are generated straight from this file (see `/api/README.md`).
//!
//! Conventions:
//! - All JSON keys are `snake_case`.
//! - IDs are UUID strings. Clients generate the IDs of synced entities themselves (UUIDv7 recommended).
//! - Tagged unions are always encoded as `{"type": "<variant>", "value": <payload>}`.
//!   This matches kotlinx.serialization's default polymorphic encoding.
//! - Timestamps are Unix epoch milliseconds (`Millis`).

use serde::{Deserialize, Serialize};
use typeshare::typeshare;
use uuid::Uuid;

/// Unix epoch milliseconds. (Mapped to `Long` in Kotlin.)
pub type Millis = i64;
/// Monotonic per-household change sequence number. (Mapped to `Long` in Kotlin.)
pub type Seq = i64;
/// Money in the currency's minor unit, e.g. cents for EUR. (Mapped to `Long` in Kotlin.)
pub type MinorUnits = i64;

/// Version of the wire protocol. Bumped on breaking changes.
pub const API_VERSION: i32 = 1;

/// Namespace for deterministic (UUIDv5) IDs of contributions that come from a
/// planned meal: `uuidv5(CONTRIBUTION_NAMESPACE, "<plan_entry_id>:<meal_ingredient_id>")`.
/// If two people add the same planned meal to the list at once, they get the same IDs,
/// so the ingredients aren't counted twice.
pub const CONTRIBUTION_NAMESPACE: Uuid = Uuid::from_u128(0x6d1f_3c2a_9b8e_4f6d_a1c3_5e7b_9d2f_4a10);

// ───────────────────────────── Errors ─────────────────────────────

/// Body of every non-2xx response.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ErrorBody {
    pub error: ErrorCode,
    pub message: String,
}

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    BadRequest,
    Validation,
    Unauthorized,
    InvalidCredentials,
    InvalidSetupToken,
    InvalidInvite,
    Forbidden,
    NotFound,
    UsernameTaken,
    Internal,
}

// ───────────────────────────── Server ─────────────────────────────

/// `GET /api/v1/server-info` (no auth). Lets the app check a server URL before signing in.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ServerInfo {
    pub name: String,
    pub version: String,
    pub api_version: i32,
    pub server_time_ms: Millis,
}

// ───────────────────────────── Auth ─────────────────────────────

/// `POST /api/v1/auth/create-household`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CreateHouseholdRequest {
    /// The setup token printed in the server log.
    pub setup_token: String,
    pub household_name: String,
    pub username: String,
    pub display_name: String,
    pub password: String,
    pub device_name: String,
}

/// `POST /api/v1/auth/join`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JoinHouseholdRequest {
    /// Invite code, e.g. `K7QM-2XRD` (case-insensitive; dashes/spaces ignored).
    pub invite_code: String,
    pub username: String,
    pub display_name: String,
    pub password: String,
    pub device_name: String,
}

/// `POST /api/v1/auth/login`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
    pub device_name: String,
}

/// Returned by create-household, join and login.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AuthResponse {
    /// Bearer token: send it as `Authorization: Bearer <token>`.
    pub session_token: String,
    pub user: User,
    pub households: Vec<Household>,
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct User {
    pub id: Uuid,
    pub username: String,
    pub display_name: String,
}

/// `GET /api/v1/me`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MeResponse {
    pub user: User,
    pub households: Vec<Household>,
}

/// `PATCH /api/v1/me`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct UpdateMeRequest {
    pub display_name: String,
}

/// `POST /api/v1/me/password`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ChangePasswordRequest {
    pub current_password: String,
    pub new_password: String,
}

/// An item from `GET /api/v1/sessions`.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Session {
    pub id: Uuid,
    pub device_name: String,
    pub created_at_ms: Millis,
    pub last_seen_at_ms: Millis,
    /// True for the session that made this request.
    pub current: bool,
}

// ───────────────────────────── Household ─────────────────────────────

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Household {
    pub id: Uuid,
    pub name: String,
}

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Owner,
    Member,
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Member {
    pub user_id: Uuid,
    pub username: String,
    pub display_name: String,
    pub role: Role,
    pub joined_at_ms: Millis,
}

/// `GET /api/v1/households/{household_id}`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HouseholdDetails {
    pub household: Household,
    pub members: Vec<Member>,
}

/// `PATCH /api/v1/households/{household_id}`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct UpdateHouseholdRequest {
    pub name: String,
}

/// `POST /api/v1/households/{household_id}/invites`
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InviteResponse {
    /// Single-use code, formatted `XXXX-XXXX`.
    pub code: String,
    pub expires_at_ms: Millis,
}

// ───────────────────────────── Synced entities ─────────────────────────────

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum EntityKind {
    Item,
    List,
    Contribution,
    Meal,
    MealIngredient,
    PlanEntry,
    ListEntry,
    Expense,
}

impl EntityKind {
    pub fn as_str(self) -> &'static str {
        match self {
            EntityKind::Item => "item",
            EntityKind::List => "list",
            EntityKind::Contribution => "contribution",
            EntityKind::Meal => "meal",
            EntityKind::MealIngredient => "meal_ingredient",
            EntityKind::PlanEntry => "plan_entry",
            EntityKind::ListEntry => "list_entry",
            EntityKind::Expense => "expense",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "item" => EntityKind::Item,
            "list" => EntityKind::List,
            "contribution" => EntityKind::Contribution,
            "meal" => EntityKind::Meal,
            "meal_ingredient" => EntityKind::MealIngredient,
            "plan_entry" => EntityKind::PlanEntry,
            "list_entry" => EntityKind::ListEntry,
            "expense" => EntityKind::Expense,
            _ => return None,
        })
    }
}

/// A grocery item in the household catalog ("Tomatoes", "Milk").
/// The server keeps at most one live item per name, compared case- and whitespace-insensitively.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ItemData {
    pub name: String,
    /// Free text used for grouping and sorting, e.g. "Produce" or "Dairy".
    pub category: Option<String>,
    /// Unit suggested when this item is added (see the recommended unit codes in docs/API.md).
    pub default_unit: Option<String>,
}

/// What a list holds. The kind is chosen when the list is created and can't be changed later.
#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ListKind {
    /// Shopping list: lines are catalog items with amounts summed from contributions.
    Grocery,
    /// Plain to-do style checklist made of `list_entry`s.
    Checklist,
    /// Free-text note stored in `body`.
    Note,
}

/// A shared list, e.g. "Groceries". Each new household gets one grocery list automatically.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ListData {
    pub name: String,
    /// Missing or null means `grocery` (lists created before kinds existed).
    pub kind: Option<ListKind>,
    /// Text of a `note` list. Unused for other kinds.
    pub body: Option<String>,
}

/// One line of a `checklist` list.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ListEntryData {
    pub list_id: Uuid,
    pub text: String,
    pub checked: bool,
    /// Set by the server to whoever last set `checked = true`. Client values are ignored.
    pub checked_by: Option<Uuid>,
}

/// One reason an item is on a list. A list line `(list_id, item_id)` is the sum of its
/// contributions that are not purchased and not archived.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ContributionData {
    pub list_id: Uuid,
    pub item_id: Uuid,
    pub amount: Option<f64>,
    pub unit: Option<String>,
    /// Set when this came from a planned meal; null for items added by hand.
    pub plan_entry_id: Option<Uuid>,
    pub note: Option<String>,
    pub purchased: bool,
    /// Set by the server to whoever last set `purchased = true`. Client values are ignored.
    pub purchased_by: Option<Uuid>,
    /// True once removed by "clear checked". Hidden from the list.
    pub archived: bool,
}

/// A known meal in the household catalog.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MealData {
    pub name: String,
    /// Number of servings the ingredient amounts are written for.
    pub default_servings: i32,
    pub notes: Option<String>,
    pub url: Option<String>,
}

/// One ingredient of a meal.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MealIngredientData {
    pub meal_id: Uuid,
    pub item_id: Uuid,
    pub amount: Option<f64>,
    pub unit: Option<String>,
    pub note: Option<String>,
}

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MealSlot {
    Breakfast,
    Lunch,
    Dinner,
}

/// A meal planned for a given day and slot.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PlanEntryData {
    /// Calendar date `YYYY-MM-DD`. Weeks start on Monday.
    pub date: String,
    pub slot: MealSlot,
    /// The person this meal is for; null means a household meal for everyone.
    pub owner_user_id: Option<Uuid>,
    /// A meal from the catalog. At least one of `meal_id` / `free_text` must be set.
    pub meal_id: Option<Uuid>,
    /// Ad-hoc meal name when not using the catalog ("Leftovers").
    pub free_text: Option<String>,
    /// Servings to cook. Defaults to the household size (household meals) or 1 (personal).
    pub servings: Option<i32>,
    pub note: Option<String>,
}

/// What an expense record is. Set on create; can't be changed later.
#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ExpenseKind {
    /// Money spent for the household (or some of its members).
    Expense,
    /// A repayment between two members: `paid_by` paid `split_among[0]` back.
    Settlement,
}

/// One entry in the household's expense book.
///
/// Balances follow from the entries alone: `paid_by` is credited `amount_minor`, and each
/// member of `split_among` is debited an equal share (see docs/API.md for the exact rounding).
/// A settlement uses the same rule, which is why it moves debt back.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExpenseData {
    /// What it was for, e.g. "Rent" or "Weekly shop".
    pub description: String,
    /// Total paid, in minor units of `currency` (cents for EUR). Greater than zero.
    pub amount_minor: MinorUnits,
    /// ISO 4217 code, uppercase, e.g. "EUR". Balances are kept per currency.
    pub currency: String,
    /// Calendar date `YYYY-MM-DD` of the purchase.
    pub date: String,
    /// The member who paid.
    pub paid_by: Uuid,
    /// Members who share the cost equally. Not empty, no duplicates. "The whole household" is
    /// stored as the list of members at the time, so people who join later don't owe old bills.
    pub split_among: Vec<Uuid>,
    /// Free text for grouping, e.g. "Groceries" or "Utilities".
    pub category: Option<String>,
    pub note: Option<String>,
    /// Missing or null means `expense`.
    pub kind: Option<ExpenseKind>,
}

/// Full data of a synced entity, tagged by kind:
/// `{"type": "item", "value": {"name": "Milk", ...}}`.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum EntityData {
    Item(ItemData),
    List(ListData),
    Contribution(ContributionData),
    Meal(MealData),
    MealIngredient(MealIngredientData),
    PlanEntry(PlanEntryData),
    ListEntry(ListEntryData),
    Expense(ExpenseData),
}

impl EntityData {
    pub fn kind(&self) -> EntityKind {
        match self {
            EntityData::Item(_) => EntityKind::Item,
            EntityData::List(_) => EntityKind::List,
            EntityData::Contribution(_) => EntityKind::Contribution,
            EntityData::Meal(_) => EntityKind::Meal,
            EntityData::MealIngredient(_) => EntityKind::MealIngredient,
            EntityData::PlanEntry(_) => EntityKind::PlanEntry,
            EntityData::ListEntry(_) => EntityKind::ListEntry,
            EntityData::Expense(_) => EntityKind::Expense,
        }
    }
}

// ───────────── Field updates (one field per op, last-writer-wins) ─────────────
// Each variant's `type` matches the field name in the corresponding `*Data` struct.

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum ItemField {
    Name(String),
    Category(Option<String>),
    DefaultUnit(Option<String>),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum ListField {
    Name(String),
    Body(Option<String>),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum ListEntryField {
    ListId(Uuid),
    Text(String),
    /// Check (true) or uncheck (false). The server sets `checked_by` to match.
    Checked(bool),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum ContributionField {
    ListId(Uuid),
    Amount(Option<f64>),
    Unit(Option<String>),
    Note(Option<String>),
    /// Check (true) or uncheck (false). The server sets `purchased_by` to match.
    Purchased(bool),
    Archived(bool),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum MealField {
    Name(String),
    DefaultServings(i32),
    Notes(Option<String>),
    Url(Option<String>),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum MealIngredientField {
    ItemId(Uuid),
    Amount(Option<f64>),
    Unit(Option<String>),
    Note(Option<String>),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum PlanEntryField {
    Date(String),
    Slot(MealSlot),
    OwnerUserId(Option<Uuid>),
    MealId(Option<Uuid>),
    FreeText(Option<String>),
    Servings(Option<i32>),
    Note(Option<String>),
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum ExpenseField {
    Description(String),
    AmountMinor(MinorUnits),
    Currency(String),
    Date(String),
    PaidBy(Uuid),
    SplitAmong(Vec<Uuid>),
    Category(Option<String>),
    Note(Option<String>),
}

// ───────────────────────────── Sync protocol ─────────────────────────────

/// What an op does to its entity.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub enum OpAction {
    /// Create the entity with all its fields. If it already exists, each field is set by
    /// last-writer-wins. If it was deleted, a newer create brings it back.
    Create(EntityData),
    /// Delete the entity. It's kept as a tombstone so other devices learn about the delete.
    Delete(EntityKind),
    SetItem(ItemField),
    SetList(ListField),
    SetContribution(ContributionField),
    SetMeal(MealField),
    SetMealIngredient(MealIngredientField),
    SetPlanEntry(PlanEntryField),
    SetListEntry(ListEntryField),
    SetExpense(ExpenseField),
}

/// A single change made by a client. Applying an op twice has no extra effect,
/// so clients can retry a batch freely.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Op {
    pub entity_id: Uuid,
    /// Hybrid logical clock timestamp, `<12 hex ms>-<4 hex counter>-<node id>`,
    /// e.g. `0193a4f1c2d0-0000-7f3a9c01e5b24d88`. Lowercase. Compared as a string.
    pub hlc: String,
    pub action: OpAction,
}

/// `POST /api/v1/households/{household_id}/sync`: sends local ops and pulls remote changes in one round trip.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SyncRequest {
    /// Last `cursor` this client received (0 for a full sync).
    pub since: Seq,
    /// Ops to apply, in the order they were made locally. May be empty.
    #[serde(default)]
    pub ops: Vec<Op>,
    /// Maximum number of changes to return (default 500, max 2000).
    pub limit: Option<i32>,
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SyncResponse {
    /// Entities changed after `since`, oldest first. Includes the client's own applied ops.
    pub changes: Vec<Entity>,
    /// Items merged into an existing item with the same name since `since`.
    /// Clients must replace `alias_id` with `target_id` wherever it's used.
    pub aliases: Vec<ItemAlias>,
    /// Ops from this request that were refused. Every other op was applied, or ignored as
    /// stale, so drop it from the outbox.
    pub rejected: Vec<RejectedOp>,
    /// Use as `since` in the next request.
    pub cursor: Seq,
    /// True if more changes are waiting: sync again right away.
    pub has_more: bool,
    /// True if `since` was ahead of the server (e.g. the server was restored from a backup).
    /// The response is then a full sync from 0: the client must drop all synced local data
    /// (keeping its pending outbox) before applying `changes`.
    pub full_resync: bool,
    pub server_time_ms: Millis,
}

/// Current state of a synced entity.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Entity {
    pub id: Uuid,
    pub seq: Seq,
    /// Tombstone. Never returned on a full sync (`since = 0`).
    pub deleted: bool,
    pub data: EntityData,
    pub updated_by: Option<Uuid>,
    pub updated_at_ms: Millis,
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ItemAlias {
    pub alias_id: Uuid,
    pub target_id: Uuid,
    pub seq: Seq,
}

#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RejectedOp {
    /// Position of the op in `SyncRequest.ops`.
    pub index: i32,
    pub entity_id: Uuid,
    pub code: RejectCode,
    pub message: String,
}

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RejectCode {
    /// `hlc` is not well formed.
    InvalidHlc,
    /// `hlc` is more than 5 minutes ahead of server time. Correct the clock using `server_time_ms`.
    ClockSkew,
    /// The entity doesn't exist in this household (for set ops).
    NotFound,
    /// The entity exists but has a different kind than the op expects.
    KindMismatch,
    /// A referenced entity or user doesn't exist in this household.
    InvalidReference,
    /// The resulting entity would be invalid (e.g. empty name).
    Validation,
}

// ───────────────────────────── Live updates ─────────────────────────────

/// Message pushed over `GET /api/v1/households/{household_id}/live` (WebSocket).
/// Events are only signals: on `changed`, call sync if `cursor` is newer than yours.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LiveEvent {
    pub kind: LiveEventKind,
    /// The household's latest change sequence number.
    pub cursor: Seq,
}

#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LiveEventKind {
    /// Sent once right after connecting.
    Hello,
    /// Synced entities changed.
    Changed,
    /// Household name or members changed: refetch `GET /households/{id}`.
    HouseholdChanged,
}
