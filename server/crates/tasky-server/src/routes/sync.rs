use axum::{
    Json,
    extract::{Path, State},
};
use tasky_api::{LiveEventKind, SyncRequest, SyncResponse};
use uuid::Uuid;

use crate::{
    AppState,
    auth::AuthUser,
    error::{AppError, AppResult},
    sync::{self, Ctx, DEFAULT_PAGE, MAX_OPS_PER_REQUEST, MAX_PAGE},
    util::now_ms,
};

/// `POST /api/v1/households/{household_id}/sync`
pub async fn sync(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(household_id): Path<Uuid>,
    Json(req): Json<SyncRequest>,
) -> AppResult<Json<SyncResponse>> {
    auth.require_member(&state, household_id).await?;
    if req.ops.len() > MAX_OPS_PER_REQUEST {
        return Err(AppError::validation(format!(
            "at most {MAX_OPS_PER_REQUEST} ops per request"
        )));
    }
    let limit = req
        .limit
        .map_or(DEFAULT_PAGE, |l| i64::from(l).clamp(1, MAX_PAGE));

    let mut rejected = Vec::new();
    if !req.ops.is_empty() {
        let ctx = Ctx {
            household_id,
            user_id: auth.user_id,
            now_ms: now_ms(),
        };
        let mut tx = state.db.write.begin().await?;
        let outcome = sync::apply_ops(&mut tx, &ctx, &req.ops).await?;
        let cursor = sync::current_seq(&mut tx, household_id).await?;
        tx.commit().await?;
        if outcome.changed {
            state
                .live
                .publish(household_id, LiveEventKind::Changed, cursor);
        }
        rejected = outcome.rejected;
    }

    let mut tx = state.db.read.begin().await?;
    let changes = sync::fetch_changes(&mut tx, household_id, req.since, limit).await?;
    tx.commit().await?;

    Ok(Json(SyncResponse {
        changes: changes.entities,
        aliases: changes.aliases,
        rejected,
        cursor: changes.cursor,
        has_more: changes.has_more,
        full_resync: changes.full_resync,
        server_time_ms: now_ms(),
    }))
}
