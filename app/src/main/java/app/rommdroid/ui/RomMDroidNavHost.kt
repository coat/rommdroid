package app.rommdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadHandler
import app.rommdroid.ui.navigation.Route
import app.rommdroid.ui.screens.*

@Composable
fun RomMDroidNavHost() {
    val navController = rememberNavController()

    // StartupViewModel reads EncryptedSharedPreferences synchronously.
    // Holding the result here (above the NavHost) means a configuration
    // change won't flip the start destination after it has been decided.
    val startupViewModel: StartupViewModel = hiltViewModel()
    val startDestination = if (startupViewModel.isConfigured) {
        Route.PlatformList.path
    } else {
        Route.Setup.path
    }

    // Select and Start work from anywhere, the way the two little buttons do on
    // a console: one opens the settings, the other the queue.  They sit under
    // every screen's own bindings, so a screen is free to take them back.
    //
    // Not during setup — neither page has anything to say before there is a
    // server to talk to, and the queue would be a way out of an unfinished
    // sign-in that leads nowhere.
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

        // ── Setup / onboarding ────────────────────────────────────────────────
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

        // ── Platform list ─────────────────────────────────────────────────────
        composable(Route.PlatformList.path) {
            PlatformListScreen(
                viewModel        = hiltViewModel(),
                onPlatformClick  = { platformId ->
                    navController.navigate(Route.RomList.go(platformId))
                },
                onSearchClick    = { navController.navigate(Route.Search.path) },
                onDownloadsClick = { navController.navigate(Route.Downloads.path) },
                onSettingsClick  = { navController.navigate(Route.Settings.path) },
            )
        }

        // ── ROM list ──────────────────────────────────────────────────────────
        composable(
            route     = Route.RomList.TEMPLATE,
            arguments = listOf(navArgument(Route.RomList.ARG) { type = NavType.IntType }),
        ) { backStack ->
            val platformId = backStack.arguments?.getInt(Route.RomList.ARG) ?: return@composable
            RomListScreen(
                viewModel        = hiltViewModel(),
                platformId       = platformId,
                onRomClick       = { romId -> navController.navigate(Route.RomDetail.go(romId)) },
                onDownloadsClick = { navController.navigate(Route.Downloads.path) },
                onFolderSettings = { navController.navigate(Route.FolderMapping.path) },
                onBack           = { navController.popBackStack() },
            )
        }

        // ── ROM detail ────────────────────────────────────────────────────────
        composable(
            route     = Route.RomDetail.TEMPLATE,
            arguments = listOf(navArgument(Route.RomDetail.ARG) { type = NavType.IntType }),
        ) { backStack ->
            val romId = backStack.arguments?.getInt(Route.RomDetail.ARG) ?: return@composable
            RomDetailScreen(
                viewModel        = hiltViewModel(),
                romId            = romId,
                onFolderSettings = { navController.navigate(Route.FolderMapping.path) },
                onBack           = { navController.popBackStack() },
            )
        }

        // ── Search ────────────────────────────────────────────────────────────
        composable(Route.Search.path) {
            SearchScreen(
                viewModel        = hiltViewModel(),
                onRomClick       = { romId -> navController.navigate(Route.RomDetail.go(romId)) },
                onFolderSettings = { navController.navigate(Route.FolderMapping.path) },
                onBack           = { navController.popBackStack() },
            )
        }

        // ── Downloads ─────────────────────────────────────────────────────────
        composable(Route.Downloads.path) {
            DownloadsScreen(
                viewModel  = hiltViewModel(),
                onRomClick = { romId -> navController.navigate(Route.RomDetail.go(romId)) },
                onBack     = { navController.popBackStack() },
            )
        }

        // ── Settings ──────────────────────────────────────────────────────────
        composable(Route.Settings.path) {
            SettingsScreen(
                viewModel        = hiltViewModel(),
                onFolderMapping  = { navController.navigate(Route.FolderMapping.path) },
                onResetSetup     = {
                    // Wipes back stack and returns to setup so the user can
                    // point the app at a different server or re-authenticate.
                    navController.navigate(Route.Setup.path) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack           = { navController.popBackStack() },
            )
        }

        composable(Route.FolderMapping.path) {
            FolderMappingScreen(
                viewModel = hiltViewModel(),
                onBack    = { navController.popBackStack() },
            )
        }
    }
}
