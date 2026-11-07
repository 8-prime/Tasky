#![allow(dead_code)]

use std::sync::atomic::{AtomicU32, Ordering};

use reqwest::{Client, StatusCode};
use serde::{Serialize, de::DeserializeOwned};
use tasky_api::*;
use tasky_server::{AppState, Config};
use uuid::Uuid;

pub struct TestServer {
    pub base: String,
    pub state: AppState,
    pub http: Client,
    _shutdown: tokio::sync::watch::Sender<bool>,
    _dir: tempfile::TempDir,
}

impl TestServer {
    pub async fn start() -> Self {
        Self::start_with_token(None).await
    }

    pub async fn start_with_token(setup_token: Option<String>) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let config = Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            database: dir.path().join("tasky.db").to_string_lossy().into_owned(),
            setup_token,
            invite_ttl_hours: 48,
        };
        let (app, state, shutdown) = tasky_server::build(config).await.unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        TestServer {
            base: format!("http://{addr}"),
            state,
            http: Client::new(),
            _shutdown: shutdown,
            _dir: dir,
        }
    }

    pub fn url(&self, path: &str) -> String {
        format!("{}/api/v1{path}", self.base)
    }

    pub fn setup_token(&self) -> String {
        self.state.setup_token.peek()
    }

    pub async fn post<B: Serialize, R: DeserializeOwned>(
        &self,
        token: Option<&str>,
        path: &str,
        body: &B,
    ) -> Result<R, (StatusCode, ErrorBody)> {
        let mut req = self.http.post(self.url(path)).json(body);
        if let Some(t) = token {
            req = req.bearer_auth(t);
        }
        decode(req.send().await.unwrap()).await
    }

    pub async fn get<R: DeserializeOwned>(
        &self,
        token: &str,
        path: &str,
    ) -> Result<R, (StatusCode, ErrorBody)> {
        decode(
            self.http
                .get(self.url(path))
                .bearer_auth(token)
                .send()
                .await
                .unwrap(),
        )
        .await
    }

    /// Creates a household and returns its first member.
    pub async fn create_household(&self, username: &str) -> TestUser {
        let res: AuthResponse = self
            .post(
                None,
                "/auth/create-household",
                &CreateHouseholdRequest {
                    setup_token: self.setup_token(),
                    household_name: "Home".into(),
                    username: username.into(),
                    display_name: username.into(),
                    password: "correct horse".into(),
                    device_name: "test".into(),
                },
            )
            .await
            .expect("create household");
        TestUser::new(res)
    }

    /// Invites a new member into `inviter`'s household.
    pub async fn join(&self, inviter: &TestUser, username: &str) -> TestUser {
        let invite = self.invite(inviter).await;
        let res: AuthResponse = self
            .post(
                None,
                "/auth/join",
                &JoinHouseholdRequest {
                    invite_code: invite.code,
                    username: username.into(),
                    display_name: username.into(),
                    password: "correct horse".into(),
                    device_name: "test".into(),
                },
            )
            .await
            .expect("join household");
        TestUser::new(res)
    }

    pub async fn invite(&self, inviter: &TestUser) -> InviteResponse {
        self.post(
            Some(&inviter.token),
            &format!("/households/{}/invites", inviter.household),
            &(),
        )
        .await
        .expect("create invite")
    }

    pub async fn sync(&self, client: &TestUser, since: Seq, ops: Vec<Op>) -> SyncResponse {
        self.try_sync(client, since, ops, None).await.expect("sync")
    }

    pub async fn try_sync(
        &self,
        client: &TestUser,
        since: Seq,
        ops: Vec<Op>,
        limit: Option<i32>,
    ) -> Result<SyncResponse, (StatusCode, ErrorBody)> {
        self.post(
            Some(&client.token),
            &format!("/households/{}/sync", client.household),
            &SyncRequest { since, ops, limit },
        )
        .await
    }

    /// Full state of the household as the given client sees it (non-deleted entities).
    pub async fn snapshot(&self, client: &TestUser) -> Vec<Entity> {
        let mut all = Vec::new();
        let mut since = 0;
        loop {
            let res = self
                .try_sync(client, since, vec![], Some(2000))
                .await
                .unwrap();
            all.extend(res.changes);
            since = res.cursor;
            if !res.has_more {
                break;
            }
        }
        all.retain(|e| !e.deleted);
        all.sort_by_key(|e| e.id);
        all
    }
}

async fn decode<R: DeserializeOwned>(res: reqwest::Response) -> Result<R, (StatusCode, ErrorBody)> {
    let status = res.status();
    if status.is_success() {
        let bytes = res.bytes().await.unwrap();
        // Unit-returning endpoints have empty bodies.
        let bytes = if bytes.is_empty() {
            b"null".as_slice().into()
        } else {
            bytes
        };
        Ok(serde_json::from_slice(&bytes).expect("decode response"))
    } else {
        Err((status, res.json().await.expect("error body")))
    }
}

/// A signed-in test user with a local HLC.
pub struct TestUser {
    pub token: String,
    pub user: User,
    pub household: Uuid,
    node: String,
    counter: AtomicU32,
}

impl TestUser {
    fn new(res: AuthResponse) -> Self {
        TestUser {
            token: res.session_token,
            user: res.user,
            household: res.households[0].id,
            node: format!("{:016x}", rand_node()),
            counter: AtomicU32::new(0),
        }
    }

    /// A fresh HLC that is newer than all previous ones from this client.
    pub fn hlc(&self) -> String {
        let n = self.counter.fetch_add(1, Ordering::SeqCst);
        hlc_at(chrono::Utc::now().timestamp_millis(), n, &self.node)
    }

    pub fn op(&self, entity_id: Uuid, action: OpAction) -> Op {
        Op {
            entity_id,
            hlc: self.hlc(),
            action,
        }
    }
}

pub fn hlc_at(ms: i64, counter: u32, node: &str) -> String {
    format!("{ms:012x}-{counter:04x}-{node}")
}

fn rand_node() -> u64 {
    Uuid::new_v4().as_u64_pair().0
}

pub fn item(name: &str) -> OpAction {
    OpAction::Create(EntityData::Item(ItemData {
        name: name.into(),
        category: None,
        default_unit: None,
    }))
}

pub fn contribution(
    list: Uuid,
    item: Uuid,
    amount: f64,
    unit: &str,
    plan_entry: Option<Uuid>,
) -> OpAction {
    OpAction::Create(EntityData::Contribution(ContributionData {
        list_id: list,
        item_id: item,
        amount: Some(amount),
        unit: Some(unit.into()),
        plan_entry_id: plan_entry,
        note: None,
        purchased: false,
        purchased_by: None,
        archived: false,
    }))
}

pub fn find(entities: &[Entity], id: Uuid) -> Option<&Entity> {
    entities.iter().find(|e| e.id == id)
}

pub fn default_list(entities: &[Entity]) -> Uuid {
    entities
        .iter()
        .find(|e| matches!(&e.data, EntityData::List(l) if l.name == "Groceries"))
        .expect("default list")
        .id
}

pub fn as_contribution(e: &Entity) -> &ContributionData {
    match &e.data {
        EntityData::Contribution(c) => c,
        other => panic!("not a contribution: {other:?}"),
    }
}

pub fn as_item(e: &Entity) -> &ItemData {
    match &e.data {
        EntityData::Item(i) => i,
        other => panic!("not an item: {other:?}"),
    }
}
