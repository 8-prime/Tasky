package dev.tasky.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Visible state: what the UI reads. Always equals the last known server state (ServerEntityRow)
// with the pending outbox ops applied on top.

@Entity(tableName = "items", indices = [Index("normName")])
data class ItemRow(
    @PrimaryKey val id: String,
    val name: String,
    val normName: String,
    val category: String?,
    val defaultUnit: String?,
)

@Entity(tableName = "lists")
data class ListRow(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "contributions", indices = [Index("listId", "itemId"), Index("itemId"), Index("planEntryId")])
data class ContributionRow(
    @PrimaryKey val id: String,
    val listId: String,
    val itemId: String,
    val amount: Double?,
    val unit: String?,
    val planEntryId: String?,
    val note: String?,
    val purchased: Boolean,
    val purchasedBy: String?,
    val archived: Boolean,
)

@Entity(tableName = "meals")
data class MealRow(
    @PrimaryKey val id: String,
    val name: String,
    val defaultServings: Int,
    val notes: String?,
    val url: String?,
)

@Entity(tableName = "meal_ingredients", indices = [Index("mealId"), Index("itemId")])
data class MealIngredientRow(
    @PrimaryKey val id: String,
    val mealId: String,
    val itemId: String,
    val amount: Double?,
    val unit: String?,
    val note: String?,
)

@Entity(tableName = "plan_entries", indices = [Index("date")])
data class PlanEntryRow(
    @PrimaryKey val id: String,
    /** `YYYY-MM-DD` */
    val date: String,
    /** `breakfast` / `lunch` / `dinner` */
    val slot: String,
    val ownerUserId: String?,
    val mealId: String?,
    val freeText: String?,
    val servings: Int?,
    val note: String?,
)

/** Last state received from the server, as `EntityData` JSON. Tombstones are not kept. */
@Entity(tableName = "server_entities")
data class ServerEntityRow(
    @PrimaryKey val id: String,
    val kind: String,
    val data: String,
)

/** Pending local op (JSON `Op`), oldest first. */
@Entity(tableName = "outbox", indices = [Index("entityId")])
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entityId: String,
    val op: String,
    /** Part of a sync request whose final page hasn't arrived yet. */
    val inFlight: Boolean = false,
)

/** Household members, cached from `GET /households/{id}`. */
@Entity(tableName = "members")
data class MemberRow(
    @PrimaryKey val userId: String,
    val username: String,
    val displayName: String,
    val role: String,
    val joinedAtMs: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val key: String,
    val value: String,
)
