package app.convarchive.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.ui.Routes
import app.convarchive.ui.components.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository
    var savedCount by mutableStateOf(0)
        private set

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            savedCount = runCatching { repo.count() }.getOrDefault(0)
        }
    }
}

private data class PlatformCard(
    val slug: String,
    val name: String,
    val description: String,
)

private val platforms = listOf(
    PlatformCard(
        "gemini",
        "Gemini",
        "Archive chats from the Gemini web app via the companion userscript.",
    ),
    PlatformCard(
        "aistudio",
        "Google AI Studio",
        "Archive AI Studio prompts and responses via the companion userscript.",
    ),
    PlatformCard(
        "jules",
        "Google Jules",
        "Archive Jules coding tasks: prompts, status, and activity as shown on the page.",
    ),
)

@Composable
fun HomeScreen(navController: NavController, vm: HomeViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.refresh() }
    ScreenScaffold(
        title = "Conversation Archive",
        navController = navController,
        showBack = false,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Honest capture banner.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Capture works through your browser + a companion userscript " +
                            "\u2014 opening a site never captures anything by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            platforms.forEach { platform ->
                HomeCard(
                    icon = Icons.Filled.Description,
                    title = platform.name,
                    description = platform.description,
                    actionLabel = "Open guide",
                    onClick = { navController.navigate(Routes.guide(platform.slug)) },
                )
            }

            HomeCard(
                icon = Icons.Filled.Archive,
                title = "Saved Conversations",
                description = if (vm.savedCount == 1) "1 conversation in your on-device archive."
                else "${vm.savedCount} conversations in your on-device archive.",
                actionLabel = "Open library",
                onClick = { navController.navigate(Routes.LIBRARY) },
            )

            HomeCard(
                icon = Icons.Filled.FileUpload,
                title = "Import Conversations",
                description = "Import JSON exports, Markdown, text, HTML, or PDF files.",
                actionLabel = "Import",
                onClick = { navController.navigate(Routes.IMPORT) },
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export and Backup", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Save conversations as JSON, Markdown, text, HTML, or real PDF. " +
                            "Back up the whole archive to a zip file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { navController.navigate(Routes.EXPORT) }) {
                            Text("Export")
                        }
                        TextButton(onClick = { navController.navigate(Routes.BACKUP) }) {
                            Text("Backup")
                        }
                    }
                }
            }

            HomeCard(
                icon = Icons.Filled.Settings,
                title = "Settings",
                description = "Theme, default export format, privacy, and about.",
                actionLabel = "Open settings",
                onClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
    }
}

@Composable
private fun HomeCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onClick,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
