package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.core.firebase.authStateFlow
import com.example.data.ai.GeminiSearchAssistant
import com.example.data.repository.FirestoreTrackerRepository
import com.example.data.repository.LiveSearchOrchestrator
import com.example.data.repository.UserPreferencesRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.InsightsScreen
import com.example.ui.screens.SavedSearchesScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.WatchlistScreen
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.TrackerViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

const val ROUTE_SEARCH = "search"
const val ROUTE_WATCHLIST = "watchlist"
const val ROUTE_ALERTS = "alerts"
const val ROUTE_HISTORY = "history"

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val navTabs = listOf(
    NavTab(ROUTE_SEARCH, "Discover", Icons.Filled.Search, Icons.Outlined.Search),
    NavTab(ROUTE_WATCHLIST, "Watchlist", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    NavTab(ROUTE_ALERTS, "Alerts", Icons.Filled.NotificationsActive, Icons.Outlined.Notifications),
    NavTab(ROUTE_HISTORY, "History", Icons.AutoMirrored.Filled.ShowChart, Icons.AutoMirrored.Outlined.ShowChart)
)

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedShell(currentUserId = user.uid)
        }
    }
}

@Composable
private fun AuthenticatedShell(currentUserId: String) {
    val searchViewModel: SearchViewModel = viewModel(
        key = "search_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                val gemini = GeminiSearchAssistant()
                val orchestrator = LiveSearchOrchestrator(gemini)
                val prefs = UserPreferencesRepository(app)
                val trackerRepo = FirestoreTrackerRepository(db)
                SearchViewModel(orchestrator, gemini, prefs, trackerRepo, currentUserId)
            }
        }
    )

    val trackerViewModel: TrackerViewModel = viewModel(
        key = "tracker_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                val orchestrator = LiveSearchOrchestrator(GeminiSearchAssistant())
                val trackerRepo = FirestoreTrackerRepository(db)
                TrackerViewModel(trackerRepo, orchestrator, currentUserId)
            }
        }
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val trackerMsg by trackerViewModel.actionMessage.collectAsStateWithLifecycle()

    LaunchedEffect(searchUiState.statusBanner) {
        searchUiState.statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            searchViewModel.dismissBanner()
        }
    }

    LaunchedEffect(trackerMsg) {
        trackerMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            trackerViewModel.clearActionMessage()
        }
    }

    val navigateTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        navTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        navTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_rail_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_SEARCH
                    ) {
                        composable(ROUTE_SEARCH) {
                            SearchScreen(
                                searchViewModel = searchViewModel,
                                trackerViewModel = trackerViewModel,
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        composable(ROUTE_WATCHLIST) {
                            WatchlistScreen(
                                trackerViewModel = trackerViewModel,
                                countryCode = searchUiState.region.countryCode
                            )
                        }
                        composable(ROUTE_ALERTS) {
                            SavedSearchesScreen(
                                trackerViewModel = trackerViewModel,
                                onRunSavedSearch = { saved ->
                                    searchViewModel.setIncludeRentals(saved.includeRentals)
                                    searchViewModel.setIncludeAuctions(saved.includeAuctions)
                                    searchViewModel.runLiveSearch(
                                        explicitQuery = saved.query,
                                        overrideCountry = saved.countryCode,
                                        overridePostcode = saved.postcode.orEmpty(),
                                        overrideCurrency = saved.currency
                                    )
                                    navigateTab(ROUTE_SEARCH)
                                }
                            )
                        }
                        composable(ROUTE_HISTORY) {
                            InsightsScreen(trackerViewModel = trackerViewModel)
                        }
                    }
                }
            }
        }
    }
}
