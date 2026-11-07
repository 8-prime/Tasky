//! Live updates over WebSocket. Events only signal that something changed; clients
//! then fetch the data with the sync endpoint.

use std::{collections::HashMap, sync::Mutex, time::Duration};

use axum::{
    extract::{
        Path, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    response::Response,
};
use tasky_api::{LiveEvent, LiveEventKind};
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::{AppState, auth::AuthUser, error::AppResult, sync::current_seq};

const PING_INTERVAL: Duration = Duration::from_secs(30);

#[derive(Default)]
pub struct LiveHub {
    channels: Mutex<HashMap<Uuid, broadcast::Sender<LiveEvent>>>,
}

impl LiveHub {
    pub fn subscribe(&self, household_id: Uuid) -> broadcast::Receiver<LiveEvent> {
        let mut channels = self.channels.lock().unwrap();
        channels
            .entry(household_id)
            .or_insert_with(|| broadcast::channel(64).0)
            .subscribe()
    }

    pub fn publish(&self, household_id: Uuid, kind: LiveEventKind, cursor: i64) {
        let mut channels = self.channels.lock().unwrap();
        if let Some(tx) = channels.get(&household_id)
            && tx.send(LiveEvent { kind, cursor }).is_err()
        {
            // Nobody is listening any more.
            channels.remove(&household_id);
        }
    }
}

/// `GET /api/v1/households/{household_id}/live`
pub async fn live(
    State(state): State<AppState>,
    user: AuthUser,
    Path(household_id): Path<Uuid>,
    ws: WebSocketUpgrade,
) -> AppResult<Response> {
    user.require_member(&state, household_id).await?;
    Ok(ws.on_upgrade(move |socket| async move {
        if let Err(e) = run(state, household_id, socket).await {
            tracing::debug!(error = ?e, "live connection ended");
        }
    }))
}

async fn send(socket: &mut WebSocket, kind: LiveEventKind, cursor: i64) -> anyhow::Result<()> {
    let text = serde_json::to_string(&LiveEvent { kind, cursor })?;
    socket.send(Message::Text(text.into())).await?;
    Ok(())
}

async fn head(state: &AppState, household_id: Uuid) -> anyhow::Result<i64> {
    let mut conn = state.db.read.acquire().await?;
    Ok(current_seq(&mut conn, household_id).await?)
}

async fn run(state: AppState, household_id: Uuid, mut socket: WebSocket) -> anyhow::Result<()> {
    // Subscribe before reading the cursor so nothing in between is missed.
    let mut rx = state.live.subscribe(household_id);
    let mut shutdown = state.shutdown.clone();
    send(
        &mut socket,
        LiveEventKind::Hello,
        head(&state, household_id).await?,
    )
    .await?;

    let mut ping = tokio::time::interval(PING_INTERVAL);
    ping.tick().await;
    loop {
        tokio::select! {
            event = rx.recv() => match event {
                Ok(event) => send(&mut socket, event.kind, event.cursor).await?,
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    let cursor = head(&state, household_id).await?;
                    send(&mut socket, LiveEventKind::Changed, cursor).await?;
                    send(&mut socket, LiveEventKind::HouseholdChanged, cursor).await?;
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            msg = socket.recv() => match msg {
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                Some(Ok(_)) => {}
            },
            _ = ping.tick() => socket.send(Message::Ping(Vec::new().into())).await?,
            Ok(()) = shutdown.changed() => break,
        }
    }
    let _ = socket.send(Message::Close(None)).await;
    Ok(())
}
