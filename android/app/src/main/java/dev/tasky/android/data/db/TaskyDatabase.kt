package dev.tasky.android.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ItemRow::class, ListRow::class, ContributionRow::class, MealRow::class, MealIngredientRow::class,
        PlanEntryRow::class, ServerEntityRow::class, OutboxRow::class, MemberRow::class, SyncStateRow::class,
        ListEntryRow::class, ExpenseRow::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 1 → 2: list kinds (lists.kind, lists.body) and checklist entries. Keeps the outbox.
        AutoMigration(from = 1, to = 2),
        // 2 → 3: expenses.
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class TaskyDatabase : RoomDatabase() {
    abstract fun entities(): EntityDao
    abstract fun outbox(): OutboxDao
    abstract fun queries(): QueryDao

    companion object {
        fun open(context: Context): TaskyDatabase =
            Room.databaseBuilder(context, TaskyDatabase::class.java, "tasky.db")
                // Everything here can be pulled from the server again, except the outbox.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
