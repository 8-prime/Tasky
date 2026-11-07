use std::time::Duration;

use axum::{Json, extract::State};
use sqlx::SqliteConnection;
use tasky_api::{
    AuthResponse, CreateHouseholdRequest, EntityData, ErrorCode, JoinHouseholdRequest, ListData,
    ListKind, LiveEventKind, LoginRequest, Op, OpAction, User,
};
use uuid::Uuid;

use crate::{
    AppState,
    auth::{
        AuthUser, create_session, hash_password, households_of, invalid_credentials,
        validate_display_name, validate_name, validate_password, validate_username,
        verify_password,
    },
    error::{AppError, AppResult},
    invite::hash_invite_code,
    sync::{Ctx, apply_ops, current_seq},
    util::{now_ms, server_hlc},
};

/// Name of the list every new household starts with.
pub const DEFAULT_LIST_NAME: &str = "Groceries";

async fn ensure_username_free(conn: &mut SqliteConnection, username: &str) -> AppResult<()> {
    let taken: Option<(i64,)> = sqlx::query_as("SELECT 1 FROM users WHERE username = ?")
        .bind(username)
        .fetch_optional(&mut *conn)
        .await?;
    match taken {
        Some(_) => Err(AppError::new(
            ErrorCode::UsernameTaken,
            "username is already taken",
        )),
        None => Ok(()),
    }
}

async fn insert_user(
    conn: &mut SqliteConnection,
    username: &str,
    display_name: &str,
    password_hash: &str,
) -> AppResult<User> {
    ensure_username_free(conn, username).await?;
    let user = User {
        id: Uuid::now_v7(),
        username: username.to_owned(),
        display_name: display_name.to_owned(),
    };
    sqlx::query(
        "INSERT INTO users (id, username, display_name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(user.id.to_string())
    .bind(&user.username)
    .bind(&user.display_name)
    .bind(password_hash)
    .bind(now_ms())
    .execute(&mut *conn)
    .await?;
    Ok(user)
}

async fn add_member(
    conn: &mut SqliteConnection,
    household_id: Uuid,
    user_id: Uuid,
    role: &str,
) -> AppResult<()> {
    sqlx::query(
        "INSERT INTO memberships (household_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)",
    )
    .bind(household_id.to_string())
    .bind(user_id.to_string())
    .bind(role)
    .bind(now_ms())
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// `POST /api/v1/auth/create-household`: needs the setup token from the server log.
pub async fn create_household(
    State(state): State<AppState>,
    Json(req): Json<CreateHouseholdRequest>,
) -> AppResult<Json<AuthResponse>> {
    let household_name = validate_name(&req.household_name, "household name", 100)?;
    let username = validate_username(&req.username)?;
    let display_name = validate_display_name(&req.display_name)?;
    validate_password(&req.password)?;
    let password_hash = hash_password(req.password).await?;

    let Some(used_token) = state.setup_token.consume(&req.setup_token) else {
        tokio::time::sleep(Duration::from_millis(500)).await;
        return Err(AppError::new(
            ErrorCode::InvalidSetupToken,
            "invalid household creation token",
        ));
    };

    let result: AppResult<AuthResponse> = async {
        let mut tx = state.db.write.begin().await?;
        let user = insert_user(&mut tx, &username, &display_name, &password_hash).await?;
        let household_id = Uuid::now_v7();
        sqlx::query("INSERT INTO households (id, name, seq, created_at) VALUES (?, ?, 0, ?)")
            .bind(household_id.to_string())
            .bind(&household_name)
            .bind(now_ms())
            .execute(&mut *tx)
            .await?;
        add_member(&mut tx, household_id, user.id, "owner").await?;

        let ctx = Ctx {
            household_id,
            user_id: user.id,
            now_ms: now_ms(),
        };
        let default_list = Op {
            entity_id: Uuid::now_v7(),
            hlc: server_hlc(),
            action: OpAction::Create(EntityData::List(ListData {
                name: DEFAULT_LIST_NAME.into(),
                kind: Some(ListKind::Grocery),
                body: None,
            })),
        };
        let outcome = apply_ops(&mut tx, &ctx, &[default_list]).await?;
        if !outcome.rejected.is_empty() {
            return Err(anyhow::anyhow!("default list rejected: {:?}", outcome.rejected).into());
        }

        let session_token = create_session(&mut tx, user.id, &req.device_name).await?;
        let households = households_of(&mut tx, user.id).await?;
        tx.commit().await?;
        Ok(AuthResponse {
            session_token,
            user,
            households,
        })
    }
    .await;

    match result {
        Ok(response) => {
            tracing::info!(username = %response.user.username, "household created");
            state.setup_token.announce();
            Ok(Json(response))
        }
        Err(e) => {
            state.setup_token.restore(used_token);
            Err(e)
        }
    }
}

/// `POST /api/v1/auth/join`: creates an account in the household that issued the invite.
pub async fn join(
    State(state): State<AppState>,
    Json(req): Json<JoinHouseholdRequest>,
) -> AppResult<Json<AuthResponse>> {
    let username = validate_username(&req.username)?;
    let display_name = validate_display_name(&req.display_name)?;
    validate_password(&req.password)?;
    let password_hash = hash_password(req.password).await?;
    let code_hash = hash_invite_code(&req.invite_code);

    let mut tx = state.db.write.begin().await?;
    let invite: Option<(String,)> = sqlx::query_as(
        "SELECT household_id FROM invites WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
    )
    .bind(&code_hash)
    .bind(now_ms())
    .fetch_optional(&mut *tx)
    .await?;
    let Some((household_id,)) = invite else {
        drop(tx);
        tokio::time::sleep(Duration::from_millis(500)).await;
        return Err(AppError::new(
            ErrorCode::InvalidInvite,
            "invite code is invalid, used or expired",
        ));
    };
    let household_id = Uuid::parse_str(&household_id).map_err(anyhow::Error::from)?;

    let user = insert_user(&mut tx, &username, &display_name, &password_hash).await?;
    add_member(&mut tx, household_id, user.id, "member").await?;
    sqlx::query("UPDATE invites SET used_at = ?, used_by = ? WHERE code_hash = ?")
        .bind(now_ms())
        .bind(user.id.to_string())
        .bind(&code_hash)
        .execute(&mut *tx)
        .await?;
    let session_token = create_session(&mut tx, user.id, &req.device_name).await?;
    let households = households_of(&mut tx, user.id).await?;
    let cursor = current_seq(&mut tx, household_id).await?;
    tx.commit().await?;

    tracing::info!(username = %user.username, %household_id, "member joined household");
    state
        .live
        .publish(household_id, LiveEventKind::HouseholdChanged, cursor);
    Ok(Json(AuthResponse {
        session_token,
        user,
        households,
    }))
}

/// `POST /api/v1/auth/login`
pub async fn login(
    State(state): State<AppState>,
    Json(req): Json<LoginRequest>,
) -> AppResult<Json<AuthResponse>> {
    let row: Option<(String, String, String, String)> = sqlx::query_as(
        "SELECT id, username, display_name, password_hash FROM users WHERE username = ?",
    )
    .bind(req.username.trim())
    .fetch_optional(&state.db.read)
    .await?;

    let verified = match &row {
        Some((_, _, _, hash)) => verify_password(req.password, hash.clone()).await?,
        None => false,
    };
    let Some((id, username, display_name, _)) = row.filter(|_| verified) else {
        tokio::time::sleep(Duration::from_millis(500)).await;
        return Err(invalid_credentials());
    };

    let user = User {
        id: Uuid::parse_str(&id).map_err(anyhow::Error::from)?,
        username,
        display_name,
    };
    let mut tx = state.db.write.begin().await?;
    let session_token = create_session(&mut tx, user.id, &req.device_name).await?;
    let households = households_of(&mut tx, user.id).await?;
    tx.commit().await?;
    Ok(Json(AuthResponse {
        session_token,
        user,
        households,
    }))
}

/// `POST /api/v1/auth/logout`: ends the current session.
pub async fn logout(State(state): State<AppState>, user: AuthUser) -> AppResult<()> {
    sqlx::query("DELETE FROM sessions WHERE id = ?")
        .bind(user.session_id.to_string())
        .execute(&state.db.write)
        .await?;
    Ok(())
}
