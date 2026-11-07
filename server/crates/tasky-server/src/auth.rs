use argon2::{
    Argon2,
    password_hash::{PasswordHasher, PasswordVerifier, phc::PasswordHash},
};
use axum::{extract::FromRequestParts, http::request::Parts};
use sqlx::SqliteConnection;
use tasky_api::{ErrorCode, Household};
use uuid::Uuid;

use crate::{
    AppState,
    error::{AppError, AppResult},
    util::{now_ms, parse_uuid, random_token, sha256_hex},
};

pub const MIN_PASSWORD_LEN: usize = 8;
const LAST_SEEN_RESOLUTION_MS: i64 = 60 * 60 * 1000;

pub async fn hash_password(password: String) -> AppResult<String> {
    tokio::task::spawn_blocking(move || {
        Argon2::default()
            .hash_password(password.as_bytes())
            .map(|h| h.to_string())
            .map_err(|e| anyhow::anyhow!("hashing password: {e}"))
    })
    .await
    .map_err(anyhow::Error::from)?
    .map_err(AppError::from)
}

pub async fn verify_password(password: String, hash: String) -> AppResult<bool> {
    let ok = tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash)
            .map(|parsed| {
                Argon2::default()
                    .verify_password(password.as_bytes(), &parsed)
                    .is_ok()
            })
            .unwrap_or(false)
    })
    .await
    .map_err(anyhow::Error::from)?;
    Ok(ok)
}

/// Creates a session for `user_id` and returns the bearer token (only its hash is stored).
pub async fn create_session(
    conn: &mut SqliteConnection,
    user_id: Uuid,
    device_name: &str,
) -> AppResult<String> {
    let token = random_token("tsk_s_", 32);
    let now = now_ms();
    sqlx::query(
        "INSERT INTO sessions (id, user_id, token_hash, device_name, created_at, last_seen_at)
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(Uuid::now_v7().to_string())
    .bind(user_id.to_string())
    .bind(sha256_hex(&token))
    .bind(clean_device_name(device_name))
    .bind(now)
    .bind(now)
    .execute(&mut *conn)
    .await?;
    Ok(token)
}

fn clean_device_name(name: &str) -> String {
    let name = name.trim();
    if name.is_empty() {
        "Unknown device".to_owned()
    } else {
        name.chars().take(100).collect()
    }
}

pub async fn households_of(
    conn: &mut SqliteConnection,
    user_id: Uuid,
) -> AppResult<Vec<Household>> {
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT h.id, h.name FROM households h
         JOIN memberships m ON m.household_id = h.id
         WHERE m.user_id = ? ORDER BY m.joined_at",
    )
    .bind(user_id.to_string())
    .fetch_all(&mut *conn)
    .await?;
    rows.into_iter()
        .map(|(id, name)| {
            Ok(Household {
                id: parse_uuid(&id)?,
                name,
            })
        })
        .collect()
}

/// An authenticated request (from `Authorization: Bearer <token>`).
#[derive(Debug, Clone, Copy)]
pub struct AuthUser {
    pub user_id: Uuid,
    pub session_id: Uuid,
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> AppResult<Self> {
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim)
            .ok_or_else(AppError::unauthorized)?;

        let row: Option<(String, String, i64)> =
            sqlx::query_as("SELECT id, user_id, last_seen_at FROM sessions WHERE token_hash = ?")
                .bind(sha256_hex(token))
                .fetch_optional(&state.db.read)
                .await?;
        let (session_id, user_id, last_seen) = row.ok_or_else(AppError::unauthorized)?;

        let now = now_ms();
        if now - last_seen > LAST_SEEN_RESOLUTION_MS {
            sqlx::query("UPDATE sessions SET last_seen_at = ? WHERE id = ?")
                .bind(now)
                .bind(&session_id)
                .execute(&state.db.write)
                .await?;
        }

        Ok(AuthUser {
            user_id: parse_uuid(&user_id)?,
            session_id: parse_uuid(&session_id)?,
        })
    }
}

impl AuthUser {
    /// Fails with 404 unless the user belongs to the household (so other households' IDs aren't revealed).
    pub async fn require_member(&self, state: &AppState, household_id: Uuid) -> AppResult<()> {
        let found: Option<(i64,)> =
            sqlx::query_as("SELECT 1 FROM memberships WHERE household_id = ? AND user_id = ?")
                .bind(household_id.to_string())
                .bind(self.user_id.to_string())
                .fetch_optional(&state.db.read)
                .await?;
        match found {
            Some(_) => Ok(()),
            None => Err(AppError::not_found("household not found")),
        }
    }
}

pub fn validate_username(username: &str) -> AppResult<String> {
    let username = username.trim();
    let ok_len = (3..=32).contains(&username.chars().count());
    let ok_chars = username
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || "._-".contains(c));
    if !ok_len || !ok_chars {
        return Err(AppError::validation(
            "username must be 3-32 characters of letters, digits, '.', '_' or '-'",
        ));
    }
    Ok(username.to_owned())
}

pub fn validate_password(password: &str) -> AppResult<()> {
    if password.chars().count() < MIN_PASSWORD_LEN {
        return Err(AppError::validation(format!(
            "password must be at least {MIN_PASSWORD_LEN} characters"
        )));
    }
    if password.len() > 1024 {
        return Err(AppError::validation("password is too long"));
    }
    Ok(())
}

pub fn validate_display_name(name: &str) -> AppResult<String> {
    validate_name(name, "display name", 64)
}

pub fn validate_name(name: &str, what: &str, max: usize) -> AppResult<String> {
    let name = name.trim();
    if name.is_empty() {
        return Err(AppError::validation(format!("{what} must not be empty")));
    }
    if name.chars().count() > max {
        return Err(AppError::validation(format!(
            "{what} must be at most {max} characters"
        )));
    }
    Ok(name.to_owned())
}

pub fn invalid_credentials() -> AppError {
    AppError::new(
        ErrorCode::InvalidCredentials,
        "invalid username or password",
    )
}
