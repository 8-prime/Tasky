mod common;

use common::*;
use futures::StreamExt;
use tasky_api::*;
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest};
use uuid::Uuid;

async fn next_event<S>(ws: &mut S) -> LiveEvent
where
    S: futures::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        let msg = tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
            .await
            .expect("timed out waiting for event")
            .unwrap()
            .unwrap();
        if let Message::Text(text) = msg {
            return serde_json::from_str(&text).unwrap();
        }
    }
}

#[tokio::test]
async fn members_get_live_change_signals() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;

    let url = format!(
        "{}/households/{}/live",
        s.url("").replace("http", "ws"),
        anna.household
    );
    let mut req = url.clone().into_client_request().unwrap();
    req.headers_mut().insert(
        "authorization",
        format!("Bearer {}", anna.token).parse().unwrap(),
    );
    let (mut ws, _) = tokio_tungstenite::connect_async(req).await.unwrap();

    let hello = next_event(&mut ws).await;
    assert_eq!(hello.kind, LiveEventKind::Hello);

    let res = s
        .sync(&bob, 0, vec![bob.op(Uuid::now_v7(), item("Butter"))])
        .await;
    let event = next_event(&mut ws).await;
    assert_eq!(event.kind, LiveEventKind::Changed);
    assert_eq!(event.cursor, res.cursor);
    assert!(event.cursor > hello.cursor);

    // Stale or no-op syncs don't wake anyone up; a new member does.
    s.sync(&bob, 0, vec![]).await;
    s.join(&anna, "carl").await;
    assert_eq!(
        next_event(&mut ws).await.kind,
        LiveEventKind::HouseholdChanged
    );

    // Unauthenticated or non-member connections are refused.
    let eve = s.create_household("eve").await;
    let mut req = url.into_client_request().unwrap();
    req.headers_mut().insert(
        "authorization",
        format!("Bearer {}", eve.token).parse().unwrap(),
    );
    assert!(tokio_tungstenite::connect_async(req).await.is_err());
}
