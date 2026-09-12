package app.rommdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.rommdroid.ui.collections.CollectionListScreen
import app.rommdroid.ui.downloads.DownloadsScreen
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.navigation.Route
import app.rommdroid.ui.platforms.PlatformListScreen
import app.rommdroid.ui.romdetail.RomDetailScreen
import app.rommdroid.ui.romlist.RomListScreen
import app.rommdroid.ui.search.SearchScreen
import app.rommdroid.ui.settings.FolderMappingScreen
import app.rommdroid.ui.settings.SettingsScreen
import app.rommdroid.ui.setup.SetupScreen

@Composable
fun RomMDroidNavHost() {
    val navController = rememberNavController()

    // Held above the NavHost so a configuration change cannot flip the start
    // destination after it has been decided.
    val startupViewModel: StartupViewModel = hiltViewModel()
    val startDestination: Route =
        if (startupViewModel.isConfigured) Route.PlatformList else Route.Setup

    // Select and Start work from anywhere, under every screen's own bindings so
    // a screen can take them back. Not during setup, where neither page has
    // anything to say and the queue leads out of an unfinished sign-in.
    val entry by navController.currentBackStackEntryAsState()
    fun openOnce(route: Route): Boolean {
        val current = entry?.destination
        if (current?.hasRoute<Route.Setup>() == true) return true
        if (current?.hasRoute(route::class) == true) return true
        navController.navigate(route) { launchSingleTop = true }
        return true
    }
    GamepadHandler { action ->
        when (action) {
            GamepadAction.Settings  -> openOnce(Route.Settings)
            GamepadAction.Downloads -> openOnce(Route.Downloads)
            else                    -> false
        }
    }

    NavHost(
        navController    = navController,
        startDestination = startDestination,
    ) {

        composable<Route.Setup> {
            SetupScreen(
                viewModel  = hiltViewModel(),
                onComplete = {
                    navController.navigate(Route.PlatformList) {
                        popUpTo<Route.Setup> { inclusive = true }
                    }
                }
            )
        }

        composable<Route.PlatformList> {
            PlatformListScreen(
                viewModel          = hiltViewModel(),
                onPlatformClick    = { navController.navigate(Route.RomList.platform(it)) },
                onCollectionsClick = { navController.navigate(Route.CollectionList) },
                onSearchClick      = { navController.navigate(Route.Search) },
                onDownloadsClick   = navController::openDownloads,
                onSettingsClick    = { navController.navigate(Route.Settings) },
            )
        }

        composable<Route.RomList> {
            RomListScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = navController::openRom,
                onDownloadsClick = navController::openDownloads,
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable<Route.CollectionList> {
            CollectionListScreen(
                viewModel         = hiltViewModel(),
                onCollectionClick = { navController.navigate(Route.RomList.collection(it)) },
                onBack            = navController::popBackStack,
            )
        }

        composable<Route.RomDetail> {
            RomDetailScreen(
                viewModel        = hiltViewModel(),
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable<Route.Search> {
            SearchScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = navController::openRom,
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable<Route.Downloads> {
            DownloadsScreen(
                viewModel  = hiltViewModel(),
                onRomClick = navController::openRom,
                onBack     = navController::popBackStack,
            )
        }

        composable<Route.Settings> {
            SettingsScreen(
                viewModel        = hiltViewModel(),
                onFolderMapping  = navController::openFolderMapping,
                onResetSetup     = {
                    // Wipe the back stack: the app is being re-pointed.
                    navController.navigate(Route.Setup) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack           = navController::popBackStack,
            )
        }

        composable<Route.FolderMapping> {
            FolderMappingScreen(
                viewModel = hiltViewModel(),
                onBack    = navController::popBackStack,
            )
        }
    }
}

// The destinations more than one screen leads to.

private fun NavController.openRom(romId: Int) = navigate(Route.RomDetail(romId))
private fun NavController.openDownloads() = navigate(Route.Downloads)
private fun NavController.openFolderMapping() = navigate(Route.FolderMapping)
