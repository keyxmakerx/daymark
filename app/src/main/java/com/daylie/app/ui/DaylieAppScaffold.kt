package com.daylie.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.daylie.app.R
import com.daylie.app.ui.activities.ActivitiesScreen
import com.daylie.app.ui.calendar.CalendarScreen
import com.daylie.app.ui.calendar.YearPixelsScreen
import com.daylie.app.ui.entry.EntryEditorScreen
import com.daylie.app.ui.home.HomeScreen
import com.daylie.app.ui.journal.JournalEditorScreen
import com.daylie.app.ui.journal.JournalScreen
import com.daylie.app.ui.navigation.Routes
import com.daylie.app.ui.navigation.TopLevelDestination
import com.daylie.app.ui.settings.SettingsScreen
import com.daylie.app.ui.stats.StatsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaylieAppScaffold() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                    "Daylie"
                } else {
                    TopLevelDestination.entries.firstOrNull { it.route == currentRoute }?.label ?: "Daylie"
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
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier,
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
            composable(Routes.YEAR_PIXELS) {
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
            composable(Routes.JOURNAL_ENTRY_PATTERN) {
                JournalEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onManageActivities = { navController.navigate(Routes.ACTIVITIES) },
                    onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.ENTRY_PATTERN) {
                EntryEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.ACTIVITIES) {
                ActivitiesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
