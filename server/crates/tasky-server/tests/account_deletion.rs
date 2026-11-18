mod common;

use common::*;
use reqwest::StatusCode;
use tasky_api::*;
use uuid::Uuid;

fn delete_req(password: &str) -> DeleteAccountRequest {
    DeleteAccountRequest {
        password: password.into(),
    }
}

fn login(username: &str) -> LoginRequest {
    LoginRequest {
        username: username.into(),
        password: "correct horse".into(),
        device_name: "x".into(),
    }
}

fn plan_entry(owner: Option<Uuid>, text: &str) -> OpAction {
    OpAction::Create(EntityData::PlanEntry(PlanEntryData {
        date: "2026-11-20".into(),
        slot: MealSlot::Dinner,
        owner_user_id: owner,
        meal_id: None,
        free_text: Some(text.into()),
        servings: None,
        note: None,
    }))
}

async fn household_count(s: &TestServer) -> i64 {
    let (n,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM households")
        .fetch_one(&s.state.db.read)
        .await
        .unwrap();
    n
}

#[tokio::test]
async fn wrong_password_keeps_the_account() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let err = s
        .post::<_, ()>(Some(&anna.token), "/me/delete", &delete_req("nope nope"))
        .await
        .unwrap_err();
    assert_eq!(err.0, StatusCode::UNAUTHORIZED);
    assert_eq!(err.1.error, ErrorCode::InvalidCredentials);
    s.get::<MeResponse>(&anna.token, "/me").await.unwrap();
}

#[tokio::test]
async fn last_member_takes_the_household_along() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    s.sync(&anna, 0, vec![anna.op(Uuid::now_v7(), item("Milk"))])
        .await;

    s.post::<_, ()>(
        Some(&anna.token),
        "/me/delete",
        &delete_req("correct horse"),
    )
    .await
    .unwrap();

    let err = s.get::<MeResponse>(&anna.token, "/me").await.unwrap_err();
    assert_eq!(err.1.error, ErrorCode::Unauthorized);
    let err = s
        .post::<_, AuthResponse>(None, "/auth/login", &login("anna"))
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::InvalidCredentials);
    assert_eq!(household_count(&s).await, 0);
    let (entities,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM entities")
        .fetch_one(&s.state.db.read)
        .await
        .unwrap();
    assert_eq!(entities, 0);

    // The username is free again.
    s.create_household("anna").await;
}

#[tokio::test]
async fn others_keep_the_household_and_shared_data() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;

    let personal = Uuid::now_v7();
    let shared = Uuid::now_v7();
    let bobs = Uuid::now_v7();
    let expense = Uuid::now_v7();
    s.sync(
        &anna,
        0,
        vec![
            anna.op(personal, plan_entry(Some(anna.user.id), "Anna's lunch")),
            anna.op(shared, plan_entry(None, "Pizza night")),
            anna.op(bobs, plan_entry(Some(bob.user.id), "Bob's lunch")),
            anna.op(
                expense,
                OpAction::Create(EntityData::Expense(ExpenseData {
                    description: "Rent".into(),
                    amount_minor: 100_000,
                    currency: "EUR".into(),
                    date: "2026-11-01".into(),
                    paid_by: anna.user.id,
                    split_among: vec![anna.user.id, bob.user.id],
                    category: None,
                    note: None,
                    kind: None,
                })),
            ),
        ],
    )
    .await;

    s.post::<_, ()>(
        Some(&anna.token),
        "/me/delete",
        &delete_req("correct horse"),
    )
    .await
    .unwrap();

    let details: HouseholdDetails = s
        .get(&bob.token, &format!("/households/{}", bob.household))
        .await
        .unwrap();
    let members: Vec<_> = details
        .members
        .iter()
        .map(|m| (m.username.as_str(), m.role))
        .collect();
    // Bob inherits ownership.
    assert_eq!(members, [("bob", Role::Owner)]);

    let snapshot = s.snapshot(&bob).await;
    assert!(
        find(&snapshot, personal).is_none(),
        "personal plan entry removed"
    );
    assert!(find(&snapshot, shared).is_some());
    assert!(find(&snapshot, bobs).is_some());
    match &find(&snapshot, expense).expect("expense kept").data {
        EntityData::Expense(e) => assert_eq!(e.paid_by, anna.user.id),
        other => panic!("not an expense: {other:?}"),
    }

    // Bob's own sync keeps working afterwards.
    s.sync(&bob, 0, vec![bob.op(Uuid::now_v7(), item("Bread"))])
        .await;
}

#[tokio::test]
async fn web_page_deletes_with_username_and_password() {
    let s = TestServer::start().await;
    s.create_household("anna").await;
    let page = format!("{}/delete-account", s.base);

    let form = s.http.get(&page).send().await.unwrap();
    assert_eq!(form.status(), 200);
    let html = form.text().await.unwrap();
    assert!(html.contains(r#"<form method="post""#));
    assert!(html.contains("mailto:admin@example.com"));

    let wrong = s
        .http
        .post(&page)
        .header("content-type", "application/x-www-form-urlencoded")
        .body("username=anna&password=nope+nope")
        .send()
        .await
        .unwrap();
    assert_eq!(wrong.status(), 401);
    assert!(
        wrong
            .text()
            .await
            .unwrap()
            .contains("Wrong username or password")
    );
    s.post::<_, AuthResponse>(None, "/auth/login", &login("anna"))
        .await
        .unwrap();

    let ok = s
        .http
        .post(&page)
        .header("content-type", "application/x-www-form-urlencoded")
        .body("username=Anna&password=correct+horse")
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    assert!(ok.text().await.unwrap().contains("Account deleted"));
    let err = s
        .post::<_, AuthResponse>(None, "/auth/login", &login("anna"))
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::InvalidCredentials);
}

#[tokio::test]
async fn privacy_policy_is_public() {
    let s = TestServer::start().await;
    let res = s
        .http
        .get(format!("{}/privacy", s.base))
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 200);
    let html = res.text().await.unwrap();
    assert!(html.contains("Privacy policy"));
    assert!(html.contains(r#"href="/delete-account""#));
}

#[tokio::test]
async fn demo_seed_creates_and_resets_the_review_account() {
    let s = TestServer::start().await;
    let other = s.create_household("anna").await;
    tasky_server::demo::seed_demo(&s.state.db, "playreview", "review pass")
        .await
        .unwrap();

    let res: AuthResponse = s
        .post(
            None,
            "/auth/login",
            &LoginRequest {
                username: "playreview".into(),
                password: "review pass".into(),
                device_name: "x".into(),
            },
        )
        .await
        .unwrap();
    let household = res.households[0].id;
    let details: HouseholdDetails = s
        .get(&res.session_token, &format!("/households/{household}"))
        .await
        .unwrap();
    assert_eq!(details.members.len(), 2);
    let sync: SyncResponse = s
        .post(
            Some(&res.session_token),
            &format!("/households/{household}/sync"),
            &SyncRequest {
                since: 0,
                ops: vec![],
                limit: None,
            },
        )
        .await
        .unwrap();
    let count = |kind: EntityKind| {
        sync.changes
            .iter()
            .filter(|e| e.data.kind() == kind)
            .count()
    };
    assert_eq!(count(EntityKind::List), 3);
    assert_eq!(count(EntityKind::Expense), 4);
    assert!(count(EntityKind::PlanEntry) > 0);

    // Seeding again starts over: old sessions end, one demo household remains, others are untouched.
    tasky_server::demo::seed_demo(&s.state.db, "playreview", "review pass")
        .await
        .unwrap();
    let err = s
        .get::<MeResponse>(&res.session_token, "/me")
        .await
        .unwrap_err();
    assert_eq!(err.1.error, ErrorCode::Unauthorized);
    assert_eq!(household_count(&s).await, 2);
    s.get::<MeResponse>(&other.token, "/me").await.unwrap();
}
