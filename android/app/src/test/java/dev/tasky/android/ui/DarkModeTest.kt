package dev.tasky.android.ui

import android.graphics.Bitmap
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.BuildConfig
import dev.tasky.android.data.AccountRepository
import dev.tasky.android.data.SessionStore
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.data.db.MemberRow
import dev.tasky.android.ui.expenses.ExpensesScreen
import dev.tasky.android.ui.expenses.ExpensesViewModel
import dev.tasky.android.ui.list.ShoppingListScreen
import dev.tasky.android.ui.list.ShoppingListViewModel
import dev.tasky.android.ui.meals.MealEditScreen
import dev.tasky.android.ui.meals.MealEditViewModel
import dev.tasky.android.ui.onboarding.OnboardingScreen
import dev.tasky.android.ui.onboarding.OnboardingViewModel
import dev.tasky.android.ui.plan.PlanScreen
import dev.tasky.android.ui.plan.PlanViewModel
import dev.tasky.android.ui.theme.TaskyTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Dark theme: content must get light text by default, and every screen is rendered to
 * `build/screenshots/dark/` so it can be eyeballed (set -Dtasky.screenshots=<dir> to change).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class DarkModeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: TaskyDatabase
    private lateinit var repo: TaskyRepository
    private lateinit var checklistId: String
    private lateinit var noteId: String
    private val outDir = File(System.getProperty("tasky.screenshots") ?: "build/screenshots/dark")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskyRepository(LocalStore(db) { "me" }, Hlc("ui")) {}
        runBlocking {
            val list = repo.createList("Groceries")
            repo.addToList(list, "Milk", 1.0, "l")
            checklistId = repo.createList("Weekend", dev.tasky.api.ListKind.Checklist)
            repo.addEntry(checklistId, "Call plumber")
            repo.addEntry(checklistId, "Water plants")
            repo.addEntry(checklistId, "Return library books")
            val done = repo.queries.listEntriesNow(checklistId).last()
            repo.setEntryChecked(done.id, true)
            noteId = repo.createList("Ideas", dev.tasky.api.ListKind.Note)
            repo.setNoteBody(noteId, "Paint the hallway\nFix the bike\nBirthday gift for Ben")
            val meal = repo.createMeal("Curry", 4, "Spicy", null)
            repo.addIngredient(meal, "Rice", 400.0, "g", null)
            seedExpenses(db, repo)
            repo.createPlanEntry(
                TaskyRepository.PlanEntryInput(java.time.LocalDate.now().toString(), dev.tasky.api.MealSlot.Dinner, null, meal, null, servings = 2, note = null),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun shoot(name: String, waitText: String? = null) {
        waitText?.let { t ->
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.waitForIdle()
        outDir.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun defaultContentColorIsLightInDarkMode() {
        var luminance = -1f
        compose.setContent { TaskyTheme { luminance = LocalContentColor.current.luminance() } }
        compose.waitForIdle()
        assertTrue("content color luminance $luminance should be light", luminance > 0.5f)
    }

    @Test
    fun onboarding() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessions = SessionStore(context, PlainBox)
        val account = AccountRepository(okhttp3.OkHttpClient(), sessions, db, sessionApi = { null }, onSignedIn = {})
        val vm = OnboardingViewModel(account, null, BuildConfig.DEFAULT_SERVER_URL)
        compose.setContent { TaskyTheme { OnboardingScreen(vm) } }
        shoot("onboarding", BuildConfig.DEFAULT_SERVER_URL)
    }

    @Test
    fun shoppingList() {
        val vm = ShoppingListViewModel(repo)
        compose.setContent { TaskyTheme { ShoppingListScreen(vm) } }
        shoot("list", "Milk")
    }

    @Test
    fun mealEdit() {
        val vm = MealEditViewModel(repo, null)
        compose.setContent { TaskyTheme { MealEditScreen(vm) {} } }
        shoot("meal-edit")
    }

    @Test
    fun plan() {
        val vm = PlanViewModel(repo, SessionStore(ApplicationProvider.getApplicationContext(), PlainBox))
        compose.setContent { TaskyTheme { PlanScreen(vm) } }
        shoot("plan", "Curry")
    }

    @Test
    fun expenses() {
        val vm = ExpensesViewModel(repo, SessionStore(ApplicationProvider.getApplicationContext(), PlainBox))
        compose.setContent { TaskyTheme { ExpensesScreen(vm) } }
        shoot("expenses", "Weekly shop")
    }

    @Test
    fun checklist() {
        val vm = ShoppingListViewModel(repo)
        vm.select(checklistId)
        compose.setContent { TaskyTheme { ShoppingListScreen(vm) } }
        shoot("checklist", "Return library books")
    }

    @Test
    fun note() {
        val vm = ShoppingListViewModel(repo)
        vm.select(noteId)
        compose.setContent { TaskyTheme { ShoppingListScreen(vm) } }
        shoot("note", "Fix the bike")
    }
}

/** Two members, a few shared expenses and a repayment. */
internal suspend fun seedExpenses(db: TaskyDatabase, repo: TaskyRepository) {
    db.queries().upsertMembers(
        listOf(MemberRow("anna", "anna", "Anna", "owner", 1), MemberRow("ben", "ben", "Ben", "member", 2)),
    )
    val month = java.time.LocalDate.now().withDayOfMonth(1)
    fun input(what: String, minor: Long, payer: String, split: List<String>, category: String?, day: Long) =
        TaskyRepository.ExpenseInput(what, minor, "EUR", month.plusDays(day).toString(), payer, split, category)
    repo.createExpense(input("Rent", 120_000, "anna", listOf("anna", "ben"), "Home", 0))
    repo.createExpense(input("Weekly shop", 4_250, "ben", listOf("anna", "ben"), "Groceries", 1))
    repo.createExpense(input("Concert tickets", 5_900, "anna", listOf("ben"), null, 2))
    repo.recordSettlement("ben", "anna", 30_000, "EUR", month.plusDays(3).toString())
}
