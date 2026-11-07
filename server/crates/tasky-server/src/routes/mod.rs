mod account;
mod auth;
mod household;
mod sync;

use axum::{
    Json, Router,
    routing::{delete, get, post},
};
use tasky_api::{API_VERSION, ServerInfo};

use crate::{AppState, live, util::now_ms};

pub fn router(state: AppState) -> Router {
    let api = Router::new()
        .route("/server-info", get(server_info))
        .route("/auth/create-household", post(auth::create_household))
        .route("/auth/join", post(auth::join))
        .route("/auth/login", post(auth::login))
        .route("/auth/logout", post(auth::logout))
        .route("/me", get(account::me).patch(account::update_me))
        .route("/me/password", post(account::change_password))
        .route("/sessions", get(account::sessions))
        .route("/sessions/{session_id}", delete(account::revoke_session))
        .route(
            "/households/{household_id}",
            get(household::get).patch(household::update),
        )
        .route(
            "/households/{household_id}/invites",
            post(household::create_invite),
        )
        .route("/households/{household_id}/sync", post(sync::sync))
        .route("/households/{household_id}/live", get(live::live));

    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .nest("/api/v1", api)
        .layer(tower_http::trace::TraceLayer::new_for_http())
        .with_state(state)
}

async fn server_info() -> Json<ServerInfo> {
    Json(ServerInfo {
        name: "tasky".into(),
        version: env!("CARGO_PKG_VERSION").into(),
        api_version: API_VERSION,
        server_time_ms: now_ms(),
    })
}
