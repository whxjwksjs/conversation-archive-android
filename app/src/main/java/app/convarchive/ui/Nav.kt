package app.convarchive.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.convarchive.ui.screens.BackupScreen
import app.convarchive.ui.screens.BrowserScreen
import app.convarchive.ui.screens.DetailScreen
import app.convarchive.ui.screens.ExportScreen
import app.convarchive.ui.screens.HomeScreen
import app.convarchive.ui.screens.ImportScreen
import app.convarchive.ui.screens.LibraryScreen
import app.convarchive.ui.screens.PlatformGuideScreen
import app.convarchive.ui.screens.RawJsonScreen
import app.convarchive.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val GUIDE = "guide/{platform}"
    const val LIBRARY = "library"
    const val DETAIL = "detail/{id}"
    const val RAW_JSON = "rawjson/{id}"
    const val IMPORT = "import"
    const val EXPORT = "export"
    const val BACKUP = "backup"
    const val SETTINGS = "settings"
    const val BROWSER = "browser/{platform}"

    fun guide(platform: String) = "guide/$platform"
    fun browser(platform: String) = "browser/$platform"
    fun detail(id: Long) = "detail/$id"
    fun rawJson(id: Long) = "rawjson/$id"
}

/**
 * Preselected conversation ids for the Export screen, set by LibraryScreen's
 * multi-select "Export" action. Consumed (and cleared) once by ExportScreen.
 */
object ExportPreselect {
    var ids: Set<Long> = emptySet()
}

@Composable
fun Nav(
    navController: NavHostController,
    pendingImport: Boolean,
    onImportConsumed: () -> Unit,
) {
    LaunchedEffect(pendingImport) {
        if (pendingImport) {
            // Recreate the destination so ImportScreen re-consumes IncomingUris.
            navController.navigate(Routes.IMPORT) {
                popUpTo(Routes.IMPORT) { inclusive = true }
                launchSingleTop = true
            }
            onImportConsumed()
        }
    }
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(
            Routes.GUIDE,
            arguments = listOf(navArgument("platform") { type = NavType.StringType }),
        ) { backStackEntry ->
            PlatformGuideScreen(
                navController = navController,
                platform = backStackEntry.arguments?.getString("platform") ?: "gemini",
            )
        }
        composable(Routes.LIBRARY) { LibraryScreen(navController) }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            DetailScreen(
                navController = navController,
                id = backStackEntry.arguments?.getLong("id") ?: -1L,
            )
        }
        composable(
            Routes.RAW_JSON,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            RawJsonScreen(
                navController = navController,
                id = backStackEntry.arguments?.getLong("id") ?: -1L,
            )
        }
        composable(Routes.IMPORT) { ImportScreen(navController) }
        composable(Routes.EXPORT) { ExportScreen(navController) }
        composable(Routes.BACKUP) { BackupScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(
            Routes.BROWSER,
            arguments = listOf(navArgument("platform") { type = NavType.StringType }),
        ) { backStackEntry ->
            BrowserScreen(
                navController = navController,
                platform = backStackEntry.arguments?.getString("platform") ?: "gemini",
            )
        }
    }
}
