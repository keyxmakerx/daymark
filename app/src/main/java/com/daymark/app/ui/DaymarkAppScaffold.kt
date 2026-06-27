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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavBackStackEntry
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.daymark.app.ui.activities.ActivityLibraryScreen
import com.daymark.app.ui.calendar.CalendarScreen
import com.daymark.app.ui.calendar.YearPixelsScreen
import com.daymark.app.ui.goals.GoalEditorScreen
import com.daymark.app.ui.goals.GoalsScreen
import com.daymark.app.ui.entry.EntryEditorScreen
import com.daymark.app.ui.components.RaisedCenterNavBar
import com.daymark.app.ui.home.HomeScreen
import com.daymark.app.ui.insights.InsightsScreen
import com.daymark.app.ui.journal.JournalEditorScreen
import com.daymark.app.ui.journal.JournalScreen
import com.daymark.app.ui.more.MoreHubScreen
import com.daymark.app.ui.navigation.Routes
import com.daymark.app.ui.navigation.TopLevelDestination
import com.daymark.app.ui.sleep.BreathingCaptureScreen
import com.daymark.app.ui.sleep.ScreenerScreen
import com.daymark.app.ui.sleep.SleepLogScreen
import com.daymark.app.ui.sleep.SleepProfileScreen
import com.daymark.app.ui.sleep.SleepScreen
import com.daymark.app.ui.sleep.TreatmentDetailScreen
import com.daymark.app.ui.sleep.TreatmentsScreen
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
    val isTopLevel = currentRoute in topLevelRoutes || currentRoute == null
    // Settings is a drill-down (reached from the More hub) that still reuses the app's top bar.
    val drillWithChrome = currentRoute == Routes.SETTINGS
    val showTopBar = isTopLevel || drillWithChrome
    val showBottomBar = isTopLevel

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showTopBar) {
                val onHome = currentRoute == Routes.HOME || currentRoute == null
                val title = when {
                    onHome -> "Daymark"
                    currentRoute == Routes.SETTINGS -> "Settings"
                    else -> TopLevelDestination.entries.firstOrNull { it.route == currentRoute }?.label ?: "Daymark"
                }
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    },
                    navigationIcon = {
                        if (drillWithChrome) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                val destination = backStackEntry?.destination
                RaisedCenterNavBar(
                    currentRoute = destination?.hierarchy
                        ?.firstOrNull { entry -> TopLevelDestination.entries.any { it.route == entry.route } }
                        ?.route ?: currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
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
                Routes.GOALS -> ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Routes.goal()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    text = { Text("New goal") },
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
            slideInVertically(offsetSpring) { it / 3 } + fadeIn(tween(120))
        }
        val sheetPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            slideOutVertically(offsetSpring) { it / 3 } + fadeOut(tween(160))
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
            composable(Routes.INSIGHTS) {
                InsightsScreen(modifier = Modifier.padding(padding))
            }
            composable(Routes.YEAR_PIXELS, enterTransition = zEnter, popExitTransition = zPopExit) {
                YearPixelsScreen(onBack = { navController.popBackStack() })
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
            composable(Routes.MORE) {
                MoreHubScreen(
                    onGoals = { navController.navigate(Routes.GOALS) },
                    onActivities = { navController.navigate(Routes.ACTIVITIES) },
                    onYearPixels = { navController.navigate(Routes.YEAR_PIXELS) },
                    onSleep = { navController.navigate(Routes.SLEEP) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.SLEEP, enterTransition = zEnter, popExitTransition = zPopExit) {
                SleepScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScreener = { key -> navController.navigate(Routes.screener(key)) },
                    onLogNight = { navController.navigate(Routes.SLEEP_LOG) },
                    onOpenSetup = { navController.navigate(Routes.SLEEP_SETUP) },
                    onOpenTreatments = { navController.navigate(Routes.TREATMENTS) },
                    onOpenBreathing = { navController.navigate(Routes.BREATHING) },
                )
            }
            composable(Routes.BREATHING, enterTransition = zEnter, popExitTransition = zPopExit) {
                BreathingCaptureScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SLEEP_LOG, enterTransition = sheetEnter, popExitTransition = sheetPopExit) {
                SleepLogScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.SLEEP_SETUP, enterTransition = zEnter, popExitTransition = zPopExit) {
                SleepProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TREATMENTS, enterTransition = zEnter, popExitTransition = zPopExit) {
                TreatmentsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTreatment = { id -> navController.navigate(Routes.treatment(id)) },
                )
            }
            composable(
                Routes.TREATMENT_PATTERN,
                arguments = listOf(navArgument("treatmentId") { type = NavType.StringType }),
                enterTransition = zEnter,
                popExitTransition = zPopExit,
            ) {
                TreatmentDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SCREENER_PATTERN,
                arguments = listOf(navArgument("screenerKey") { type = NavType.StringType }),
                enterTransition = sheetEnter,
                popExitTransition = sheetPopExit,
            ) { entry ->
                ScreenerScreen(
                    screenerKey = entry.arguments?.getString("screenerKey") ?: "",
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS, enterTransition = zEnter, popExitTransition = zPopExit) {
                SettingsScreen(
                    onManageActivities = { navController.navigate(Routes.ACTIVITIES) },
                    onManageGoals = { navController.navigate(Routes.GOALS) },
                    onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.GOALS) {
                GoalsScreen(
                    onGoalClick = { id -> navController.navigate(Routes.goal(id)) },
                    modifier = Modifier.padding(padding),
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
                ActivitiesScreen(
                    onBack = { navController.popBackStack() },
                    onBrowseLibrary = { navController.navigate(Routes.ACTIVITY_LIBRARY) },
                )
            }
            composable(Routes.ACTIVITY_LIBRARY, enterTransition = zEnter, popExitTransition = zPopExit) {
                ActivityLibraryScreen(
                    onBack = { navController.popBackStack() },
                    onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
            }
        }
    }
}
