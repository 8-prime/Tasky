package dev.tasky.android.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.tasky.android.AppContainer
import dev.tasky.android.R
import dev.tasky.android.ui.household.HouseholdScreen
import dev.tasky.android.ui.household.HouseholdViewModel
import dev.tasky.android.ui.list.ShoppingListScreen
import dev.tasky.android.ui.list.ShoppingListViewModel
import dev.tasky.android.ui.meals.MealEditScreen
import dev.tasky.android.ui.meals.MealEditViewModel
import dev.tasky.android.ui.meals.MealsScreen
import dev.tasky.android.ui.meals.MealsViewModel
import dev.tasky.android.ui.plan.PlanScreen
import dev.tasky.android.ui.plan.PlanViewModel
import kotlinx.serialization.Serializable

@Serializable object ListRoute
@Serializable object PlanRoute
@Serializable object MealsRoute
@Serializable object HouseholdRoute
@Serializable data class MealEditRoute(val mealId: String? = null)

private data class Tab(val route: Any, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(ListRoute, R.string.nav_list, Icons.AutoMirrored.Filled.List),
    Tab(PlanRoute, R.string.nav_plan, Icons.Filled.CalendarMonth),
    Tab(MealsRoute, R.string.nav_meals, Icons.Filled.RestaurantMenu),
    Tab(HouseholdRoute, R.string.nav_household, Icons.Filled.Home),
)

@Composable
fun MainScaffold(container: AppContainer) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onTab = tabs.any { tab -> destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true }

    // Ops the server refused (e.g. invalid input that slipped through): tell the user.
    LaunchedEffect(Unit) {
        container.syncEngine.rejections.collect { snackbar.showSnackbar(resources.getString(R.string.error_rejected, it.message)) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ListRoute,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
        ) {
            composable<ListRoute> {
                ShoppingListScreen(viewModel { ShoppingListViewModel(container.repository) })
            }
            composable<PlanRoute> {
                PlanScreen(viewModel { PlanViewModel(container.repository, container.sessions) })
            }
            composable<MealsRoute> {
                MealsScreen(viewModel { MealsViewModel(container.repository) }) { id -> nav.navigate(MealEditRoute(id)) }
            }
            composable<MealEditRoute> { entry ->
                val route = entry.toRoute<MealEditRoute>()
                MealEditScreen(viewModel { MealEditViewModel(container.repository, route.mealId) }) { nav.popBackStack() }
            }
            composable<HouseholdRoute> {
                HouseholdScreen(viewModel { HouseholdViewModel(container) })
            }
        }
    }
}
