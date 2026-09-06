package app.rommdroid.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
                onEditConnection = { navController.navigate(Route.Connection.path) },
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

        composable(Route.Connection.path) {
            ConnectionScreen(
                viewModel = hiltViewModel(),
                onSaved   = { navController.popBackStack() },
                onBack    = { navController.popBackStack() },
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
