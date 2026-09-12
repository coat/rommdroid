package app.rommdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.navigation.Route
import app.rommdroid.ui.collections.CollectionListScreen
import app.rommdroid.ui.downloads.DownloadsScreen
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
    val startDestination = if (startupViewModel.isConfigured) {
        Route.PlatformList.path
    } else {
        Route.Setup.path
    }

    // Select and Start work from anywhere, under every screen's own bindings so
    // a screen can take them back. Not during setup, where neither page has
    // anything to say and the queue leads out of an unfinished sign-in.
    val route by navController.currentBackStackEntryAsState()
    fun openOnce(path: String): Boolean {
        val current = route?.destination?.route
        if (current == Route.Setup.path || current == path) return true
        navController.navigate(path) { launchSingleTop = true }
        return true
    }
    GamepadHandler { action ->
        when (action) {
            GamepadAction.Settings  -> openOnce(Route.Settings.path)
            GamepadAction.Downloads -> openOnce(Route.Downloads.path)
            else                    -> false
        }
    }

    NavHost(
        navController    = navController,
        startDestination = startDestination,
    ) {

        composable(Route.Setup.path) {
            SetupScreen(
                viewModel  = hiltViewModel(),
                onComplete = {
                    navController.navigate(Route.PlatformList.path) {
                        popUpTo(Route.Setup.path) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.PlatformList.path) {
            PlatformListScreen(
                viewModel        = hiltViewModel(),
                onPlatformClick  = { platformId ->
                    navController.navigate(Route.RomList.go(platformId))
                },
                onCollectionsClick = { navController.navigate(Route.CollectionList.path) },
                onSearchClick    = { navController.navigate(Route.Search.path) },
                onDownloadsClick = navController::openDownloads,
                onSettingsClick  = { navController.navigate(Route.Settings.path) },
            )
        }

        composable(
            route     = Route.RomList.TEMPLATE,
            arguments = listOf(navArgument(Route.RomList.ARG) { type = NavType.IntType }),
        ) {
            RomListScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = navController::openRom,
                onDownloadsClick = navController::openDownloads,
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable(Route.CollectionList.path) {
            CollectionListScreen(
                viewModel         = hiltViewModel(),
                onCollectionClick = { id -> navController.navigate(Route.CollectionRoms.go(id)) },
                onBack            = navController::popBackStack,
            )
        }

        // Same screen as a platform's ROMs; only the argument differs, and
        // RomListViewModel reads whichever it was given.
        composable(
            route     = Route.CollectionRoms.TEMPLATE,
            arguments = listOf(navArgument(Route.CollectionRoms.ARG) { type = NavType.IntType }),
        ) {
            RomListScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = navController::openRom,
                onDownloadsClick = navController::openDownloads,
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable(
            route     = Route.RomDetail.TEMPLATE,
            arguments = listOf(navArgument(Route.RomDetail.ARG) { type = NavType.IntType }),
        ) {
            RomDetailScreen(
                viewModel        = hiltViewModel(),
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable(Route.Search.path) {
            SearchScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = navController::openRom,
                onFolderSettings = navController::openFolderMapping,
                onBack           = navController::popBackStack,
            )
        }

        composable(Route.Downloads.path) {
            DownloadsScreen(
                viewModel  = hiltViewModel(),
                onRomClick = navController::openRom,
                onBack     = navController::popBackStack,
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                viewModel        = hiltViewModel(),
                onFolderMapping  = navController::openFolderMapping,
                onResetSetup     = {
                    // Wipe the back stack: the app is being re-pointed.
                    navController.navigate(Route.Setup.path) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack           = navController::popBackStack,
            )
        }

        composable(Route.FolderMapping.path) {
            FolderMappingScreen(
                viewModel = hiltViewModel(),
                onBack    = navController::popBackStack,
            )
        }
    }
}

// The destinations more than one screen leads to.

private fun NavController.openRom(romId: Int) = navigate(Route.RomDetail.go(romId))
private fun NavController.openDownloads() = navigate(Route.Downloads.path)
private fun NavController.openFolderMapping() = navigate(Route.FolderMapping.path)
