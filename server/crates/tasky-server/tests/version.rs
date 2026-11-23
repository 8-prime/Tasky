mod common;

use common::*;
use reqwest::Client;
use tasky_api::*;

fn versions(res: &reqwest::Response) -> (String, String) {
    let header = |name| {
        res.headers()
            .get(name)
            .map(|v| v.to_str().unwrap().to_owned())
            .unwrap_or_default()
    };
    (
        header(SERVER_API_VERSION_HEADER),
        header(MIN_CLIENT_API_VERSION_HEADER),
    )
}

#[tokio::test]
async fn server_info_states_the_versions_and_needs_no_header() {
    let s = TestServer::start().await;
    let res = Client::new()
        .get(s.url("/server-info"))
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 200);
    assert_eq!(
        versions(&res),
        (API_VERSION.to_string(), MIN_CLIENT_API_VERSION.to_string())
    );
    let info: ServerInfo = res.json().await.unwrap();
    assert_eq!(info.api_version, API_VERSION);
    assert_eq!(info.min_client_api_version, Some(MIN_CLIENT_API_VERSION));
}

#[tokio::test]
async fn outdated_apps_are_told_to_update() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let sync_url = s.url(&format!("/households/{}/sync", anna.household));
    let body = SyncRequest {
        since: 0,
        ops: vec![],
        limit: None,
    };

    // Apps from before the header count as version 1; so does an explicit old version.
    let old = MIN_CLIENT_API_VERSION - 1;
    let requests = [
        Client::new().post(&sync_url),
        Client::new()
            .post(&sync_url)
            .header(API_VERSION_HEADER, old.to_string()),
    ];
    for req in requests {
        let res = req
            .bearer_auth(&anna.token)
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(res.status(), 426);
        assert_eq!(versions(&res).1, MIN_CLIENT_API_VERSION.to_string());
        let err: ErrorBody = res.json().await.unwrap();
        assert_eq!(err.error, ErrorCode::ClientOutdated);
        assert!(err.message.contains("update the app"), "{}", err.message);
    }

    // Login is refused too: an outdated app must not get as far as reading data.
    let res = Client::new()
        .post(s.url("/auth/login"))
        .json(&LoginRequest {
            username: "anna".into(),
            password: "correct horse".into(),
            device_name: "old phone".into(),
        })
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 426);

    let res = Client::new()
        .post(&sync_url)
        .header(API_VERSION_HEADER, "two")
        .bearer_auth(&anna.token)
        .json(&body)
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 400);
}

#[tokio::test]
async fn current_and_newer_apps_are_served_and_see_the_versions() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    for v in [API_VERSION, API_VERSION + 1] {
        let res = Client::new()
            .post(s.url(&format!("/households/{}/sync", anna.household)))
            .header(API_VERSION_HEADER, v.to_string())
            .bearer_auth(&anna.token)
            .json(&SyncRequest {
                since: 0,
                ops: vec![],
                limit: None,
            })
            .send()
            .await
            .unwrap();
        assert_eq!(res.status(), 200);
        assert_eq!(versions(&res).0, API_VERSION.to_string());
    }
    // Errors carry the versions as well.
    let res = Client::new()
        .get(s.url("/me"))
        .header(API_VERSION_HEADER, API_VERSION.to_string())
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 401);
    assert_eq!(versions(&res).0, API_VERSION.to_string());
}
