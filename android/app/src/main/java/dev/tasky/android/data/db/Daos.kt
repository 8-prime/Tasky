package dev.tasky.android.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityDao {
    // --- visible rows by id ---
    @Query("SELECT * FROM items WHERE id = :id") suspend fun item(id: String): ItemRow?
    @Query("SELECT * FROM lists WHERE id = :id") suspend fun list(id: String): ListRow?
    @Query("SELECT * FROM contributions WHERE id = :id") suspend fun contribution(id: String): ContributionRow?
    @Query("SELECT * FROM meals WHERE id = :id") suspend fun meal(id: String): MealRow?
    @Query("SELECT * FROM meal_ingredients WHERE id = :id") suspend fun mealIngredient(id: String): MealIngredientRow?
    @Query("SELECT * FROM plan_entries WHERE id = :id") suspend fun planEntry(id: String): PlanEntryRow?
    @Query("SELECT * FROM list_entries WHERE id = :id") suspend fun listEntry(id: String): ListEntryRow?

    @Upsert suspend fun upsert(row: ItemRow)
    @Upsert suspend fun upsert(row: ListRow)
    @Upsert suspend fun upsert(row: ContributionRow)
    @Upsert suspend fun upsert(row: MealRow)
    @Upsert suspend fun upsert(row: MealIngredientRow)
    @Upsert suspend fun upsert(row: PlanEntryRow)
    @Upsert suspend fun upsert(row: ListEntryRow)

    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItem(id: String)
    @Query("DELETE FROM lists WHERE id = :id") suspend fun deleteList(id: String)
    @Query("DELETE FROM contributions WHERE id = :id") suspend fun deleteContribution(id: String)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Query("DELETE FROM meal_ingredients WHERE id = :id") suspend fun deleteMealIngredient(id: String)
    @Query("DELETE FROM plan_entries WHERE id = :id") suspend fun deletePlanEntry(id: String)
    @Query("DELETE FROM list_entries WHERE id = :id") suspend fun deleteListEntry(id: String)

    @Query("DELETE FROM items") suspend fun clearItems()
    @Query("DELETE FROM lists") suspend fun clearLists()
    @Query("DELETE FROM contributions") suspend fun clearContributions()
    @Query("DELETE FROM meals") suspend fun clearMeals()
    @Query("DELETE FROM meal_ingredients") suspend fun clearMealIngredients()
    @Query("DELETE FROM plan_entries") suspend fun clearPlanEntries()
    @Query("DELETE FROM list_entries") suspend fun clearListEntries()

    @Query("UPDATE contributions SET itemId = :target WHERE itemId = :alias")
    suspend fun remapContributionItems(alias: String, target: String)

    @Query("UPDATE meal_ingredients SET itemId = :target WHERE itemId = :alias")
    suspend fun remapIngredientItems(alias: String, target: String)

    // --- server state ---
    @Query("SELECT * FROM server_entities WHERE id = :id") suspend fun serverEntity(id: String): ServerEntityRow?
    @Upsert suspend fun upsertServer(row: ServerEntityRow)
    @Query("DELETE FROM server_entities WHERE id = :id") suspend fun deleteServer(id: String)
    @Query("DELETE FROM server_entities") suspend fun clearServer()

    // --- sync state ---
    @Query("SELECT value FROM sync_state WHERE `key` = :key") suspend fun state(key: String): String?
    @Query("SELECT value FROM sync_state WHERE `key` = :key") fun observeState(key: String): Flow<String?>
    @Upsert suspend fun setState(row: SyncStateRow)
}

@Dao
interface OutboxDao {
    @Query("INSERT INTO outbox (entityId, op, inFlight) VALUES (:entityId, :op, 0)")
    suspend fun append(entityId: String, op: String)

    @Query("SELECT * FROM outbox WHERE inFlight = 0 ORDER BY seq LIMIT :limit")
    suspend fun unsent(limit: Int): List<OutboxRow>

    @Query("SELECT COUNT(*) FROM outbox WHERE inFlight = 0") suspend fun unsentCount(): Int
    @Query("SELECT COUNT(*) FROM outbox") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
    @Query("SELECT * FROM outbox ORDER BY seq") suspend fun all(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE entityId = :entityId ORDER BY seq") suspend fun forEntity(entityId: String): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE inFlight = 1 ORDER BY seq") suspend fun inFlight(): List<OutboxRow>

    @Query("UPDATE outbox SET inFlight = 1 WHERE seq IN (:seqs)") suspend fun markInFlight(seqs: List<Long>)
    @Query("UPDATE outbox SET inFlight = 0") suspend fun resetInFlight()
    @Query("DELETE FROM outbox WHERE seq IN (:seqs)") suspend fun delete(seqs: List<Long>)
    @Query("DELETE FROM outbox WHERE inFlight = 1") suspend fun deleteInFlight()
    @Query("UPDATE outbox SET entityId = :entityId, op = :op WHERE seq = :seq")
    suspend fun rewrite(seq: Long, entityId: String, op: String)
}

/** Read-side queries for the UI. */
@Dao
interface QueryDao {
    @Query("SELECT * FROM lists ORDER BY name COLLATE NOCASE") fun lists(): Flow<List<ListRow>>
    @Query("SELECT * FROM list_entries WHERE listId = :listId ORDER BY id") fun listEntries(listId: String): Flow<List<ListEntryRow>>
    @Query("SELECT * FROM list_entries WHERE listId = :listId ORDER BY id") suspend fun listEntriesNow(listId: String): List<ListEntryRow>
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE") fun items(): Flow<List<ItemRow>>
    @Query("SELECT * FROM items WHERE normName = :normName LIMIT 1") suspend fun itemByNormName(normName: String): ItemRow?

    @Query("SELECT * FROM contributions WHERE listId = :listId AND archived = 0")
    fun activeContributions(listId: String): Flow<List<ContributionRow>>

    @Query("SELECT * FROM contributions WHERE listId = :listId AND archived = 0")
    suspend fun activeContributionsNow(listId: String): List<ContributionRow>

    @Query("SELECT * FROM contributions WHERE listId = :listId AND itemId = :itemId AND archived = 0")
    suspend fun lineContributions(listId: String, itemId: String): List<ContributionRow>

    @Query("SELECT * FROM contributions WHERE listId = :listId") suspend fun allContributionsOfList(listId: String): List<ContributionRow>
    @Query("SELECT * FROM contributions WHERE planEntryId = :planEntryId") suspend fun contributionsOfPlanEntry(planEntryId: String): List<ContributionRow>
    @Query("SELECT id FROM contributions WHERE id IN (:ids)") suspend fun existingContributionIds(ids: List<String>): List<String>
    @Query("SELECT * FROM contributions WHERE planEntryId IS NOT NULL") fun planContributions(): Flow<List<ContributionRow>>

    @Query("SELECT * FROM meals ORDER BY name COLLATE NOCASE") fun meals(): Flow<List<MealRow>>
    @Query("SELECT * FROM meals WHERE id = :id") fun meal(id: String): Flow<MealRow?>
    @Query("SELECT * FROM meal_ingredients WHERE mealId = :mealId") fun ingredients(mealId: String): Flow<List<MealIngredientRow>>
    @Query("SELECT * FROM meal_ingredients WHERE mealId = :mealId") suspend fun ingredientsNow(mealId: String): List<MealIngredientRow>
    @Query("SELECT * FROM meal_ingredients") fun allIngredients(): Flow<List<MealIngredientRow>>

    @Query("SELECT * FROM plan_entries WHERE date >= :from AND date <= :to ORDER BY date")
    fun planEntries(from: String, to: String): Flow<List<PlanEntryRow>>

    @Query("SELECT * FROM plan_entries WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun planEntriesNow(from: String, to: String): List<PlanEntryRow>

    @Query("SELECT * FROM meals") suspend fun mealsNow(): List<MealRow>
    @Query("SELECT * FROM meal_ingredients") suspend fun allIngredientsNow(): List<MealIngredientRow>
    @Query("SELECT * FROM items") suspend fun itemsNow(): List<ItemRow>

    @Query("SELECT * FROM plan_entries WHERE mealId = :mealId") suspend fun planEntriesForMeal(mealId: String): List<PlanEntryRow>

    @Query("SELECT * FROM members ORDER BY joinedAtMs") fun members(): Flow<List<MemberRow>>
    @Query("SELECT COUNT(*) FROM members") suspend fun memberCount(): Int
    @Upsert suspend fun upsertMembers(rows: List<MemberRow>)
    @Query("DELETE FROM members WHERE userId NOT IN (:keep)") suspend fun deleteMembersExcept(keep: List<String>)
}
