//! `tasky-server seed-demo`: a demo account with realistic sample data, for app-store reviewers.
//!
//! Every run starts over: the demo user and its partner are deleted (with their household) and
//! created again, so whatever a reviewer changed, deleted or renamed is reset, including the
//! password and the accounts themselves.

use anyhow::Context;
use chrono::{Days, NaiveDate};
use sqlx::SqliteConnection;
use tasky_api::{
    ContributionData, EntityData, ExpenseData, ExpenseKind, ItemData, ListData, ListEntryData,
    ListKind, MealData, MealIngredientData, MealSlot, Op, OpAction, PlanEntryData,
};
use uuid::Uuid;

use crate::{
    auth::{hash_password, validate_password, validate_username},
    db::Db,
    deletion::delete_user_in,
    sync::{Ctx, apply_ops},
    util::{now_ms, random_token, server_hlc},
};

pub const DEMO_HOUSEHOLD_NAME: &str = "Demo household";

/// Creates (or resets) the demo household. `username` signs in with `password`; a second
/// member `<username>-partner` (random password) makes balances and assignments meaningful.
pub async fn seed_demo(db: &Db, username: &str, password: &str) -> anyhow::Result<()> {
    let username = validate_username(username).map_err(|e| anyhow::anyhow!("{e}"))?;
    let partner_name = validate_username(&format!("{username}-partner"))
        .map_err(|e| anyhow::anyhow!("partner username: {e}"))?;
    validate_password(password).map_err(|e| anyhow::anyhow!("{e}"))?;
    let hash = hash_password(password.to_owned()).await?;
    let partner_hash = hash_password(random_token("", 24)).await?;

    let mut tx = db.write.begin().await?;
    for name in [&username, &partner_name] {
        let existing: Option<(String,)> = sqlx::query_as("SELECT id FROM users WHERE username = ?")
            .bind(name)
            .fetch_optional(&mut *tx)
            .await?;
        if let Some((id,)) = existing {
            delete_user_in(&mut tx, Uuid::parse_str(&id)?).await?;
        }
    }

    let demo = insert_user(&mut tx, &username, "Alex", &hash).await?;
    let partner = insert_user(&mut tx, &partner_name, "Sam", &partner_hash).await?;
    let household = Uuid::now_v7();
    sqlx::query("INSERT INTO households (id, name, seq, created_at) VALUES (?, ?, 0, ?)")
        .bind(household.to_string())
        .bind(DEMO_HOUSEHOLD_NAME)
        .bind(now_ms())
        .execute(&mut *tx)
        .await?;
    for (user, role) in [(demo, "owner"), (partner, "member")] {
        sqlx::query(
            "INSERT INTO memberships (household_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)",
        )
        .bind(household.to_string())
        .bind(user.to_string())
        .bind(role)
        .bind(now_ms())
        .execute(&mut *tx)
        .await?;
    }

    let today = chrono::Utc::now().date_naive();
    let ops = sample_ops(demo, partner, today);
    let ctx = Ctx {
        household_id: household,
        user_id: demo,
        now_ms: now_ms(),
    };
    let outcome = apply_ops(&mut tx, &ctx, &ops).await?;
    anyhow::ensure!(
        outcome.rejected.is_empty(),
        "sample data rejected: {:?}",
        outcome.rejected
    );
    tx.commit().await.context("saving the demo household")?;
    tracing::info!(%username, "demo household ready");
    Ok(())
}

async fn insert_user(
    conn: &mut SqliteConnection,
    username: &str,
    display_name: &str,
    hash: &str,
) -> anyhow::Result<Uuid> {
    let id = Uuid::now_v7();
    sqlx::query(
        "INSERT INTO users (id, username, display_name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(id.to_string())
    .bind(username)
    .bind(display_name)
    .bind(hash)
    .bind(now_ms())
    .execute(&mut *conn)
    .await?;
    Ok(id)
}

/// Collects create ops and hands out IDs for them.
struct Seed(Vec<Op>);

impl Seed {
    fn add(&mut self, data: EntityData) -> Uuid {
        let id = Uuid::now_v7();
        self.0.push(Op {
            entity_id: id,
            hlc: server_hlc(),
            action: OpAction::Create(data),
        });
        id
    }

    fn item(&mut self, name: &str, category: &str, unit: Option<&str>) -> Uuid {
        self.add(EntityData::Item(ItemData {
            name: name.into(),
            category: Some(category.into()),
            default_unit: unit.map(Into::into),
        }))
    }

    fn list(&mut self, name: &str, kind: ListKind, body: Option<&str>) -> Uuid {
        self.add(EntityData::List(ListData {
            name: name.into(),
            kind: Some(kind),
            body: body.map(Into::into),
        }))
    }

    fn contribution(&mut self, list: Uuid, item: Uuid, amount: f64, unit: &str, purchased: bool) {
        self.add(EntityData::Contribution(ContributionData {
            list_id: list,
            item_id: item,
            amount: Some(amount),
            unit: Some(unit.into()),
            plan_entry_id: None,
            note: None,
            purchased,
            purchased_by: None,
            archived: false,
        }));
    }

    fn entry(&mut self, list: Uuid, text: &str, checked: bool) {
        self.add(EntityData::ListEntry(ListEntryData {
            list_id: list,
            text: text.into(),
            checked,
            checked_by: None,
        }));
    }

    fn ingredient(&mut self, meal: Uuid, item: Uuid, amount: f64, unit: &str) {
        self.add(EntityData::MealIngredient(MealIngredientData {
            meal_id: meal,
            item_id: item,
            amount: Some(amount),
            unit: Some(unit.into()),
            note: None,
        }));
    }

    fn plan(
        &mut self,
        date: NaiveDate,
        slot: MealSlot,
        meal: Result<Uuid, &str>,
        owner: Option<Uuid>,
    ) {
        let (meal_id, free_text) = match meal {
            Ok(id) => (Some(id), None),
            Err(text) => (None, Some(text.into())),
        };
        self.add(EntityData::PlanEntry(PlanEntryData {
            date: date.to_string(),
            slot,
            owner_user_id: owner,
            meal_id,
            free_text,
            servings: None,
            note: None,
        }));
    }

    #[allow(clippy::too_many_arguments)]
    fn expense(
        &mut self,
        description: &str,
        cents: i64,
        date: NaiveDate,
        paid_by: Uuid,
        split_among: Vec<Uuid>,
        category: Option<&str>,
        kind: ExpenseKind,
    ) {
        self.add(EntityData::Expense(ExpenseData {
            description: description.into(),
            amount_minor: cents,
            currency: "EUR".into(),
            date: date.to_string(),
            paid_by,
            split_among,
            category: category.map(Into::into),
            note: None,
            kind: Some(kind),
        }));
    }
}

fn sample_ops(alex: Uuid, sam: Uuid, today: NaiveDate) -> Vec<Op> {
    let day = |offset: i64| {
        if offset >= 0 {
            today.checked_add_days(Days::new(offset as u64))
        } else {
            today.checked_sub_days(Days::new(offset.unsigned_abs()))
        }
        .unwrap_or(today)
    };
    let mut s = Seed(Vec::new());

    let milk = s.item("Milk", "Dairy", Some("l"));
    let eggs = s.item("Eggs", "Dairy", Some("pcs"));
    let parmesan = s.item("Parmesan", "Dairy", Some("g"));
    let bananas = s.item("Bananas", "Produce", Some("pcs"));
    let tomatoes = s.item("Tomatoes", "Produce", Some("g"));
    let garlic = s.item("Garlic", "Produce", Some("pcs"));
    let spaghetti = s.item("Spaghetti", "Pantry", Some("g"));
    let coffee = s.item("Coffee beans", "Pantry", Some("g"));

    let groceries = s.list("Groceries", ListKind::Grocery, None);
    s.contribution(groceries, milk, 2.0, "l", false);
    s.contribution(groceries, bananas, 6.0, "pcs", false);
    s.contribution(groceries, coffee, 500.0, "g", false);
    s.contribution(groceries, eggs, 10.0, "pcs", true);

    let chores = s.list("Weekend chores", ListKind::Checklist, None);
    s.entry(chores, "Water the plants", true);
    s.entry(chores, "Take out the recycling", false);
    s.entry(chores, "Clean the fridge", false);

    s.list(
        "House notes",
        ListKind::Note,
        Some("Recycling goes out on Tuesday evenings.\nSpare key is with the neighbours at number 12.\nBoiler service is due in March."),
    );

    let pasta = s.add(EntityData::Meal(MealData {
        name: "Spaghetti pomodoro".into(),
        default_servings: 2,
        notes: Some("Let the sauce simmer for 20 minutes.".into()),
        url: None,
    }));
    s.ingredient(pasta, spaghetti, 250.0, "g");
    s.ingredient(pasta, tomatoes, 400.0, "g");
    s.ingredient(pasta, garlic, 2.0, "pcs");
    s.ingredient(pasta, parmesan, 50.0, "g");

    s.plan(day(0), MealSlot::Dinner, Ok(pasta), None);
    s.plan(day(1), MealSlot::Lunch, Err("Leftovers"), None);
    s.plan(day(1), MealSlot::Dinner, Err("Salad with feta"), Some(sam));
    s.plan(day(2), MealSlot::Dinner, Err("Homemade pizza"), None);

    s.expense(
        "Weekly shop",
        6420,
        day(-6),
        alex,
        vec![alex, sam],
        Some("Groceries"),
        ExpenseKind::Expense,
    );
    s.expense(
        "Electricity",
        9000,
        day(-4),
        sam,
        vec![alex, sam],
        Some("Utilities"),
        ExpenseKind::Expense,
    );
    s.expense(
        "Cinema tickets",
        2400,
        day(-2),
        alex,
        vec![sam],
        Some("Fun"),
        ExpenseKind::Expense,
    );
    s.expense(
        "Paying back",
        2000,
        day(-1),
        sam,
        vec![alex],
        None,
        ExpenseKind::Settlement,
    );

    s.0
}
