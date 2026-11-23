mod common;

use common::*;
use tasky_api::*;
use tasky_server::categories::{builtin_id, upgrade_households};
use uuid::Uuid;

fn categories(snap: &[Entity]) -> Vec<(Uuid, &CategoryData)> {
    snap.iter()
        .filter_map(|e| match &e.data {
            EntityData::Category(c) => Some((e.id, c)),
            _ => None,
        })
        .collect()
}

fn as_store(e: &Entity) -> &StoreData {
    match &e.data {
        EntityData::Store(s) => s,
        other => panic!("not a store: {other:?}"),
    }
}

fn item_in(name: &str, category_id: Option<Uuid>, legacy: Option<&str>) -> OpAction {
    OpAction::Create(EntityData::Item(ItemData {
        name: name.into(),
        category: legacy.map(Into::into),
        default_unit: None,
        category_id,
    }))
}

fn new_category(name: &str) -> OpAction {
    OpAction::Create(EntityData::Category(CategoryData {
        name: name.into(),
        key: None,
    }))
}

fn new_store(name: &str, order: Vec<Uuid>) -> OpAction {
    OpAction::Create(EntityData::Store(StoreData {
        name: name.into(),
        category_order: order,
    }))
}

#[tokio::test]
async fn new_households_start_with_the_builtin_categories() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let snap = s.snapshot(&anna).await;
    let cats = categories(&snap);
    assert_eq!(cats.len(), BUILTIN_CATEGORIES.len());
    for (key, name) in BUILTIN_CATEGORIES {
        let id = builtin_id(anna.household, key);
        let (_, c) = cats
            .iter()
            .find(|(i, _)| *i == id)
            .expect("builtin category");
        assert_eq!(c.key.as_deref(), Some(*key));
        assert_eq!(c.name, *name);
    }
}

#[tokio::test]
async fn items_reference_categories_and_can_go_back_to_automatic() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let dairy = builtin_id(anna.household, "dairy");
    let oat_milk = Uuid::now_v7();
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(oat_milk, item_in("Oat milk", Some(dairy), None)),
                // Unknown category, and a non-category id.
                anna.op(Uuid::now_v7(), item_in("Ghost", Some(Uuid::now_v7()), None)),
                anna.op(
                    oat_milk,
                    OpAction::SetItem(ItemField::CategoryId(Some(oat_milk))),
                ),
            ],
        )
        .await;
    let codes: Vec<_> = res.rejected.iter().map(|r| (r.index, r.code)).collect();
    assert_eq!(
        codes,
        [
            (1, RejectCode::InvalidReference),
            (2, RejectCode::InvalidReference)
        ]
    );
    let snap = s.snapshot(&anna).await;
    assert_eq!(
        as_item(find(&snap, oat_milk).unwrap()).category_id,
        Some(dairy)
    );

    s.sync(
        &anna,
        0,
        vec![anna.op(oat_milk, OpAction::SetItem(ItemField::CategoryId(None)))],
    )
    .await;
    let snap = s.snapshot(&anna).await;
    assert_eq!(as_item(find(&snap, oat_milk).unwrap()).category_id, None);
}

#[tokio::test]
async fn categories_can_be_added_renamed_and_deleted() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let asian = Uuid::now_v7();
    let snacks = builtin_id(anna.household, "snacks");
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(asian, new_category("Asian")),
                anna.op(
                    snacks,
                    OpAction::SetCategory(CategoryField::Name("Sweets".into())),
                ),
                anna.op(Uuid::now_v7(), new_category("  ")),
                anna.op(Uuid::now_v7(), new_category(&"x".repeat(65))),
            ],
        )
        .await;
    let codes: Vec<_> = res.rejected.iter().map(|r| (r.index, r.code)).collect();
    assert_eq!(
        codes,
        [(2, RejectCode::Validation), (3, RejectCode::Validation)]
    );
    let snap = s.snapshot(&anna).await;
    let cats = categories(&snap);
    let renamed = cats.iter().find(|(id, _)| *id == snacks).unwrap().1;
    assert_eq!(renamed.name, "Sweets");
    assert_eq!(renamed.key.as_deref(), Some("snacks"), "the key stays");
    assert!(cats.iter().any(|(id, c)| *id == asian && c.key.is_none()));

    s.sync(
        &anna,
        0,
        vec![anna.op(asian, OpAction::Delete(EntityKind::Category))],
    )
    .await;
    assert!(find(&s.snapshot(&anna).await, asian).is_none());
}

#[tokio::test]
async fn stores_keep_a_category_order() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let bob = s.join(&anna, "bob").await;
    let h = anna.household;
    let (produce, dairy, drinks) = (
        builtin_id(h, "produce"),
        builtin_id(h, "dairy"),
        builtin_id(h, "drinks"),
    );
    let aldi = Uuid::now_v7();
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(aldi, new_store("Aldi", vec![drinks, produce])),
                anna.op(Uuid::now_v7(), new_store("Dup", vec![drinks, drinks])),
                anna.op(Uuid::now_v7(), new_store("Bad", vec![Uuid::now_v7()])),
                anna.op(Uuid::now_v7(), new_store("", vec![])),
            ],
        )
        .await;
    let codes: Vec<_> = res.rejected.iter().map(|r| (r.index, r.code)).collect();
    assert_eq!(
        codes,
        [
            (1, RejectCode::Validation),
            (2, RejectCode::InvalidReference),
            (3, RejectCode::Validation),
        ]
    );

    // The order is replaced as a whole; the later edit wins. The name is a separate field.
    let now = chrono::Utc::now().timestamp_millis();
    let first = Op {
        entity_id: aldi,
        hlc: hlc_at(now - 1000, 0, "anna"),
        action: OpAction::SetStore(StoreField::CategoryOrder(vec![produce, dairy])),
    };
    let second = Op {
        entity_id: aldi,
        hlc: hlc_at(now, 0, "bob"),
        action: OpAction::SetStore(StoreField::CategoryOrder(vec![dairy, produce, drinks])),
    };
    s.sync(&bob, 0, vec![second]).await;
    s.sync(
        &anna,
        0,
        vec![
            first,
            anna.op(
                aldi,
                OpAction::SetStore(StoreField::Name("Aldi Süd".into())),
            ),
        ],
    )
    .await;
    let snap = s.snapshot(&anna).await;
    let store = as_store(find(&snap, aldi).unwrap());
    assert_eq!(store.name, "Aldi Süd");
    assert_eq!(store.category_order, vec![dairy, produce, drinks]);

    // Deleting a category doesn't break layouts that mention it.
    s.sync(
        &anna,
        0,
        vec![
            anna.op(dairy, OpAction::Delete(EntityKind::Category)),
            anna.op(aldi, OpAction::SetStore(StoreField::Name("Aldi".into()))),
        ],
    )
    .await;
    let snap = s.snapshot(&anna).await;
    assert_eq!(as_store(find(&snap, aldi).unwrap()).name, "Aldi");
}

#[tokio::test]
async fn old_households_get_categories_and_their_free_text_is_converted() {
    let s = TestServer::start().await;
    let anna = s.create_household("anna").await;
    let (apples, beans, noodles, tofu, salt) = (
        Uuid::now_v7(),
        Uuid::now_v7(),
        Uuid::now_v7(),
        Uuid::now_v7(),
        Uuid::now_v7(),
    );
    let res = s
        .sync(
            &anna,
            0,
            vec![
                anna.op(apples, item_in("Apples", None, Some("Produce"))),
                anna.op(beans, item_in("Beans", None, Some("cans"))),
                anna.op(noodles, item_in("Noodles", None, Some("Asian"))),
                anna.op(tofu, item_in("Tofu", None, Some(" asian "))),
                anna.op(salt, item_in("Salt", None, None)),
            ],
        )
        .await;
    assert!(res.rejected.is_empty(), "{:?}", res.rejected);

    // Pretend the household is from before categories: no categories and data version 0.
    sqlx::query("DELETE FROM entities WHERE household_id = ? AND kind = 'category'")
        .bind(anna.household.to_string())
        .execute(&s.state.db.write)
        .await
        .unwrap();
    sqlx::query("UPDATE households SET data_version = 0")
        .execute(&s.state.db.write)
        .await
        .unwrap();

    upgrade_households(&s.state.db).await.unwrap();
    // Running it again does nothing.
    upgrade_households(&s.state.db).await.unwrap();

    let snap = s.snapshot(&anna).await;
    let cats = categories(&snap);
    assert_eq!(cats.len(), BUILTIN_CATEGORIES.len() + 1);
    let category_of = |id| as_item(find(&snap, id).unwrap()).category_id;
    assert_eq!(
        category_of(apples),
        Some(builtin_id(anna.household, "produce"))
    );
    assert_eq!(
        category_of(beans),
        Some(builtin_id(anna.household, "canned"))
    );
    let asian = category_of(noodles).expect("custom category");
    assert_eq!(category_of(tofu), Some(asian), "same text, same category");
    let (_, c) = cats.iter().find(|(id, _)| *id == asian).unwrap();
    assert_eq!((c.name.as_str(), c.key.as_deref()), ("Asian", None));
    assert_eq!(category_of(salt), None);
}
