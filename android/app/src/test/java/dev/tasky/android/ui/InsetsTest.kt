package dev.tasky.android.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tasky.android.data.SessionStore
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.TaskyDatabase
import dev.tasky.android.sync.Hlc
import dev.tasky.android.sync.LocalStore
import dev.tasky.android.ui.list.ShoppingListScreen
import dev.tasky.android.ui.list.ShoppingListViewModel
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
 * Edge-to-edge: with a (simulated) status bar, tab screens inside [TabsScaffold] must keep their
 * top bar below it. Robolectric has no real system bars, so insets are dispatched by hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class InsetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: TaskyDatabase
    private lateinit var repo: TaskyRepository
    private val statusBarPx = 120
    private val navBarPx = 60

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskyDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskyRepository(LocalStore(db) { "me" }, Hlc("ui")) {}
        runBlocking { repo.addToList(repo.createList("Groceries"), "Milk", 1.0, "l") }
    }

    @After
    fun tearDown() = db.close()

    private fun showInTabs(screen: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            TaskyTheme {
                TabsScaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Tab") })
                    }
                }) { modifier -> androidx.compose.foundation.layout.Box(modifier) { screen() } }
            }
        }
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun assertBelowStatusBar(text: String, shot: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val top = compose.onNodeWithText(text, substring = true).getBoundsInRoot().top
        val statusBarDp = with(compose.density) { statusBarPx.toDp() }
        File("build/screenshots/insets").mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots/insets/$shot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("'$text' top $top must be below the status bar ($statusBarDp)", top >= statusBarDp + 4.dp)
    }

    @Test
    fun planTopBarClearsStatusBar() {
        val vm = PlanViewModel(repo, SessionStore(ApplicationProvider.getApplicationContext(), PlainBox))
        showInTabs { PlanScreen(vm) }
        assertBelowStatusBar("Week of", "plan")
    }

    @Test
    fun listTopBarClearsStatusBar() {
        val vm = ShoppingListViewModel(repo)
        showInTabs { ShoppingListScreen(vm) }
        assertBelowStatusBar("Groceries", "list")
    }
}
