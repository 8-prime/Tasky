mod common;

use common::*;
use reqwest::StatusCode;
use tasky_api::*;

fn create_req(token: &str, username: &str) -> CreateHouseholdRequest {
    CreateHouseholdRequest {
        setup_token: token.into(),
        household_name: "Home".into(),
        username: username.into(),
        display_name: "Anna".into(),
        password: "correct horse".into(),
        device_name: "Pixel".into(),
    }
}

#[tokio::test]
async fn server_info_is_public() {
    let s = TestServer::start().await;
    let info: ServerInfo = s
        .http
        .get(s.url("/server-info"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(info.api_version, API_VERSION);
    assert_eq!(
        s.http
            .get(format!("{}/healthz", s.base))
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
}

#[tokio::test]
async fn setup_token_is_required_and_single_use() {
    let s = TestServer::start().await;
    let err = s
        .post::<_, AuthResponse>(
            None,
            "/auth/create-household",
            &create_req("tsk_wrong", "anna"),
        )
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::InvalidSetupToken);

    let token = s.setup_token();
    let res: AuthResponse = s
        .post(None, "/auth/create-household", &create_req(&token, "anna"))
        .await
        .unwrap();
    assert_eq!(res.households.len(), 1);
    assert_eq!(res.user.username, "anna");

    // Token rotated: the old one no longer works, the new one does.
    let err = s
        .post::<_, AuthResponse>(None, "/auth/create-household", &create_req(&token, "bob"))
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::InvalidSetupToken);
    assert_ne!(s.setup_token(), token);
    s.post::<_, AuthResponse>(
        None,
        "/auth/create-household",
        &create_req(&s.setup_token(), "bob"),
    )
    .await
    .unwrap();
}

#[tokio::test]
async fn failed_creation_does_not_burn_the_token() {
    let s = TestServer::start().await;
    s.create_household("anna").await;
    let token = s.setup_token();
    let err = s
        .post::<_, AuthResponse>(None, "/auth/create-household", &create_req(&token, "ANNA"))
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::UsernameTaken);
    assert_eq!(s.setup_token(), token);
}

#[tokio::test]
async fn fixed_setup_token_is_reusable() {
    let s = TestServer::start_with_token(Some("my-fixed-token".into())).await;
    for name in ["anna", "bob"] {
        s.post::<_, AuthResponse>(
            None,
            "/auth/create-household",
            &create_req("my-fixed-token", name),
        )
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn new_household_has_a_default_list() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let snapshot = s.snapshot(&anna).await;
    assert_eq!(snapshot.len(), 1);
    default_list(&snapshot);
}

#[tokio::test]
async fn invites_are_single_use() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let invite = s.invite(&anna).await;
    let join = |username: &str, code: &str| JoinHouseholdRequest {
        invite_code: code.into(),
        username: username.into(),
        display_name: username.into(),
        password: "correct horse".into(),
        device_name: "phone".into(),
    };
    // Lowercase and without the dash still works.
    let messy = invite.code.to_lowercase().replace('-', "");
    let bob: AuthResponse = s
        .post(None, "/auth/join", &join("bob", &messy))
        .await
        .unwrap();
    assert_eq!(bob.households[0].id, anna.household);

    let err = s
        .post::<_, AuthResponse>(None, "/auth/join", &join("carl", &invite.code))
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::InvalidInvite);

    let details: HouseholdDetails = s
        .get(&anna.token, &format!("/households/{}", anna.household))
        .await
        .unwrap();
    let names: Vec<_> = details
        .members
        .iter()
        .map(|m| (m.username.as_str(), m.role))
        .collect();
    assert_eq!(names, [("anna", Role::Owner), ("bob", Role::Member)]);
}

#[tokio::test]
async fn login_logout_and_sessions() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;

    let bad = LoginRequest {
        username: "anna".into(),
        password: "nope nope".into(),
        device_name: "x".into(),
    };
    let err = s
        .post::<_, AuthResponse>(None, "/auth/login", &bad)
        .await
        .unwrap_err();
    assert_eq!(err.0, StatusCode::UNAUTHORIZED);
    assert_eq!(err.1.error, ErrorCode::InvalidCredentials);

    let good = LoginRequest {
        username: "ANNA".into(),
        password: "correct horse".into(),
        device_name: "Tablet".into(),
    };
    let tablet: AuthResponse = s.post(None, "/auth/login", &good).await.unwrap();
    assert_eq!(tablet.households[0].id, anna.household);

    let sessions: Vec<Session> = s.get(&tablet.session_token, "/sessions").await.unwrap();
    assert_eq!(sessions.len(), 2);
    assert_eq!(sessions.iter().filter(|s| s.current).count(), 1);

    // Changing the password signs out every other session.
    let change = ChangePasswordRequest {
        current_password: "correct horse".into(),
        new_password: "battery staple".into(),
    };
    s.post::<_, ()>(Some(&tablet.session_token), "/me/password", &change)
        .await
        .unwrap();
    let err = s.get::<MeResponse>(&anna.token, "/me").await.unwrap_err();
    assert_eq!(err.0, StatusCode::UNAUTHORIZED);

    s.post::<_, ()>(Some(&tablet.session_token), "/auth/logout", &())
        .await
        .unwrap();
    let err = s
        .get::<MeResponse>(&tablet.session_token, "/me")
        .await
        .unwrap_err();
    assert_eq!(err.0, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn other_households_are_invisible() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let eve = s.create_household("eve").await;

    let err = s
        .get::<HouseholdDetails>(&eve.token, &format!("/households/{}", anna.household))
        .await
        .unwrap_err();
    assert_eq!(err.0, StatusCode::NOT_FOUND);

    let err = s
        .post::<_, SyncResponse>(
            Some(&eve.token),
            &format!("/households/{}/sync", anna.household),
            &SyncRequest {
                since: 0,
                ops: vec![],
                limit: None,
            },
        )
        .await
        .unwrap_err();
    assert_eq!(err.0, StatusCode::NOT_FOUND);

    // Eve can't touch Anna's entities, even by ID through her own household.
    let anna_list = default_list(&s.snapshot(&anna).await);
    let res = s
        .sync(
            &eve,
            0,
            vec![eve.op(
                anna_list,
                OpAction::SetList(ListField::Name("pwned".into())),
            )],
        )
        .await;
    assert_eq!(res.rejected[0].code, RejectCode::NotFound);
}
