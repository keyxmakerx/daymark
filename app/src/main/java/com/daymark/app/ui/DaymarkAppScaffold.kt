package com.daymark.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavBackStackEntry
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.daymark.app.R
import com.daymark.app.ui.activities.ActivitiesScreen
import com.daymark.app.ui.calendar.CalendarScreen
import com.daymark.app.ui.calendar.YearPixelsScreen
import com.daymark.app.ui.goals.GoalEditorScreen
import com.daymark.app.ui.goals.GoalsScreen
import com.daymark.app.ui.entry.EntryEditorScreen
import com.daymark.app.ui.home.HomeScreen
import com.daymark.app.ui.journal.JournalEditorScreen
import com.daymark.app.ui.journal.JournalScreen
import com.daymark.app.ui.navigation.Routes
import com.daymark.app.ui.navigation.TopLevelDestination
import com.daymark.app.ui.settings.SettingsScreen
import com.daymark.app.ui.stats.StatsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaymarkAppScaffold(initialMood: Int = -1) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // From the home-screen widget: jump straight into a new entry with the tapped mood.
    androidx.compose.runtime.LaunchedEffect(initialMood) {
        if (initialMood in 1..5) {
            navController.navigate(Routes.entry(mood = initialMood))
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = TopLevelDestination.entries.map { it.route }
    val showChrome = currentRoute in topLevelRoutes || currentRoute == null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showChrome) {
                val onHome = currentRoute == Routes.HOME || currentRoute == null
                val title = if (onHome) {
                    "Daymark"
                } else {
                    TopLevelDestination.entries.firstOrNull { it.route == currentRoute }?.label ?: "Daymark"
                }
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        bottomBar = {
            if (showChrome) {
                NavigationBar {
                    val destination = backStackEntry?.destination
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(dest.icon), contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (currentRoute) {
                Routes.HOME -> ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Routes.entry()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    text = { Text("Entry") },
                    icon = { Icon(painterResource(R.drawable.ic_ui_plus), contentDescription = null) },
                )
                Routes.JOURNAL -> ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Routes.journalEntry()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    text = { Text("Write") },
                    icon = { Icon(painterResource(R.drawable.ic_ui_plus), contentDescription = null) },
                )
            }
        },
    ) { padding ->
        // Spring specs for spatial motion — alive & interruptible, unlike a fixed tween.
        val floatSpring = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
        val offsetSpring =
            spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

        // Drill into a detail/list = shared-axis Z (scale up from depth).
        val zEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            scaleIn(floatSpring, initialScale = 0.85f) + fadeIn(tween(150))
        }
        val zPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            scaleOut(floatSpring, targetScale = 0.85f) + fadeOut(tween(150))
        }
        // Create/edit an entry = a sheet rising from the bottom (a different metaphor).
        val sheetEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            slideInVertically(offsetSpring) { it / 6 } + fadeIn(tween(150))
        }
        val sheetPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            slideOutVertically(offsetSpring) { it / 6 } + fadeOut(tween(150))
        }

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier,
            // Default = top-level tabs, which are siblings: a non-directional fade-through.
            enterTransition = { fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f) },
            exitTransition = { fadeOut(tween(110)) },
            popEnterTransition = { fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f) },
            popExitTransition = { fadeOut(tween(110)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onEntryClick = { id -> navController.navigate(Routes.entry(id)) },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(
                    onYearView = { navController.navigate(Routes.YEAR_PIXELS) },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.YEAR_PIXELS, enterTransition = zEnter, popExitTransition = zPopExit) {
                YearPixelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STATS) {
                StatsScreen(modifier = Modifier.padding(padding))
            }
            composable(Routes.JOURNAL) {
                JournalScreen(
                    onEntryClick = { id -> navController.navigate(Routes.journalEntry(id)) },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(
                Routes.JOURNAL_ENTRY_PATTERN,
                enterTransition = sheetEnter,
                popExitTransition = sheetPopExit,
            ) {
                JournalEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onManageActivities = { navController.navigate(Routes.ACTIVITIES) },
                    onManageGoals = { navController.navigate(Routes.GOALS) },
                    onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.GOALS, enterTransition = zEnter, popExitTransition = zPopExit) {
                GoalsScreen(
                    onBack = { navController.popBackStack() },
                    onGoalClick = { id -> navController.navigate(Routes.goal(id)) },
                    onAddGoal = { navController.navigate(Routes.goal()) },
                )
            }
            composable(Routes.GOAL_PATTERN, enterTransition = sheetEnter, popExitTransition = sheetPopExit) {
                GoalEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(
                Routes.ENTRY_PATTERN,
                arguments = listOf(
                    navArgument("entryId") { type = NavType.StringType },
                    navArgument("mood") { type = NavType.StringType; defaultValue = "-1" },
                ),
                enterTransition = sheetEnter,
                popExitTransition = sheetPopExit,
            ) {
                EntryEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.ACTIVITIES, enterTransition = zEnter, popExitTransition = zPopExit) {
                ActivitiesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
