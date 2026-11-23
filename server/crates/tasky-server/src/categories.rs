//! Built-in categories and the one-time upgrade of households created before categories existed.
//!
//! Every household gets the categories in [`BUILTIN_CATEGORIES`], with deterministic IDs
//! (`uuidv5(CATEGORY_NAMESPACE, "<household_id>:<key>")`). Households from before categories
//! are upgraded at startup: they get the built-in categories, and the free-text `category` of
//! their items is turned into a `category_id` (matching a built-in category where the text
//! says the same thing, otherwise creating a category with that name).

use std::collections::HashMap;

use serde_json::Value;
use sqlx::SqliteConnection;
use tasky_api::{
    BUILTIN_CATEGORIES, CATEGORY_NAMESPACE, CategoryData, EntityData, ItemField, Op, OpAction,
};
use uuid::Uuid;

use crate::{
    db::Db,
    sync::{Ctx, apply_ops},
    util::{normalize_name, now_ms, parse_uuid, server_hlc},
};

/// `households.data_version` once a household has categories.
pub const DATA_VERSION_CATEGORIES: i64 = 1;
/// The data version new households start at.
pub const CURRENT_DATA_VERSION: i64 = DATA_VERSION_CATEGORIES;

/// ID of a built-in category in a household.
pub fn builtin_id(household_id: Uuid, key: &str) -> Uuid {
    Uuid::new_v5(
        &CATEGORY_NAMESPACE,
        format!("{household_id}:{key}").as_bytes(),
    )
}

/// Ops creating the built-in categories. Creating them again is harmless: the IDs are the same.
pub fn builtin_ops(household_id: Uuid, hlc: &str) -> Vec<Op> {
    BUILTIN_CATEGORIES
        .iter()
        .map(|(key, name)| Op {
            entity_id: builtin_id(household_id, key),
            hlc: hlc.to_owned(),
            action: OpAction::Create(EntityData::Category(CategoryData {
                name: (*name).into(),
                key: Some((*key).into()),
            })),
        })
        .collect()
}

/// Old free-text categories (normalized) that mean a built-in one. The built-in keys and names
/// match too.
const LEGACY_SYNONYMS: &[(&str, &str)] = &[
    ("produce", "produce"),
    ("fruit", "produce"),
    ("fruits", "produce"),
    ("vegetables", "produce"),
    ("veggies", "produce"),
    ("fruit & veg", "produce"),
    ("obst", "produce"),
    ("gemüse", "produce"),
    ("obst & gemüse", "produce"),
    ("obst und gemüse", "produce"),
    ("bakery", "bakery"),
    ("bread", "bakery"),
    ("brot", "bakery"),
    ("backwaren", "bakery"),
    ("meat", "meat_fish"),
    ("fish", "meat_fish"),
    ("fleisch", "meat_fish"),
    ("fisch", "meat_fish"),
    ("dairy", "dairy"),
    ("milchprodukte", "dairy"),
    ("cheese", "dairy"),
    ("käse", "dairy"),
    ("eggs", "dairy"),
    ("deli", "chilled"),
    ("chilled", "chilled"),
    ("kühlregal", "chilled"),
    ("breakfast", "breakfast"),
    ("frühstück", "breakfast"),
    ("coffee", "coffee_tea"),
    ("tea", "coffee_tea"),
    ("kaffee", "coffee_tea"),
    ("pasta", "pasta_rice"),
    ("grains", "pasta_rice"),
    ("baking", "baking"),
    ("backen", "baking"),
    ("cans", "canned"),
    ("canned", "canned"),
    ("canned goods", "canned"),
    ("konserven", "canned"),
    ("spices", "condiments"),
    ("gewürze", "condiments"),
    ("condiments", "condiments"),
    ("sauces", "condiments"),
    ("snacks", "snacks"),
    ("sweets", "snacks"),
    ("candy", "snacks"),
    ("süßigkeiten", "snacks"),
    ("drinks", "drinks"),
    ("beverages", "drinks"),
    ("getränke", "drinks"),
    ("frozen", "frozen"),
    ("tiefkühl", "frozen"),
    ("tiefkühlkost", "frozen"),
    ("household", "household"),
    ("cleaning", "household"),
    ("haushalt", "household"),
    ("drugstore", "personal_care"),
    ("drogerie", "personal_care"),
    ("hygiene", "personal_care"),
    ("toiletries", "personal_care"),
    ("pet", "pets"),
    ("pets", "pets"),
    ("haustier", "pets"),
];

/// Maps an old free-text category to a built-in key, if it means one.
fn builtin_key_for(text: &str) -> Option<&'static str> {
    let norm = normalize_name(text);
    BUILTIN_CATEGORIES
        .iter()
        .find(|(key, name)| norm == *key || norm == normalize_name(name))
        .map(|(key, _)| *key)
        .or_else(|| {
            LEGACY_SYNONYMS
                .iter()
                .find(|(syn, _)| norm == *syn)
                .map(|(_, key)| *key)
        })
}

/// Brings every household up to [`CURRENT_DATA_VERSION`]. Runs at startup; each household is
/// upgraded in its own transaction.
pub async fn upgrade_households(db: &Db) -> anyhow::Result<()> {
    let pending: Vec<(String,)> =
        sqlx::query_as("SELECT id FROM households WHERE data_version < ?")
            .bind(DATA_VERSION_CATEGORIES)
            .fetch_all(&db.write)
            .await?;
    for (id,) in pending {
        let household_id = parse_uuid(&id)?;
        let mut tx = db.write.begin().await?;
        add_categories(&mut tx, household_id).await?;
        sqlx::query("UPDATE households SET data_version = ? WHERE id = ?")
            .bind(DATA_VERSION_CATEGORIES)
            .bind(&id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        tracing::info!(%household_id, "added categories to household");
    }
    Ok(())
}

async fn add_categories(conn: &mut SqliteConnection, household_id: Uuid) -> anyhow::Result<()> {
    // Changes need an author; use the household's first member.
    let owner: Option<(String,)> = sqlx::query_as(
        "SELECT user_id FROM memberships WHERE household_id = ? ORDER BY role = 'owner' DESC, joined_at LIMIT 1",
    )
    .bind(household_id.to_string())
    .fetch_optional(&mut *conn)
    .await?;
    let user_id = owner
        .map(|(u,)| parse_uuid(&u))
        .transpose()?
        .unwrap_or_default();
    let ctx = Ctx {
        household_id,
        user_id,
        now_ms: now_ms(),
    };
    let hlc = server_hlc();
    let mut ops = builtin_ops(household_id, &hlc);

    let items: Vec<(String, String)> = sqlx::query_as(
        "SELECT id, data FROM entities WHERE household_id = ? AND kind = 'item' AND deleted = 0",
    )
    .bind(household_id.to_string())
    .fetch_all(&mut *conn)
    .await?;
    // Normalized text → category ID, for custom categories created along the way.
    let mut custom: HashMap<String, Uuid> = HashMap::new();
    for (item_id, data) in items {
        let data: serde_json::Map<String, Value> = serde_json::from_str(&data)?;
        if data.get("category_id").is_some_and(|v| !v.is_null()) {
            continue;
        }
        let Some(text) = data
            .get("category")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|t| !t.is_empty())
        else {
            continue;
        };
        let category_id = match builtin_key_for(text) {
            Some(key) => builtin_id(household_id, key),
            None => *custom
                .entry(normalize_name(text))
                .or_insert_with_key(|norm| {
                    let id = Uuid::new_v5(
                        &CATEGORY_NAMESPACE,
                        format!("{household_id}:custom:{norm}").as_bytes(),
                    );
                    ops.push(Op {
                        entity_id: id,
                        hlc: hlc.clone(),
                        action: OpAction::Create(EntityData::Category(CategoryData {
                            name: text.chars().take(64).collect(),
                            key: None,
                        })),
                    });
                    id
                }),
        };
        ops.push(Op {
            entity_id: parse_uuid(&item_id)?,
            hlc: hlc.clone(),
            action: OpAction::SetItem(ItemField::CategoryId(Some(category_id))),
        });
    }

    let outcome = apply_ops(conn, &ctx, &ops).await?;
    if !outcome.rejected.is_empty() {
        anyhow::bail!("category upgrade rejected: {:?}", outcome.rejected);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_old_categories_to_builtin_ones() {
        assert_eq!(builtin_key_for(" Produce "), Some("produce"));
        assert_eq!(builtin_key_for("dairy & eggs"), Some("dairy"));
        assert_eq!(builtin_key_for("Getränke"), Some("drinks"));
        assert_eq!(builtin_key_for("Cans"), Some("canned"));
        assert_eq!(builtin_key_for("Asian"), None);
    }

    #[test]
    fn builtin_ids_are_stable() {
        let h = Uuid::from_u128(1);
        assert_eq!(builtin_id(h, "dairy"), builtin_id(h, "dairy"));
        assert_ne!(
            builtin_id(h, "dairy"),
            builtin_id(Uuid::from_u128(2), "dairy")
        );
    }

    #[test]
    fn builtin_keys_are_unique() {
        let mut keys: Vec<_> = BUILTIN_CATEGORIES.iter().map(|(k, _)| *k).collect();
        keys.sort();
        keys.dedup();
        assert_eq!(keys.len(), BUILTIN_CATEGORIES.len());
    }
}
