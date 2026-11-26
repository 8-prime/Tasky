//! Deleting accounts (from the app, the `/delete-account` web page and the demo seeder).
//!
//! What is removed:
//! - the user row (username, display name, password hash) and with it every session,
//!   membership and invite the user created (`ON DELETE CASCADE`);
//! - meals planned only for this user (personal plan entries), as tombstones so the other
//!   members' devices drop them too;
//! - households where the user was the last member, with everything in them.
//!
//! Shared data in households that still have members stays, because it belongs to them as
//! well: lists, meals and the expense book. Expenses keep the deleted user's ID so balances
//! stay correct; clients show an unknown member as "someone".

use sqlx::SqliteConnection;
use tasky_api::{EntityKind, LiveEventKind, Op, OpAction};
use uuid::Uuid;

use crate::{
    AppState,
    auth::households_of,
    error::AppResult,
    sync::{Ctx, apply_ops, current_seq},
    util::{now_ms, parse_uuid, server_hlc},
};

/// Deletes the user and notifies the households they leave behind.
pub async fn delete_user(state: &AppState, user_id: Uuid) -> AppResult<()> {
    let mut tx = state.db.write.begin().await?;
    let remaining = delete_user_in(&mut tx, user_id).await?;
    let mut cursors = Vec::with_capacity(remaining.len());
    for household_id in remaining {
        cursors.push((household_id, current_seq(&mut tx, household_id).await?));
    }
    tx.commit().await?;

    for (household_id, cursor) in cursors {
        state
            .live
            .publish(household_id, LiveEventKind::HouseholdChanged, cursor);
        state
            .live
            .publish(household_id, LiveEventKind::Changed, cursor);
    }
    tracing::info!(%user_id, "account deleted");
    Ok(())
}

/// Does the work of [`delete_user`] inside the caller's transaction. Returns the households
/// that still exist (and need a live notification).
pub async fn delete_user_in(conn: &mut SqliteConnection, user_id: Uuid) -> AppResult<Vec<Uuid>> {
    let mut remaining = Vec::new();
    for household in households_of(conn, user_id).await? {
        let hid = household.id.to_string();
        let others: Vec<(String, String)> = sqlx::query_as(
            "SELECT user_id, role FROM memberships
             WHERE household_id = ? AND user_id != ? ORDER BY joined_at",
        )
        .bind(&hid)
        .bind(user_id.to_string())
        .fetch_all(&mut *conn)
        .await?;

        if others.is_empty() {
            sqlx::query("DELETE FROM households WHERE id = ?")
                .bind(&hid)
                .execute(&mut *conn)
                .await?;
            continue;
        }

        let personal: Vec<(String,)> = sqlx::query_as(
            "SELECT id FROM entities
             WHERE household_id = ? AND kind = 'plan_entry' AND deleted = 0
               AND json_extract(data, '$.owner_user_id') = ?",
        )
        .bind(&hid)
        .bind(user_id.to_string())
        .fetch_all(&mut *conn)
        .await?;
        let ops = personal
            .into_iter()
            .map(|(id,)| {
                Ok(Op {
                    entity_id: parse_uuid(&id)?,
                    hlc: server_hlc(),
                    action: OpAction::Delete(EntityKind::PlanEntry),
                })
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        if !ops.is_empty() {
            let ctx = Ctx {
                household_id: household.id,
                user_id,
                now_ms: now_ms(),
            };
            let outcome = apply_ops(conn, &ctx, &ops).await?;
            if !outcome.rejected.is_empty() {
                return Err(anyhow::anyhow!(
                    "removing personal plan entries: {:?}",
                    outcome.rejected
                )
                .into());
            }
        }

        // Someone has to own the household: hand it to the longest-standing member.
        if !others.iter().any(|(_, role)| role == "owner") {
            sqlx::query(
                "UPDATE memberships SET role = 'owner' WHERE household_id = ? AND user_id = ?",
            )
            .bind(&hid)
            .bind(&others[0].0)
            .execute(&mut *conn)
            .await?;
        }
        remaining.push(household.id);
    }

    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(user_id.to_string())
        .execute(&mut *conn)
        .await?;
    Ok(remaining)
}
