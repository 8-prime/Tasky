package dev.tasky.android.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.domain.MealPlanning
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.sync.Uuids
import dev.tasky.android.ui.list.ShoppingListScreen
import dev.tasky.android.ui.list.ShoppingListViewModel
import dev.tasky.android.ui.meals.MealEditScreen
import dev.tasky.android.ui.meals.MealEditViewModel
import dev.tasky.android.ui.plan.PlanScreen
import dev.tasky.android.ui.plan.PlanViewModel
import dev.tasky.android.ui.theme.TaskyTheme
import dev.tasky.api.MealSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Renders the main screens on Robolectric against an in-memory store and drives them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: TaskyDatabase
    private lateinit var repo: TaskyRepository
    private var edits = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = LocalStore(db) { "me" }
        repo = TaskyRepository(store, Hlc("ui")) { edits++ }
    }

    @After
    fun tearDown() = db.close()

    private fun waitFor(text: String) = try {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        val roots = compose.onAllNodes(isRoot())
        (0 until roots.fetchSemanticsNodes().size).forEach { println(roots[it].printToString()) }
        throw e
    }

    /** Waits for a non-UI condition. Fetching the tree also lets queued Main-dispatcher work run. */
    private fun waitForCondition(timeoutMs: Long = 15_000, check: () -> Boolean) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        check()
    }

    private fun waitForDb(check: suspend () -> Boolean) = waitForCondition { runBlocking { check() } }

    @Test
    fun shoppingList() {
        runBlocking {
            val list = repo.createList("Groceries")
            repo.createList("Hardware")
            repo.addToList(list, "Tomatoes", 500.0, "g", category = "Produce")
            repo.addToList(list, "tomatoes", 2.0, "pcs")
            repo.addToList(list, "Milk", 1.0, "l", category = "Dairy")
        }
        val vm = ShoppingListViewModel(repo)
        compose.setContent { TaskyTheme { ShoppingListScreen(vm) } }
        waitFor("500 g + 2 pcs")
        compose.onNodeWithText("Groceries").assertExists()
        compose.onNodeWithText("Produce").assertExists()
        compose.onNodeWithText("Dairy").assertExists()

        // Tap a line to check it off.
        compose.onNodeWithText("Milk").performClick()
        waitFor("Checked (1)")
        val q = db.queries()
        waitForDb { q.activeContributionsNow(q.lists().first().first { it.name == "Groceries" }.id).any { it.purchased } }

        // Clear checked from the section header.
        compose.onNode(hasText("Clear checked") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Milk")).fetchSemanticsNodes().isEmpty() }

        // Add an item through the sheet.
        compose.onNodeWithText("Add item", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Item")).performTextInput("Eggs")
        compose.onNode(hasSetTextAction() and hasText("Amount")).performTextInput("6")
        compose.onNode(hasSetTextAction() and hasText("Unit")).performTextInput("pcs")
        compose.onNode(hasText("Add") and hasClickAction()).performClick()
        waitFor("Added Eggs")
        waitForDb { q.itemByNormName("eggs") != null }
        assertTrue(edits >= 4)
    }

    /**
     * Text fields inside dialogs never let Compose idle under Robolectric (the cursor blink in a
     * dialog window), so the ingredient dialog itself isn't driven here; ingredients are added
     * through the repository and checked on screen.
     */
    @Test
    fun mealEditorCreatesAMealAndShowsIngredients() {
        val vm = MealEditViewModel(repo, null)
        compose.setContent { TaskyTheme { MealEditScreen(vm) {} } }
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Pancakes")
        compose.onNode(hasText("Create meal") and hasClickAction()).performClick()
        waitFor("Add ingredient")
        val q = db.queries()
        waitForDb { q.meals().first().singleOrNull()?.name == "Pancakes" }
        val meal = runBlocking { q.meals().first().single() }
        assertEquals(2, meal.defaultServings)
        runBlocking { repo.addIngredient(meal.id, "Flour", 250.0, "g", "sifted") }
        waitFor("250 g")
        compose.onNodeWithText("Flour").assertExists()
        compose.onNodeWithText("sifted").assertExists()

        // Editing the main form saves only the changed field.
        compose.onNode(hasSetTextAction() and hasText("Servings")).performTextReplacement("20")
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()
        waitForDb { q.meals().first().single().defaultServings == 20 }
    }

    @Test
    fun plannerAddsTheWeekToTheList() {
        val monday = MealPlanning.weekStart(LocalDate.now())
        val entryId = runBlocking {
            repo.createList("Groceries")
            val meal = repo.createMeal("Curry", 4, null, null)
            repo.addIngredient(meal, "Rice", 400.0, "g", null)
            repo.addIngredient(meal, "Coconut milk", 2.0, "can", null)
            repo.createPlanEntry(
                TaskyRepository.PlanEntryInput(LocalDate.now().toString(), MealSlot.Dinner, null, meal, null, servings = 2, note = null),
            )
        }
        val sessions = dev.tasky.android.data.SessionStore(ApplicationProvider.getApplicationContext(), PlainBox)
        val vm = PlanViewModel(repo, sessions)
        compose.setContent { TaskyTheme { PlanScreen(vm) } }
        waitFor("Curry")
        compose.onNode(hasContentDescription("Add week to list")).performClick()
        waitFor("Coconut milk")
        compose.onNodeWithText("200 g").assertExists() // 400 g for 4 servings, planned for 2
        // Untick the rice: we have it at home.
        compose.onNodeWithText("Rice").performClick()
        compose.onNode(hasText("Add 1 item") and hasClickAction()).performClick()
        val q = db.queries()
        waitForDb { q.contributionsOfPlanEntry(entryId).size == 1 }
        runBlocking {
            val c = q.contributionsOfPlanEntry(entryId).single()
            val ingredient = q.allIngredients().first().single { it.amount == 2.0 }
            assertEquals(Uuids.contributionId(entryId, ingredient.id), c.id)
            assertEquals(1.0, c.amount)
        }
    }

    /** Opt-in (needs TASKY_TEST_SERVER/TASKY_TEST_SETUP_TOKEN): onboarding screens against a real server. */
    @Test
    fun onboardingCreatesAHousehold() {
        val server = System.getProperty("tasky.server").orEmpty()
        val token = System.getProperty("tasky.setupToken").orEmpty()
        org.junit.Assume.assumeTrue(server.isNotEmpty() && token.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessions = dev.tasky.android.data.SessionStore(context, PlainBox)
        sessions.clear()
        val http = okhttp3.OkHttpClient()
        var signedIn = false
        val account = dev.tasky.android.data.AccountRepository(
            http, sessions, db,
            sessionApi = { sessions.current?.let { dev.tasky.android.api.ApiClient(http, it.serverUrl, it.token) } },
            onSignedIn = { signedIn = true },
        )
        val vm = dev.tasky.android.ui.onboarding.OnboardingViewModel(account, null, dev.tasky.android.BuildConfig.DEFAULT_SERVER_URL)
        compose.setContent { TaskyTheme { dev.tasky.android.ui.onboarding.OnboardingScreen(vm) } }

        // The build's default server is prefilled; switch to the test server.
        compose.onNodeWithText(dev.tasky.android.BuildConfig.DEFAULT_SERVER_URL).assertExists()
        compose.onNode(hasText("Change server") and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction() and hasText("Server address")).performTextReplacement(server + "/")
        compose.onNode(hasText("Continue") and hasClickAction()).performClick()
        waitFor("Create a household")
        compose.onNodeWithText("Connected to", substring = true).assertExists()
        compose.onNode(hasText("Create a household") and hasClickAction()).performClick()

        val user = "ui_" + java.util.UUID.randomUUID().toString().take(8)
        compose.onNode(hasSetTextAction() and hasText("Household creation token")).performTextInput(token)
        compose.onNode(hasSetTextAction() and hasText("Household name")).performTextInput("UI test home")
        compose.onNode(hasSetTextAction() and hasText("Your name")).performTextInput("Uma")
        compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput("x") // too short
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("short")
        compose.onNode(hasText("Create a household") and hasClickAction()).performScrollTo().performClick()
        waitFor("3–32 characters")
        compose.onNodeWithText("At least 8 characters.").assertExists()

        compose.onNode(hasSetTextAction() and hasText("Username")).performTextReplacement(user)
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement("long enough password")
        compose.onNode(hasText("Create a household") and hasClickAction()).performScrollTo().performClick()
        waitForCondition { sessions.current != null && signedIn }
        val s = sessions.current!!
        assertEquals(user, s.username)
        assertEquals("UI test home", s.householdName)
        assertEquals(server, s.serverUrl)
        // Household details were fetched into the local cache.
        assertEquals(listOf("Uma"), runBlocking { db.queries().members().first().map { it.displayName } })
        sessions.clear()
    }
}

/** No Android Keystore under Robolectric. */
object PlainBox : dev.tasky.android.data.SecretBox {
    override fun seal(plain: String) = plain
    override fun open(sealed: String) = sealed
}
