mod common;

use common::*;
use tasky_api::*;
use uuid::Uuid;

#[tokio::test]
async fn contributions_add_up_and_check_off_independently() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let list = default_list(&s.snapshot(&anna).await);

    let pasta = Uuid::now_v7();
    let lasagne = Uuid::now_v7();
    let salad = Uuid::now_v7();
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(pasta, item("Pasta")),
                anna.op(lasagne, contribution(list, pasta, 500.0, "g", None)),
                anna.op(salad, contribution(list, pasta, 250.0, "g", None)),
            ],
        )
        .await;
    assert!(res.rejected.is_empty(), "{:?}", res.rejected);

    // Bob checks off both contributions on the "Pasta" line...
    s.sync(
        &bob,
        0,
        vec![
            bob.op(
                lasagne,
                OpAction::SetContribution(ContributionField::Purchased(true)),
            ),
            bob.op(
                salad,
                OpAction::SetContribution(ContributionField::Purchased(true)),
            ),
        ],
    )
    .await;
    // ...while Anna adds another meal's pasta. Only that one stays open.
    let pesto = Uuid::now_v7();
    s.sync(
        &anna,
        0,
        vec![anna.op(pesto, contribution(list, pasta, 200.0, "g", None))],
    )
    .await;

    let snap = s.snapshot(&anna).await;
    let open: f64 = snap
        .iter()
        .filter(|e| e.data.kind() == EntityKind::Contribution)
        .map(as_contribution)
        .filter(|c| c.item_id == pasta && !c.purchased && !c.archived)
        .filter_map(|c| c.amount)
        .sum();
    assert_eq!(open, 200.0);
    let checked = as_contribution(find(&snap, lasagne).unwrap());
    assert!(checked.purchased);
    assert_eq!(
        checked.purchased_by,
        Some(bob.user.id),
        "server sets purchased_by"
    );
}

#[tokio::test]
async fn concurrent_edits_to_different_fields_both_survive() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let list = default_list(&s.snapshot(&anna).await);
    let milk = Uuid::now_v7();
    let c = Uuid::now_v7();
    s.sync(
        &anna,
        0,
        vec![
            anna.op(milk, item("Milk")),
            anna.op(c, contribution(list, milk, 1.0, "l", None)),
        ],
    )
    .await;

    // Both edit offline, then sync in either order.
    let bob_check = bob.op(
        c,
        OpAction::SetContribution(ContributionField::Purchased(true)),
    );
    let anna_amount = anna.op(
        c,
        OpAction::SetContribution(ContributionField::Amount(Some(2.0))),
    );
    s.sync(&bob, 0, vec![bob_check]).await;
    s.sync(&anna, 0, vec![anna_amount]).await;

    let snap = s.snapshot(&bob).await;
    let contrib = as_contribution(find(&snap, c).unwrap());
    assert!(contrib.purchased);
    assert_eq!(contrib.amount, Some(2.0));
}

#[tokio::test]
async fn stale_writes_lose_regardless_of_arrival_order() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let milk = Uuid::now_v7();
    s.sync(&anna, 0, vec![anna.op(milk, item("Milk"))]).await;

    let now = chrono::Utc::now().timestamp_millis();
    let older = Op {
        entity_id: milk,
        hlc: hlc_at(now - 60_000, 0, "bob"),
        action: OpAction::SetItem(ItemField::Name("Oat milk (old edit)".into())),
    };
    let newer = Op {
        entity_id: milk,
        hlc: hlc_at(now, 0, "anna"),
        action: OpAction::SetItem(ItemField::Name("Oat milk".into())),
    };
    s.sync(&anna, 0, vec![newer.clone()]).await;
    let before = s.sync(&bob, 0, vec![]).await.cursor;
    // Bob was offline: his older edit arrives last but must not win, and must not
    // produce a change.
    let res = s.sync(&bob, before, vec![older]).await;
    assert!(res.rejected.is_empty());
    assert!(res.changes.is_empty());
    // Re-sending an applied op is a no-op too.
    let res = s.sync(&anna, before, vec![newer]).await;
    assert!(res.changes.is_empty());

    let snap = s.snapshot(&anna).await;
    assert_eq!(as_item(find(&snap, milk).unwrap()).name, "Oat milk");
}

#[tokio::test]
async fn deletes_are_tombstones_and_newer_creates_revive() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let list = default_list(&s.snapshot(&anna).await);
    let eggs = Uuid::now_v7();
    let c = Uuid::now_v7();
    let create = anna.op(c, contribution(list, eggs, 6.0, "pcs", None));
    s.sync(&anna, 0, vec![anna.op(eggs, item("Eggs")), create.clone()])
        .await;
    let cursor = s.sync(&anna, 0, vec![]).await.cursor;

    s.sync(
        &anna,
        0,
        vec![anna.op(c, OpAction::Delete(EntityKind::Contribution))],
    )
    .await;
    // Incremental sync reports the tombstone; a full sync omits it.
    let res = s.sync(&anna, cursor, vec![]).await;
    assert!(find(&res.changes, c).unwrap().deleted);
    assert!(find(&s.snapshot(&anna).await, c).is_none());

    // Updates to deleted entities and stale re-creates are ignored.
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(
                    c,
                    OpAction::SetContribution(ContributionField::Amount(Some(12.0))),
                ),
                create,
            ],
        )
        .await;
    assert!(res.rejected.is_empty());
    assert!(find(&s.snapshot(&anna).await, c).is_none());

    // A newer create brings it back (e.g. adding the same planned meal again).
    s.sync(
        &anna,
        0,
        vec![anna.op(c, contribution(list, eggs, 6.0, "pcs", None))],
    )
    .await;
    assert!(find(&s.snapshot(&anna).await, c).is_some());
}

#[tokio::test]
async fn duplicate_items_are_merged_into_one() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let list = default_list(&s.snapshot(&anna).await);

    // Both add "Milk" while offline, each under their own ID.
    let anna_milk = Uuid::now_v7();
    let bob_milk = Uuid::now_v7();
    let bob_c = Uuid::now_v7();
    s.sync(&anna, 0, vec![anna.op(anna_milk, item("Milk"))])
        .await;
    let res = s
        .sync(
            &bob,
            0,
            vec![
                bob.op(bob_milk, item("  milk ")),
                bob.op(bob_c, contribution(list, bob_milk, 1.0, "l", None)),
            ],
        )
        .await;
    assert!(res.rejected.is_empty(), "{:?}", res.rejected);
    assert_eq!(res.aliases.len(), 1);
    assert_eq!(
        (res.aliases[0].alias_id, res.aliases[0].target_id),
        (bob_milk, anna_milk)
    );

    let snap = s.snapshot(&anna).await;
    let items: Vec<_> = snap
        .iter()
        .filter(|e| e.data.kind() == EntityKind::Item)
        .collect();
    assert_eq!(items.len(), 1);
    assert_eq!(
        as_contribution(find(&snap, bob_c).unwrap()).item_id,
        anna_milk
    );

    // Later ops that still use the alias ID reach the real item.
    s.sync(
        &bob,
        0,
        vec![bob.op(
            bob_milk,
            OpAction::SetItem(ItemField::Category(Some("Dairy".into()))),
        )],
    )
    .await;
    let snap = s.snapshot(&anna).await;
    assert_eq!(
        as_item(find(&snap, anna_milk).unwrap()).category.as_deref(),
        Some("Dairy")
    );
}

#[tokio::test]
async fn invalid_ops_are_rejected_individually() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let list = default_list(&s.snapshot(&anna).await);
    let future = hlc_at(chrono::Utc::now().timestamp_millis() + 3_600_000, 0, "n");

    let good = Uuid::now_v7();
    let ops = vec![
        Op {
            entity_id: Uuid::now_v7(),
            hlc: "garbage".into(),
            action: item("A"),
        },
        Op {
            entity_id: Uuid::now_v7(),
            hlc: future,
            action: item("B"),
        },
        anna.op(Uuid::now_v7(), item("   ")),
        anna.op(
            Uuid::now_v7(),
            contribution(list, Uuid::now_v7(), 1.0, "g", None),
        ),
        anna.op(list, OpAction::SetItem(ItemField::Name("x".into()))),
        anna.op(
            Uuid::now_v7(),
            OpAction::SetList(ListField::Name("x".into())),
        ),
        anna.op(good, item("Bread")),
        anna.op(Uuid::now_v7(), contribution(list, good, -1.0, "g", None)),
        anna.op(
            Uuid::now_v7(),
            OpAction::Create(EntityData::PlanEntry(PlanEntryData {
                date: "2026-02-30".into(),
                slot: MealSlot::Dinner,
                owner_user_id: None,
                meal_id: None,
                free_text: Some("Pizza".into()),
                servings: None,
                note: None,
            })),
        ),
        anna.op(
            Uuid::now_v7(),
            OpAction::Create(EntityData::PlanEntry(PlanEntryData {
                date: "2026-02-03".into(),
                slot: MealSlot::Dinner,
                owner_user_id: Some(Uuid::now_v7()),
                meal_id: None,
                free_text: Some("Pizza".into()),
                servings: None,
                note: None,
            })),
        ),
    ];
    let res = s.sync(&anna, 0, ops).await;
    let codes: Vec<_> = res.rejected.iter().map(|r| (r.index, r.code)).collect();
    assert_eq!(
        codes,
        [
            (0, RejectCode::InvalidHlc),
            (1, RejectCode::ClockSkew),
            (2, RejectCode::Validation),
            (3, RejectCode::InvalidReference),
            (4, RejectCode::KindMismatch),
            (5, RejectCode::NotFound),
            (7, RejectCode::Validation),
            (8, RejectCode::Validation),
            (9, RejectCode::InvalidReference),
        ]
    );
    // The valid op in the middle was still applied.
    assert!(find(&s.snapshot(&anna).await, good).is_some());
}

#[tokio::test]
async fn meal_planning_flow() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let list = default_list(&s.snapshot(&anna).await);

    let (meal, tomato, ing) = (Uuid::now_v7(), Uuid::now_v7(), Uuid::now_v7());
    let (household_dinner, bobs_lunch) = (Uuid::now_v7(), Uuid::now_v7());
    let plan = |owner, slot| {
        OpAction::Create(EntityData::PlanEntry(PlanEntryData {
            date: "2026-11-09".into(),
            slot,
            owner_user_id: owner,
            meal_id: Some(meal),
            free_text: None,
            servings: Some(2),
            note: None,
        }))
    };
    let contribution_id = Uuid::new_v5(
        &CONTRIBUTION_NAMESPACE,
        format!("{household_dinner}:{ing}").as_bytes(),
    );
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(tomato, item("Tomatoes")),
                anna.op(
                    meal,
                    OpAction::Create(EntityData::Meal(MealData {
                        name: "Shakshuka".into(),
                        default_servings: 2,
                        notes: None,
                        url: None,
                    })),
                ),
                anna.op(
                    ing,
                    OpAction::Create(EntityData::MealIngredient(MealIngredientData {
                        meal_id: meal,
                        item_id: tomato,
                        amount: Some(400.0),
                        unit: Some("g".into()),
                        note: None,
                    })),
                ),
                anna.op(household_dinner, plan(None, MealSlot::Dinner)),
                anna.op(bobs_lunch, plan(Some(bob.user.id), MealSlot::Lunch)),
                anna.op(
                    contribution_id,
                    contribution(list, tomato, 400.0, "g", Some(household_dinner)),
                ),
            ],
        )
        .await;
    assert!(res.rejected.is_empty(), "{:?}", res.rejected);

    // Bob adds the same planned meal to the list at the same time: same deterministic ID,
    // so it isn't counted twice.
    let res = s
        .sync(
            &bob,
            0,
            vec![bob.op(
                contribution_id,
                contribution(list, tomato, 400.0, "g", Some(household_dinner)),
            )],
        )
        .await;
    assert!(res.rejected.is_empty());
    let contributions = s
        .snapshot(&bob)
        .await
        .into_iter()
        .filter(|e| e.data.kind() == EntityKind::Contribution)
        .count();
    assert_eq!(contributions, 1);
}

#[tokio::test]
async fn pagination_and_cursor() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let ops = (0..7)
        .map(|i| anna.op(Uuid::now_v7(), item(&format!("Item {i}"))))
        .collect();
    s.sync(&anna, 0, ops).await;

    let mut since = 0;
    let mut pages = 0;
    let mut seen = 0;
    loop {
        let res = s.try_sync(&anna, since, vec![], Some(3)).await.unwrap();
        pages += 1;
        seen += res.changes.len();
        assert!(res.changes.iter().all(|e| e.seq > since));
        since = res.cursor;
        if !res.has_more {
            break;
        }
    }
    assert_eq!((pages, seen), (3, 8)); // 7 items + default list
    let res = s.sync(&anna, since, vec![]).await;
    assert!(res.changes.is_empty() && !res.has_more && !res.full_resync);
}

#[tokio::test]
async fn cursor_ahead_of_server_forces_full_resync() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let res = s.sync(&anna, 9_999, vec![]).await;
    assert!(res.full_resync);
    assert_eq!(res.changes.len(), 1);
}

/// Last-writer-wins must give the same result no matter the order ops arrive in.
#[tokio::test]
async fn final_state_is_independent_of_arrival_order() {
    use rand::{SeedableRng, seq::SliceRandom};

    let mut rng = rand::rngs::StdRng::seed_from_u64(7);
    for round in 0..5 {
        let base = chrono::Utc::now().timestamp_millis() - 100_000;
        let items: Vec<Uuid> = (0..3).map(|_| Uuid::now_v7()).collect();
        let mut ops = Vec::new();
        for (i, id) in items.iter().enumerate() {
            ops.push(Op {
                entity_id: *id,
                hlc: hlc_at(base, 0, "a"),
                action: item(&format!("item {i}")),
            });
        }
        // Random field updates and deletes with random timestamps from several "devices".
        let mut counter = 0;
        for _ in 0..40 {
            use rand::RngExt;
            let id = items[rng.random_range(0..items.len())];
            let node = ["a", "b", "c"][rng.random_range(0..3)];
            counter += 1;
            let hlc = hlc_at(base + rng.random_range(1..50_000), counter, node);
            let action = match rng.random_range(0..10) {
                0 => OpAction::Delete(EntityKind::Item),
                1 => item(&format!("revived {counter}")),
                2..=5 => OpAction::SetItem(ItemField::Category(Some(format!("cat {counter}")))),
                _ => OpAction::SetItem(ItemField::DefaultUnit(Some(format!("u{counter}")))),
            };
            ops.push(Op {
                entity_id: id,
                hlc,
                action,
            });
        }

        let mut results = Vec::new();
        for _ in 0..3 {
            let s = TestServer::start().await;
            let anna = s.create_household("anna").await;
            // Creates must arrive first (a client never sends updates before its create).
            let (creates, rest) = ops.split_at(items.len());
            let mut shuffled = creates.to_vec();
            let mut tail = rest.to_vec();
            tail.shuffle(&mut rng);
            shuffled.extend(tail);
            for chunk in shuffled.chunks(7) {
                let res = s.sync(&anna, 0, chunk.to_vec()).await;
                assert!(res.rejected.is_empty(), "{:?}", res.rejected);
            }
            let state: Vec<_> = s
                .snapshot(&anna)
                .await
                .into_iter()
                .filter(|e| e.data.kind() == EntityKind::Item)
                .map(|e| (e.id, e.data))
                .collect();
            results.push(state);
        }
        assert_eq!(results[0], results[1], "round {round}");
        assert_eq!(results[1], results[2], "round {round}");
    }
}

fn new_list(name: &str, kind: ListKind) -> OpAction {
    OpAction::Create(EntityData::List(ListData {
        name: name.into(),
        kind: Some(kind),
        body: None,
    }))
}

fn entry(list: Uuid, text: &str) -> OpAction {
    OpAction::Create(EntityData::ListEntry(ListEntryData {
        list_id: list,
        text: text.into(),
        checked: false,
        checked_by: Some(Uuid::now_v7()), // ignored: server-owned
    }))
}

#[tokio::test]
async fn default_list_is_a_grocery_list() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let snap = s.snapshot(&anna).await;
    match &snap[0].data {
        EntityData::List(l) => assert_eq!(l.kind, Some(ListKind::Grocery)),
        other => panic!("{other:?}"),
    }
}

#[tokio::test]
async fn checklist_entries_sync_and_record_who_checked() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let (list, e1, e2) = (Uuid::now_v7(), Uuid::now_v7(), Uuid::now_v7());
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(list, new_list("Weekend", ListKind::Checklist)),
                anna.op(e1, entry(list, "Call plumber")),
                anna.op(e2, entry(list, "Water plants")),
            ],
        )
        .await;
    assert!(res.rejected.is_empty(), "{:?}", res.rejected);

    // Concurrent: Bob checks an entry while Anna renames it; both survive.
    s.sync(
        &bob,
        0,
        vec![bob.op(e1, OpAction::SetListEntry(ListEntryField::Checked(true)))],
    )
    .await;
    s.sync(
        &anna,
        0,
        vec![anna.op(
            e1,
            OpAction::SetListEntry(ListEntryField::Text("Call the plumber".into())),
        )],
    )
    .await;

    let snap = s.snapshot(&anna).await;
    let EntityData::ListEntry(e) = &find(&snap, e1).unwrap().data else {
        panic!()
    };
    assert!(e.checked);
    assert_eq!(e.checked_by, Some(bob.user.id));
    assert_eq!(e.text, "Call the plumber");
    let EntityData::ListEntry(e) = &find(&snap, e2).unwrap().data else {
        panic!()
    };
    assert_eq!(e.checked_by, None, "client-sent checked_by is ignored");

    // Checklist entries don't touch the grocery item catalog.
    assert!(!snap.iter().any(|e| e.data.kind() == EntityKind::Item));

    // Unchecking clears checked_by; deleting removes the entry.
    s.sync(
        &bob,
        0,
        vec![
            bob.op(e1, OpAction::SetListEntry(ListEntryField::Checked(false))),
            bob.op(e2, OpAction::Delete(EntityKind::ListEntry)),
        ],
    )
    .await;
    let snap = s.snapshot(&anna).await;
    let EntityData::ListEntry(e) = &find(&snap, e1).unwrap().data else {
        panic!()
    };
    assert_eq!((e.checked, e.checked_by), (false, None));
    assert!(find(&snap, e2).is_none());

    // Entries must reference a list in this household and have text.
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(Uuid::now_v7(), entry(Uuid::now_v7(), "orphan")),
                anna.op(Uuid::now_v7(), entry(list, "  ")),
            ],
        )
        .await;
    let codes: Vec<_> = res.rejected.iter().map(|r| r.code).collect();
    assert_eq!(
        codes,
        [RejectCode::InvalidReference, RejectCode::Validation]
    );
}

#[tokio::test]
async fn notes_store_text_with_last_writer_wins() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let note = Uuid::now_v7();
    s.sync(
        &anna,
        0,
        vec![anna.op(note, new_list("Ideas", ListKind::Note))],
    )
    .await;

    let now = chrono::Utc::now().timestamp_millis();
    let older = Op {
        entity_id: note,
        hlc: hlc_at(now - 1000, 0, "anna"),
        action: OpAction::SetList(ListField::Body(Some("first draft".into()))),
    };
    let newer = Op {
        entity_id: note,
        hlc: hlc_at(now, 0, "bob"),
        action: OpAction::SetList(ListField::Body(Some(
            "Paint the hallway\nFix the bike".into(),
        ))),
    };
    s.sync(&bob, 0, vec![newer]).await;
    s.sync(&anna, 0, vec![older]).await;

    let snap = s.snapshot(&anna).await;
    let EntityData::List(l) = &find(&snap, note).unwrap().data else {
        panic!()
    };
    assert_eq!(l.kind, Some(ListKind::Note));
    assert_eq!(l.body.as_deref(), Some("Paint the hallway\nFix the bike"));
}

#[tokio::test]
async fn lists_from_older_clients_default_to_no_kind() {
    // An older app sends a list without `kind`/`body`; it's accepted and stored as-is
    // (clients treat a missing kind as grocery).
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let list = Uuid::now_v7();
    let body = serde_json::json!({
        "since": 0,
        "ops": [{
            "entity_id": list,
            "hlc": anna.hlc(),
            "action": {"type": "create", "value": {"type": "list", "value": {"name": "Old app list"}}}
        }]
    });
    let res: SyncResponse = s
        .post(
            Some(&anna.token),
            &format!("/households/{}/sync", anna.household),
            &body,
        )
        .await
        .unwrap();
    assert!(res.rejected.is_empty());
    let EntityData::List(l) = &find(&res.changes, list).unwrap().data else {
        panic!()
    };
    assert_eq!((l.kind, l.body.as_deref()), (None, None));
}
