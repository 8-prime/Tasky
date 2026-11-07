//! The sync engine: applies client ops to stored entities and serves change feeds.
//!
//! Model:
//! - Each entity is a JSON object of fields. Every field has its own HLC clock, and a write
//!   only lands if its HLC is newer than the field's clock (last-writer-wins per field).
//!   Concurrent edits to different fields therefore never conflict, and the result doesn't
//!   depend on the order ops reach the server.
//! - Existence is its own LWW register (`alive_hlc`): a delete hides the entity unless a
//!   newer create brings it back. Deleted entities stay as tombstones.
//! - Each change bumps the household's `seq`, and clients pull everything with `seq > since`.

use std::collections::BTreeMap;

use serde_json::{Map, Value, json};
use sqlx::SqliteConnection;
use tasky_api::{
    ContributionData, Entity, EntityData, EntityKind, ItemAlias, MealIngredientData, Op, OpAction,
    PlanEntryData, RejectCode, RejectedOp, Seq,
};
use uuid::Uuid;

use crate::{
    hlc,
    util::{normalize_name, parse_uuid},
};

pub const DEFAULT_PAGE: i64 = 500;
pub const MAX_PAGE: i64 = 2000;
pub const MAX_OPS_PER_REQUEST: usize = 5000;

/// Who is applying ops, and to which household.
#[derive(Debug, Clone, Copy)]
pub struct Ctx {
    pub household_id: Uuid,
    pub user_id: Uuid,
    pub now_ms: i64,
}

#[derive(Debug, Default)]
pub struct ApplyOutcome {
    pub rejected: Vec<RejectedOp>,
    pub changed: bool,
}

enum OpError {
    Reject(RejectCode, String),
    Internal(anyhow::Error),
}

impl From<sqlx::Error> for OpError {
    fn from(e: sqlx::Error) -> Self {
        OpError::Internal(e.into())
    }
}
impl From<anyhow::Error> for OpError {
    fn from(e: anyhow::Error) -> Self {
        OpError::Internal(e)
    }
}
impl From<(RejectCode, String)> for OpError {
    fn from((code, msg): (RejectCode, String)) -> Self {
        OpError::Reject(code, msg)
    }
}

fn reject<T>(code: RejectCode, msg: impl Into<String>) -> Result<T, OpError> {
    Err(OpError::Reject(code, msg.into()))
}

/// Applies `ops` in order inside the caller's transaction. Rejected ops are reported back;
/// the rest are applied (or ignored if stale).
pub async fn apply_ops(
    conn: &mut SqliteConnection,
    ctx: &Ctx,
    ops: &[Op],
) -> anyhow::Result<ApplyOutcome> {
    let mut outcome = ApplyOutcome::default();
    for (index, op) in ops.iter().enumerate() {
        match apply_op(conn, ctx, op).await {
            Ok(changed) => outcome.changed |= changed,
            Err(OpError::Reject(code, message)) => outcome.rejected.push(RejectedOp {
                index: index as i32,
                entity_id: op.entity_id,
                code,
                message,
            }),
            Err(OpError::Internal(e)) => return Err(e),
        }
    }
    Ok(outcome)
}

struct Row {
    id: Uuid,
    kind: EntityKind,
    data: Map<String, Value>,
    clocks: BTreeMap<String, String>,
    alive_hlc: String,
    deleted: bool,
}

impl Row {
    /// Writes `value` if `hlc` is newer than the field's clock. Returns whether it was written.
    fn set_field(&mut self, field: &str, value: Value, hlc: &str) -> bool {
        if self.clocks.get(field).is_some_and(|c| c.as_str() >= hlc) {
            return false;
        }
        self.data.insert(field.to_owned(), value);
        self.clocks.insert(field.to_owned(), hlc.to_owned());
        true
    }
}

fn action_kind(action: &OpAction) -> EntityKind {
    match action {
        OpAction::Create(data) => data.kind(),
        OpAction::Delete(kind) => *kind,
        OpAction::SetItem(_) => EntityKind::Item,
        OpAction::SetList(_) => EntityKind::List,
        OpAction::SetContribution(_) => EntityKind::Contribution,
        OpAction::SetMeal(_) => EntityKind::Meal,
        OpAction::SetMealIngredient(_) => EntityKind::MealIngredient,
        OpAction::SetPlanEntry(_) => EntityKind::PlanEntry,
    }
}

/// Splits a tagged value (`{"type": name, "value": v}`) into its parts.
fn untag(tagged: Value) -> anyhow::Result<(String, Value)> {
    let Value::Object(mut obj) = tagged else {
        anyhow::bail!("expected tagged object")
    };
    let name = match obj.remove("type") {
        Some(Value::String(s)) => s,
        _ => anyhow::bail!("missing type tag"),
    };
    Ok((name, obj.remove("value").unwrap_or(Value::Null)))
}

/// Returns the single field written by a `Set*` action, or `None` for create/delete.
fn field_update(action: &OpAction) -> anyhow::Result<Option<(String, Value)>> {
    let tagged = match action {
        OpAction::Create(_) | OpAction::Delete(_) => return Ok(None),
        OpAction::SetItem(f) => serde_json::to_value(f)?,
        OpAction::SetList(f) => serde_json::to_value(f)?,
        OpAction::SetContribution(f) => serde_json::to_value(f)?,
        OpAction::SetMeal(f) => serde_json::to_value(f)?,
        OpAction::SetMealIngredient(f) => serde_json::to_value(f)?,
        OpAction::SetPlanEntry(f) => serde_json::to_value(f)?,
    };
    untag(tagged).map(Some)
}

fn data_fields(data: &EntityData) -> anyhow::Result<Map<String, Value>> {
    match untag(serde_json::to_value(data)?)? {
        (_, Value::Object(map)) => Ok(map),
        _ => anyhow::bail!("entity data is not an object"),
    }
}

async fn apply_op(conn: &mut SqliteConnection, ctx: &Ctx, op: &Op) -> Result<bool, OpError> {
    hlc::validate(&op.hlc, ctx.now_ms)?;
    let hlc = op.hlc.as_str();
    let kind = action_kind(&op.action);

    let mut entity_id = op.entity_id;
    if kind == EntityKind::Item {
        entity_id = resolve_alias(conn, ctx, entity_id).await?;
    }

    let existing = load(conn, ctx, entity_id).await?;
    if let Some(row) = &existing
        && row.kind != kind
    {
        return reject(
            RejectCode::KindMismatch,
            format!("entity is a {}, not a {}", row.kind.as_str(), kind.as_str()),
        );
    }

    match &op.action {
        OpAction::Create(data) => {
            let mut fields = data_fields(data)?;
            // Server-owned: always derived from `purchased` in `after_write`.
            fields.remove("purchased_by");
            match existing {
                None => {
                    if let EntityData::Item(item) = data
                        && let Some(target) =
                            find_item_by_name(conn, ctx, &normalize_name(&item.name)).await?
                    {
                        insert_alias(conn, ctx, entity_id, target).await?;
                        return Ok(true);
                    }
                    let mut row = Row {
                        id: entity_id,
                        kind,
                        data: Map::new(),
                        clocks: BTreeMap::new(),
                        alive_hlc: hlc.to_owned(),
                        deleted: false,
                    };
                    for (field, value) in fields {
                        row.set_field(&field, value, hlc);
                    }
                    after_write(&mut row, ctx, hlc, true);
                    finalize(conn, ctx, &mut row).await?;
                    save(conn, ctx, &row, Save::Insert).await?;
                    Ok(true)
                }
                Some(mut row) => {
                    let was_deleted = row.deleted;
                    // Existence is an LWW register: a create newer than the last create/delete
                    // wins, so an older delete that arrives later can't remove the entity.
                    let mut revived = false;
                    if hlc > row.alive_hlc.as_str() {
                        row.deleted = false;
                        row.alive_hlc = hlc.to_owned();
                        revived = true;
                    }
                    // Fields merge even under a tombstone, so the result doesn't depend on whether
                    // a later revive arrives before or after this op.
                    let mut written = false;
                    let mut purchased_written = false;
                    for (field, value) in fields {
                        let w = row.set_field(&field, value, hlc);
                        purchased_written |= w && field == "purchased";
                        written |= w;
                    }
                    if !written && !revived {
                        return Ok(false);
                    }
                    after_write(&mut row, ctx, hlc, purchased_written);
                    finalize(conn, ctx, &mut row).await?;
                    // Hidden changes to a still-deleted entity don't need to reach clients.
                    let visible = !(was_deleted && row.deleted);
                    save(
                        conn,
                        ctx,
                        &row,
                        if visible { Save::Update } else { Save::Silent },
                    )
                    .await?;
                    Ok(visible)
                }
            }
        }
        OpAction::Delete(_) => {
            let Some(mut row) = existing else {
                return Ok(false);
            };
            if hlc <= row.alive_hlc.as_str() {
                return Ok(false);
            }
            row.alive_hlc = hlc.to_owned();
            if row.deleted {
                // Already gone; only record the newer clock, which other clients don't need to see.
                sqlx::query("UPDATE entities SET alive_hlc = ? WHERE id = ?")
                    .bind(hlc)
                    .bind(row.id.to_string())
                    .execute(&mut *conn)
                    .await?;
                return Ok(false);
            }
            row.deleted = true;
            save(conn, ctx, &row, Save::Update).await?;
            Ok(true)
        }
        action => {
            let (field, value) = field_update(action)?.expect("set action has a field");
            let Some(mut row) = existing else {
                return reject(RejectCode::NotFound, "entity does not exist");
            };
            if !row.set_field(&field, value, hlc) {
                return Ok(false);
            }
            after_write(&mut row, ctx, hlc, field == "purchased");
            finalize(conn, ctx, &mut row).await?;
            // Writes to a deleted entity are kept (in case it's revived) but stay hidden.
            save(
                conn,
                ctx,
                &row,
                if row.deleted {
                    Save::Silent
                } else {
                    Save::Update
                },
            )
            .await?;
            Ok(!row.deleted)
        }
    }
}

/// Fields the server derives itself: whoever checks off a contribution becomes its `purchased_by`.
fn after_write(row: &mut Row, ctx: &Ctx, hlc: &str, purchased_written: bool) {
    if row.kind == EntityKind::Contribution && purchased_written {
        let purchased = row
            .data
            .get("purchased")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let by = if purchased {
            json!(ctx.user_id)
        } else {
            Value::Null
        };
        row.data.insert("purchased_by".into(), by);
        row.clocks.insert("purchased_by".into(), hlc.to_owned());
    }
}

/// Maps merged item IDs to their targets, then checks the entity is valid and its
/// references exist in this household.
async fn finalize(conn: &mut SqliteConnection, ctx: &Ctx, row: &mut Row) -> Result<(), OpError> {
    if matches!(
        row.kind,
        EntityKind::Contribution | EntityKind::MealIngredient
    ) && let Some(Value::String(item_id)) = row.data.get("item_id")
        && let Ok(item_id) = Uuid::parse_str(item_id)
    {
        let target = resolve_alias(conn, ctx, item_id).await?;
        if target != item_id {
            row.data.insert("item_id".into(), json!(target));
        }
    }

    let typed = to_entity_data(row.kind, &row.data).map_err(|e| {
        OpError::Reject(
            RejectCode::Validation,
            format!("invalid {}: {e}", row.kind.as_str()),
        )
    })?;
    validate(conn, ctx, &typed).await
}

fn to_entity_data(kind: EntityKind, data: &Map<String, Value>) -> serde_json::Result<EntityData> {
    serde_json::from_value(json!({ "type": kind.as_str(), "value": data }))
}

fn check_text(value: &str, what: &str, max: usize, required: bool) -> Result<(), OpError> {
    if required && value.trim().is_empty() {
        return reject(RejectCode::Validation, format!("{what} must not be empty"));
    }
    if value.chars().count() > max {
        return reject(
            RejectCode::Validation,
            format!("{what} must be at most {max} characters"),
        );
    }
    Ok(())
}

fn check_opt_text(value: &Option<String>, what: &str, max: usize) -> Result<(), OpError> {
    value
        .as_deref()
        .map_or(Ok(()), |v| check_text(v, what, max, false))
}

fn check_amount(amount: Option<f64>) -> Result<(), OpError> {
    match amount {
        Some(a) if !a.is_finite() || a < 0.0 || a > 1e9 => reject(
            RejectCode::Validation,
            "amount must be a finite number between 0 and 1e9",
        ),
        _ => Ok(()),
    }
}

fn check_servings(servings: i32, what: &str) -> Result<(), OpError> {
    if !(1..=1000).contains(&servings) {
        return reject(
            RejectCode::Validation,
            format!("{what} must be between 1 and 1000"),
        );
    }
    Ok(())
}

async fn validate(
    conn: &mut SqliteConnection,
    ctx: &Ctx,
    data: &EntityData,
) -> Result<(), OpError> {
    match data {
        EntityData::Item(d) => {
            check_text(&d.name, "name", 200, true)?;
            check_opt_text(&d.category, "category", 64)?;
            check_opt_text(&d.default_unit, "default_unit", 32)?;
        }
        EntityData::List(d) => check_text(&d.name, "name", 200, true)?,
        EntityData::Contribution(ContributionData {
            list_id,
            item_id,
            amount,
            unit,
            plan_entry_id,
            note,
            ..
        }) => {
            check_amount(*amount)?;
            check_opt_text(unit, "unit", 32)?;
            check_opt_text(note, "note", 1000)?;
            check_ref(conn, ctx, *list_id, EntityKind::List).await?;
            check_ref(conn, ctx, *item_id, EntityKind::Item).await?;
            if let Some(id) = plan_entry_id {
                check_ref(conn, ctx, *id, EntityKind::PlanEntry).await?;
            }
        }
        EntityData::Meal(d) => {
            check_text(&d.name, "name", 200, true)?;
            check_servings(d.default_servings, "default_servings")?;
            check_opt_text(&d.notes, "notes", 10_000)?;
            check_opt_text(&d.url, "url", 2000)?;
        }
        EntityData::MealIngredient(MealIngredientData {
            meal_id,
            item_id,
            amount,
            unit,
            note,
        }) => {
            check_amount(*amount)?;
            check_opt_text(unit, "unit", 32)?;
            check_opt_text(note, "note", 1000)?;
            check_ref(conn, ctx, *meal_id, EntityKind::Meal).await?;
            check_ref(conn, ctx, *item_id, EntityKind::Item).await?;
        }
        EntityData::PlanEntry(PlanEntryData {
            date,
            owner_user_id,
            meal_id,
            free_text,
            servings,
            note,
            ..
        }) => {
            if chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d").is_err() {
                return reject(RejectCode::Validation, "date must be YYYY-MM-DD");
            }
            check_opt_text(free_text, "free_text", 200)?;
            check_opt_text(note, "note", 1000)?;
            if let Some(s) = servings {
                check_servings(*s, "servings")?;
            }
            let has_text = free_text.as_deref().is_some_and(|t| !t.trim().is_empty());
            if meal_id.is_none() && !has_text {
                return reject(
                    RejectCode::Validation,
                    "plan entry needs a meal_id or free_text",
                );
            }
            if let Some(id) = meal_id {
                check_ref(conn, ctx, *id, EntityKind::Meal).await?;
            }
            if let Some(user) = owner_user_id {
                check_member(conn, ctx, *user).await?;
            }
        }
    }
    Ok(())
}

async fn check_ref(
    conn: &mut SqliteConnection,
    ctx: &Ctx,
    id: Uuid,
    kind: EntityKind,
) -> Result<(), OpError> {
    let found: Option<(String,)> =
        sqlx::query_as("SELECT kind FROM entities WHERE id = ? AND household_id = ?")
            .bind(id.to_string())
            .bind(ctx.household_id.to_string())
            .fetch_optional(&mut *conn)
            .await?;
    match found {
        Some((k,)) if k == kind.as_str() => Ok(()),
        _ => reject(
            RejectCode::InvalidReference,
            format!("unknown {} {id}", kind.as_str()),
        ),
    }
}

async fn check_member(conn: &mut SqliteConnection, ctx: &Ctx, user: Uuid) -> Result<(), OpError> {
    let found: Option<(i64,)> =
        sqlx::query_as("SELECT 1 FROM memberships WHERE household_id = ? AND user_id = ?")
            .bind(ctx.household_id.to_string())
            .bind(user.to_string())
            .fetch_optional(&mut *conn)
            .await?;
    match found {
        Some(_) => Ok(()),
        None => reject(
            RejectCode::InvalidReference,
            format!("user {user} is not a member"),
        ),
    }
}

async fn resolve_alias(conn: &mut SqliteConnection, ctx: &Ctx, id: Uuid) -> sqlx::Result<Uuid> {
    let target: Option<(String,)> = sqlx::query_as(
        "SELECT target_id FROM item_aliases WHERE alias_id = ? AND household_id = ?",
    )
    .bind(id.to_string())
    .bind(ctx.household_id.to_string())
    .fetch_optional(&mut *conn)
    .await?;
    Ok(target
        .and_then(|(t,)| Uuid::parse_str(&t).ok())
        .unwrap_or(id))
}

async fn find_item_by_name(
    conn: &mut SqliteConnection,
    ctx: &Ctx,
    norm_name: &str,
) -> anyhow::Result<Option<Uuid>> {
    let row: Option<(String,)> = sqlx::query_as(
        "SELECT id FROM entities
         WHERE household_id = ? AND kind = 'item' AND deleted = 0 AND norm_name = ?
         ORDER BY seq LIMIT 1",
    )
    .bind(ctx.household_id.to_string())
    .bind(norm_name)
    .fetch_optional(&mut *conn)
    .await?;
    row.map(|(id,)| parse_uuid(&id)).transpose()
}

async fn next_seq(conn: &mut SqliteConnection, household_id: Uuid) -> sqlx::Result<Seq> {
    let (seq,): (i64,) =
        sqlx::query_as("UPDATE households SET seq = seq + 1 WHERE id = ? RETURNING seq")
            .bind(household_id.to_string())
            .fetch_one(&mut *conn)
            .await?;
    Ok(seq)
}

async fn insert_alias(
    conn: &mut SqliteConnection,
    ctx: &Ctx,
    alias: Uuid,
    target: Uuid,
) -> sqlx::Result<()> {
    let seq = next_seq(conn, ctx.household_id).await?;
    sqlx::query(
        "INSERT INTO item_aliases (alias_id, household_id, target_id, seq) VALUES (?, ?, ?, ?)",
    )
    .bind(alias.to_string())
    .bind(ctx.household_id.to_string())
    .bind(target.to_string())
    .bind(seq)
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// Loads an entity of this household. An ID that belongs to another household is
/// treated as a reference that doesn't exist.
async fn load(conn: &mut SqliteConnection, ctx: &Ctx, id: Uuid) -> Result<Option<Row>, OpError> {
    let row: Option<(String, String, String, String, String, bool)> = sqlx::query_as(
        "SELECT household_id, kind, data, clocks, alive_hlc, deleted FROM entities WHERE id = ?",
    )
    .bind(id.to_string())
    .fetch_optional(&mut *conn)
    .await?;
    let Some((household_id, kind, data, clocks, alive_hlc, deleted)) = row else {
        return Ok(None);
    };
    if household_id != ctx.household_id.to_string() {
        return reject(RejectCode::NotFound, "entity does not exist");
    }
    let kind = EntityKind::parse(&kind).ok_or_else(|| anyhow::anyhow!("bad stored kind {kind}"))?;
    Ok(Some(Row {
        id,
        kind,
        data: serde_json::from_str(&data).map_err(anyhow::Error::from)?,
        clocks: serde_json::from_str(&clocks).map_err(anyhow::Error::from)?,
        alive_hlc,
        deleted,
    }))
}

#[derive(PartialEq)]
enum Save {
    Insert,
    /// Bump the seq so clients receive the change.
    Update,
    /// Store without bumping the seq (changes nobody can see yet).
    Silent,
}

async fn save(conn: &mut SqliteConnection, ctx: &Ctx, row: &Row, mode: Save) -> anyhow::Result<()> {
    let data = serde_json::to_string(&row.data)?;
    let clocks = serde_json::to_string(&row.clocks)?;
    let norm_name = (row.kind == EntityKind::Item)
        .then(|| {
            row.data
                .get("name")
                .and_then(Value::as_str)
                .map(normalize_name)
        })
        .flatten();
    if mode == Save::Silent {
        sqlx::query(
            "UPDATE entities SET data = ?, clocks = ?, alive_hlc = ?, deleted = ?, norm_name = ?
             WHERE id = ? AND household_id = ?",
        )
        .bind(data)
        .bind(clocks)
        .bind(&row.alive_hlc)
        .bind(row.deleted)
        .bind(norm_name)
        .bind(row.id.to_string())
        .bind(ctx.household_id.to_string())
        .execute(&mut *conn)
        .await?;
        return Ok(());
    }

    let seq = next_seq(conn, ctx.household_id).await?;
    let sql = if mode == Save::Insert {
        "INSERT INTO entities (data, clocks, alive_hlc, deleted, norm_name, seq, updated_by, updated_at,
                               id, household_id, kind)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    } else {
        "UPDATE entities SET data = ?, clocks = ?, alive_hlc = ?, deleted = ?, norm_name = ?, seq = ?,
                             updated_by = ?, updated_at = ?
         WHERE id = ? AND household_id = ? AND kind = ?"
    };
    sqlx::query(sql)
        .bind(data)
        .bind(clocks)
        .bind(&row.alive_hlc)
        .bind(row.deleted)
        .bind(norm_name)
        .bind(seq)
        .bind(ctx.user_id.to_string())
        .bind(ctx.now_ms)
        .bind(row.id.to_string())
        .bind(ctx.household_id.to_string())
        .bind(row.kind.as_str())
        .execute(&mut *conn)
        .await?;
    Ok(())
}

pub async fn current_seq(conn: &mut SqliteConnection, household_id: Uuid) -> sqlx::Result<Seq> {
    let (seq,): (i64,) = sqlx::query_as("SELECT seq FROM households WHERE id = ?")
        .bind(household_id.to_string())
        .fetch_one(&mut *conn)
        .await?;
    Ok(seq)
}

/// One page of the change feed.
pub struct Changes {
    pub entities: Vec<Entity>,
    pub aliases: Vec<ItemAlias>,
    pub cursor: Seq,
    pub has_more: bool,
    pub full_resync: bool,
}

/// Reads entities changed after `since`. Run it in a read transaction so the page and
/// the cursor come from the same snapshot.
pub async fn fetch_changes(
    conn: &mut SqliteConnection,
    household_id: Uuid,
    since: Seq,
    limit: i64,
) -> anyhow::Result<Changes> {
    let head = current_seq(conn, household_id).await?;
    let full_resync = since > head;
    let since = if full_resync { 0 } else { since.max(0) };

    type EntityRow = (String, String, String, bool, i64, Option<String>, i64);
    let rows: Vec<EntityRow> = sqlx::query_as(
        "SELECT id, kind, data, deleted, seq, updated_by, updated_at FROM entities
         WHERE household_id = ?1 AND seq > ?2 AND (?2 > 0 OR deleted = 0)
         ORDER BY seq LIMIT ?3",
    )
    .bind(household_id.to_string())
    .bind(since)
    .bind(limit)
    .fetch_all(&mut *conn)
    .await?;

    let has_more = rows.len() as i64 == limit && rows.last().is_some_and(|r| r.4 < head);
    let cursor = if has_more {
        rows.last().map_or(head, |r| r.4)
    } else {
        head
    };

    let mut entities = Vec::with_capacity(rows.len());
    for (id, kind, data, deleted, seq, updated_by, updated_at) in rows {
        let kind = EntityKind::parse(&kind).ok_or_else(|| anyhow::anyhow!("bad stored kind"))?;
        let data: Map<String, Value> = serde_json::from_str(&data)?;
        entities.push(Entity {
            id: parse_uuid(&id)?,
            seq,
            deleted,
            data: to_entity_data(kind, &data)?,
            updated_by: updated_by.as_deref().map(parse_uuid).transpose()?,
            updated_at_ms: updated_at,
        });
    }

    let alias_rows: Vec<(String, String, i64)> = sqlx::query_as(
        "SELECT alias_id, target_id, seq FROM item_aliases
         WHERE household_id = ? AND seq > ? AND seq <= ? ORDER BY seq",
    )
    .bind(household_id.to_string())
    .bind(since)
    .bind(cursor)
    .fetch_all(&mut *conn)
    .await?;
    let aliases = alias_rows
        .into_iter()
        .map(|(alias, target, seq)| {
            Ok(ItemAlias {
                alias_id: parse_uuid(&alias)?,
                target_id: parse_uuid(&target)?,
                seq,
            })
        })
        .collect::<anyhow::Result<_>>()?;

    Ok(Changes {
        entities,
        aliases,
        cursor,
        has_more,
        full_resync,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tasky_api::*;

    /// Every field-update variant must name a real field of its data struct, or set ops
    /// would write keys that are silently dropped.
    #[test]
    fn field_updates_match_data_fields() {
        let id = Uuid::nil();
        let samples: Vec<(OpAction, EntityData)> = vec![
            (OpAction::SetItem(ItemField::Name("x".into())), item()),
            (OpAction::SetItem(ItemField::Category(None)), item()),
            (OpAction::SetItem(ItemField::DefaultUnit(None)), item()),
            (
                OpAction::SetList(ListField::Name("x".into())),
                EntityData::List(ListData { name: "l".into() }),
            ),
            (
                OpAction::SetContribution(ContributionField::ListId(id)),
                contribution(),
            ),
            (
                OpAction::SetContribution(ContributionField::Amount(None)),
                contribution(),
            ),
            (
                OpAction::SetContribution(ContributionField::Unit(None)),
                contribution(),
            ),
            (
                OpAction::SetContribution(ContributionField::Note(None)),
                contribution(),
            ),
            (
                OpAction::SetContribution(ContributionField::Purchased(true)),
                contribution(),
            ),
            (
                OpAction::SetContribution(ContributionField::Archived(true)),
                contribution(),
            ),
            (OpAction::SetMeal(MealField::Name("x".into())), meal()),
            (OpAction::SetMeal(MealField::DefaultServings(2)), meal()),
            (OpAction::SetMeal(MealField::Notes(None)), meal()),
            (OpAction::SetMeal(MealField::Url(None)), meal()),
            (
                OpAction::SetMealIngredient(MealIngredientField::ItemId(id)),
                ingredient(),
            ),
            (
                OpAction::SetMealIngredient(MealIngredientField::Amount(None)),
                ingredient(),
            ),
            (
                OpAction::SetMealIngredient(MealIngredientField::Unit(None)),
                ingredient(),
            ),
            (
                OpAction::SetMealIngredient(MealIngredientField::Note(None)),
                ingredient(),
            ),
            (
                OpAction::SetPlanEntry(PlanEntryField::Date("2026-01-01".into())),
                plan(),
            ),
            (
                OpAction::SetPlanEntry(PlanEntryField::Slot(MealSlot::Lunch)),
                plan(),
            ),
            (
                OpAction::SetPlanEntry(PlanEntryField::OwnerUserId(None)),
                plan(),
            ),
            (OpAction::SetPlanEntry(PlanEntryField::MealId(None)), plan()),
            (
                OpAction::SetPlanEntry(PlanEntryField::FreeText(None)),
                plan(),
            ),
            (
                OpAction::SetPlanEntry(PlanEntryField::Servings(None)),
                plan(),
            ),
            (OpAction::SetPlanEntry(PlanEntryField::Note(None)), plan()),
        ];
        for (action, data) in samples {
            let (field, _) = field_update(&action).unwrap().unwrap();
            let fields = data_fields(&data).unwrap();
            assert!(
                fields.contains_key(&field),
                "{field} is not a field of {:?}",
                data.kind()
            );
            assert_eq!(action_kind(&action), data.kind());
        }
    }

    fn item() -> EntityData {
        EntityData::Item(ItemData {
            name: "i".into(),
            category: None,
            default_unit: None,
        })
    }
    fn contribution() -> EntityData {
        EntityData::Contribution(ContributionData {
            list_id: Uuid::nil(),
            item_id: Uuid::nil(),
            amount: None,
            unit: None,
            plan_entry_id: None,
            note: None,
            purchased: false,
            purchased_by: None,
            archived: false,
        })
    }
    fn meal() -> EntityData {
        EntityData::Meal(MealData {
            name: "m".into(),
            default_servings: 2,
            notes: None,
            url: None,
        })
    }
    fn ingredient() -> EntityData {
        EntityData::MealIngredient(MealIngredientData {
            meal_id: Uuid::nil(),
            item_id: Uuid::nil(),
            amount: None,
            unit: None,
            note: None,
        })
    }
    fn plan() -> EntityData {
        EntityData::PlanEntry(PlanEntryData {
            date: "2026-01-01".into(),
            slot: MealSlot::Dinner,
            owner_user_id: None,
            meal_id: None,
            free_text: Some("x".into()),
            servings: None,
            note: None,
        })
    }
}
