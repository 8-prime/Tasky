use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use tasky_api::{ChangePasswordRequest, MeResponse, Session, UpdateMeRequest, User};
use uuid::Uuid;

use crate::{
    AppState,
    auth::{
        AuthUser, hash_password, households_of, invalid_credentials, validate_display_name,
        validate_password, verify_password,
    },
    error::{AppError, AppResult},
    util::parse_uuid,
};

async fn load_user(state: &AppState, user_id: Uuid) -> AppResult<(User, String)> {
    let (username, display_name, hash): (String, String, String) =
        sqlx::query_as("SELECT username, display_name, password_hash FROM users WHERE id = ?")
            .bind(user_id.to_string())
            .fetch_one(&state.db.read)
            .await?;
    Ok((
        User {
            id: user_id,
            username,
            display_name,
        },
        hash,
    ))
}

/// `GET /api/v1/me`
pub async fn me(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<MeResponse>> {
    let (user, _) = load_user(&state, auth.user_id).await?;
    let mut conn = state.db.read.acquire().await?;
    let households = households_of(&mut conn, auth.user_id).await?;
    Ok(Json(MeResponse { user, households }))
}

/// `PATCH /api/v1/me`
pub async fn update_me(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<UpdateMeRequest>,
) -> AppResult<Json<MeResponse>> {
    let display_name = validate_display_name(&req.display_name)?;
    sqlx::query("UPDATE users SET display_name = ? WHERE id = ?")
        .bind(&display_name)
        .bind(auth.user_id.to_string())
        .execute(&state.db.write)
        .await?;
    let response = me(State(state.clone()), auth).await?;
    for household in &response.households {
        crate::routes::household::notify_household_changed(&state, household.id).await?;
    }
    Ok(response)
}

/// `POST /api/v1/me/password`: also signs out every other session.
pub async fn change_password(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<ChangePasswordRequest>,
) -> AppResult<StatusCode> {
    validate_password(&req.new_password)?;
    let (_, hash) = load_user(&state, auth.user_id).await?;
    if !verify_password(req.current_password, hash).await? {
        return Err(invalid_credentials());
    }
    let new_hash = hash_password(req.new_password).await?;
    let mut tx = state.db.write.begin().await?;
    sqlx::query("UPDATE users SET password_hash = ? WHERE id = ?")
        .bind(new_hash)
        .bind(auth.user_id.to_string())
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM sessions WHERE user_id = ? AND id != ?")
        .bind(auth.user_id.to_string())
        .bind(auth.session_id.to_string())
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

/// `GET /api/v1/sessions`
pub async fn sessions(
    State(state): State<AppState>,
    auth: AuthUser,
) -> AppResult<Json<Vec<Session>>> {
    let rows: Vec<(String, String, i64, i64)> = sqlx::query_as(
        "SELECT id, device_name, created_at, last_seen_at FROM sessions
         WHERE user_id = ? ORDER BY last_seen_at DESC",
    )
    .bind(auth.user_id.to_string())
    .fetch_all(&state.db.read)
    .await?;
    let sessions = rows
        .into_iter()
        .map(|(id, device_name, created_at_ms, last_seen_at_ms)| {
            let id = parse_uuid(&id)?;
            Ok(Session {
                id,
                device_name,
                created_at_ms,
                last_seen_at_ms,
                current: id == auth.session_id,
            })
        })
        .collect::<anyhow::Result<_>>()?;
    Ok(Json(sessions))
}

/// `DELETE /api/v1/sessions/{session_id}`: signs out one of your own devices.
pub async fn revoke_session(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(session_id): Path<Uuid>,
) -> AppResult<StatusCode> {
    let result = sqlx::query("DELETE FROM sessions WHERE id = ? AND user_id = ?")
        .bind(session_id.to_string())
        .bind(auth.user_id.to_string())
        .execute(&state.db.write)
        .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::not_found("session not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}
