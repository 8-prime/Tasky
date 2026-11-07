//! Tasky server library: exposes [`build`] so tests can run the full app in-process.

pub mod auth;
pub mod config;
pub mod db;
pub mod error;
pub mod hlc;
pub mod invite;
pub mod live;
pub mod routes;
pub mod setup_token;
pub mod sync;
pub mod util;

use std::sync::Arc;

use axum::Router;
use tokio::sync::watch;

pub use config::Config;

#[derive(Clone)]
pub struct AppState {
    pub db: db::Db,
    pub config: Arc<Config>,
    pub setup_token: Arc<setup_token::SetupToken>,
    pub live: Arc<live::LiveHub>,
    /// Flips to `true` on shutdown so long-lived WebSocket connections close.
    pub shutdown: watch::Receiver<bool>,
}

/// Opens the database, runs migrations and builds the router.
/// Returns the state (for tests) and the sender that triggers shutdown.
pub async fn build(config: Config) -> anyhow::Result<(Router, AppState, watch::Sender<bool>)> {
    let db = db::Db::open(&config.database).await?;
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let state = AppState {
        db,
        setup_token: Arc::new(setup_token::SetupToken::new(config.setup_token.clone())),
        config: Arc::new(config),
        live: Arc::new(live::LiveHub::default()),
        shutdown: shutdown_rx,
    };
    Ok((routes::router(state.clone()), state, shutdown_tx))
}
