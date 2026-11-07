use axum::{
    Json,
    extract::{Path, State},
};
use tasky_api::{
    Household, HouseholdDetails, InviteResponse, LiveEventKind, Member, Role,
    UpdateHouseholdRequest,
};
use uuid::Uuid;

use crate::{
    AppState,
    auth::{AuthUser, validate_name},
    error::AppResult,
    invite::{generate_invite_code, hash_invite_code},
    sync::current_seq,
    util::{now_ms, parse_uuid},
};

pub async fn notify_household_changed(state: &AppState, household_id: Uuid) -> AppResult<()> {
    let mut conn = state.db.read.acquire().await?;
    let cursor = current_seq(&mut conn, household_id).await?;
    state
        .live
        .publish(household_id, LiveEventKind::HouseholdChanged, cursor);
    Ok(())
}

/// `GET /api/v1/households/{household_id}`
pub async fn get(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(household_id): Path<Uuid>,
) -> AppResult<Json<HouseholdDetails>> {
    auth.require_member(&state, household_id).await?;
    let (name,): (String,) = sqlx::query_as("SELECT name FROM households WHERE id = ?")
        .bind(household_id.to_string())
        .fetch_one(&state.db.read)
        .await?;
    let rows: Vec<(String, String, String, String, i64)> = sqlx::query_as(
        "SELECT u.id, u.username, u.display_name, m.role, m.joined_at
         FROM memberships m JOIN users u ON u.id = m.user_id
         WHERE m.household_id = ? ORDER BY m.joined_at",
    )
    .bind(household_id.to_string())
    .fetch_all(&state.db.read)
    .await?;
    let members = rows
        .into_iter()
        .map(|(id, username, display_name, role, joined_at_ms)| {
            Ok(Member {
                user_id: parse_uuid(&id)?,
                username,
                display_name,
                role: if role == "owner" {
                    Role::Owner
                } else {
                    Role::Member
                },
                joined_at_ms,
            })
        })
        .collect::<anyhow::Result<_>>()?;
    Ok(Json(HouseholdDetails {
        household: Household {
            id: household_id,
            name,
        },
        members,
    }))
}

/// `PATCH /api/v1/households/{household_id}`
pub async fn update(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(household_id): Path<Uuid>,
    Json(req): Json<UpdateHouseholdRequest>,
) -> AppResult<Json<HouseholdDetails>> {
    auth.require_member(&state, household_id).await?;
    let name = validate_name(&req.name, "household name", 100)?;
    sqlx::query("UPDATE households SET name = ? WHERE id = ?")
        .bind(name)
        .bind(household_id.to_string())
        .execute(&state.db.write)
        .await?;
    notify_household_changed(&state, household_id).await?;
    get(State(state), auth, Path(household_id)).await
}

/// `POST /api/v1/households/{household_id}/invites`: any member can invite.
pub async fn create_invite(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(household_id): Path<Uuid>,
) -> AppResult<Json<InviteResponse>> {
    auth.require_member(&state, household_id).await?;
    let code = generate_invite_code();
    let now = now_ms();
    let expires_at_ms = now + state.config.invite_ttl_hours * 60 * 60 * 1000;
    sqlx::query(
        "INSERT INTO invites (code_hash, household_id, created_by, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(hash_invite_code(&code))
    .bind(household_id.to_string())
    .bind(auth.user_id.to_string())
    .bind(now)
    .bind(expires_at_ms)
    .execute(&state.db.write)
    .await?;
    Ok(Json(InviteResponse {
        code,
        expires_at_ms,
    }))
}
