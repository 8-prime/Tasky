//! Pins the JSON wire format: the Android client and docs/API.md rely on these exact shapes.

use serde_json::json;
use tasky_api::*;
use uuid::Uuid;

const ID: Uuid = Uuid::from_u128(0x0193a4f1_c2d0_7000_8000_000000000001);

#[test]
fn create_op() {
    let op = Op {
        entity_id: ID,
        hlc: "0193a4f1c2d0-0000-7f3a9c01e5b24d88".into(),
        action: OpAction::Create(EntityData::Item(ItemData {
            name: "Milk".into(),
            category: Some("Dairy".into()),
            default_unit: None,
        })),
    };
    assert_eq!(
        serde_json::to_value(&op).unwrap(),
        json!({
            "entity_id": "0193a4f1-c2d0-7000-8000-000000000001",
            "hlc": "0193a4f1c2d0-0000-7f3a9c01e5b24d88",
            "action": {"type": "create", "value": {
                "type": "item", "value": {"name": "Milk", "category": "Dairy", "default_unit": null}
            }}
        })
    );
}

#[test]
fn set_and_delete_ops() {
    let set = OpAction::SetContribution(ContributionField::Purchased(true));
    assert_eq!(
        serde_json::to_value(&set).unwrap(),
        json!({"type": "set_contribution", "value": {"type": "purchased", "value": true}})
    );
    let clear = OpAction::SetItem(ItemField::Category(None));
    assert_eq!(
        serde_json::to_value(&clear).unwrap(),
        json!({"type": "set_item", "value": {"type": "category", "value": null}})
    );
    let delete = OpAction::Delete(EntityKind::MealIngredient);
    assert_eq!(
        serde_json::to_value(&delete).unwrap(),
        json!({"type": "delete", "value": "meal_ingredient"})
    );
}

#[test]
fn optional_fields_may_be_omitted_by_clients() {
    // kotlinx.serialization leaves out null fields that have defaults.
    let data: EntityData =
        serde_json::from_value(json!({"type": "item", "value": {"name": "Eggs"}})).unwrap();
    assert_eq!(
        data,
        EntityData::Item(ItemData {
            name: "Eggs".into(),
            category: None,
            default_unit: None
        })
    );
    let req: SyncRequest = serde_json::from_value(json!({"since": 0})).unwrap();
    assert!(req.ops.is_empty() && req.limit.is_none());
}

#[test]
fn live_event() {
    let ev = LiveEvent {
        kind: LiveEventKind::HouseholdChanged,
        cursor: 42,
    };
    assert_eq!(
        serde_json::to_value(&ev).unwrap(),
        json!({"kind": "household_changed", "cursor": 42})
    );
}

#[test]
fn contribution_namespace_ids_are_stable() {
    // Clients must reproduce this exact value (UUIDv5 of "<plan_entry_id>:<meal_ingredient_id>").
    let plan_entry = Uuid::from_u128(1);
    let ingredient = Uuid::from_u128(2);
    let name = format!("{plan_entry}:{ingredient}");
    assert_eq!(
        name,
        "00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000002"
    );
    assert_eq!(
        CONTRIBUTION_NAMESPACE.to_string(),
        "6d1f3c2a-9b8e-4f6d-a1c3-5e7b9d2f4a10"
    );
}

#[test]
fn contribution_id_example() {
    let id = Uuid::new_v5(
        &CONTRIBUTION_NAMESPACE,
        b"00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000002",
    );
    // Test vector for clients, also listed in docs/API.md.
    assert_eq!(id.to_string(), "79f48edd-396a-5e27-b339-9c3997d57a61");
}
