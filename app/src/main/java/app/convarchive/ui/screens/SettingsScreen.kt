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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.BuildConfig
import app.convarchive.ConvArchiveApp
import app.convarchive.export.ExportFormat
import app.convarchive.ui.SettingsStore
import app.convarchive.ui.components.ScreenScaffold
import app.convarchive.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    var conversationCount by mutableStateOf<Int?>(null)
        private set

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { repo.count() }.getOrNull()
            withContext(Dispatchers.Main) { conversationCount = count }
        }
    }
}

@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val themeMode by SettingsStore.themeMode.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    ScreenScaffold(title = "Settings", navController = navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "Appearance") {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { SettingsStore.setThemeMode(context, mode) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { SettingsStore.setThemeMode(context, mode) },
                        )
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System default"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                        )
                    }
                }
            }

            SettingsSection(title = "Export") {
                Text(
                    "Default export format",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                var defaultFormat by rememberDefaultFormat()
                ExportFormat.entries.forEach { format ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                defaultFormat = format
                                SettingsStore.setDefaultExportFormat(context, format)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = defaultFormat == format,
                            onClick = {
                                defaultFormat = format
                                SettingsStore.setDefaultExportFormat(context, format)
                            },
                        )
                        Text("${format.label} (.${format.ext})")
                    }
                }
            }

            SettingsSection(title = "Storage") {
                val count = vm.conversationCount
                if (count == null) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Text(
                        if (count == 1) "1 conversation stored." else "$count conversations stored.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your archive is stored only on this device, in the app's private " +
                        "database. Nothing is uploaded anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "What happens if I uninstall",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Uninstalling the app or clearing its data permanently deletes your " +
                        "archive. Make a backup first (Export and Backup \u2192 Backup) and " +
                        "keep the .zip somewhere safe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = "Privacy") {
                PrivacyBullet("No accounts: there is nothing to sign in to.")
                PrivacyBullet("No network: the app makes no network calls of its own. The only " +
                    "network use is opening official sites in your own browser when you tap " +
                    "\"Open in browser\".")
                PrivacyBullet("No telemetry, analytics, or crash reporting.")
                PrivacyBullet("No passwords, cookies, or tokens are ever requested, stored, " +
                    "or transmitted. Capture happens in your browser, under your control.")
            }

            SettingsSection(title = "About") {
                Text(
                    "Conversation Archive ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        "DEBUG build: signed with the Android debug key, not for release.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Import support for community exporter formats",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "This app can import exports produced by third-party community projects: " +
                        "Voyager (nagi-ovo, Gemini exports), ai-studio-conversation-exporter " +
                        "(rmtd418), and aistudio-dump-script (Tokisaki-Galaxy). Those projects " +
                        "are independent works; their own licenses apply to their code and " +
                        "outputs. See their repositories for details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Research notes: see RESEARCH.md in the project repository for the " +
                        "browser/userscript compatibility research behind the capture workflow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("\u2022 ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Remembers the persisted default export format. */
@Composable
private fun rememberDefaultFormat(): MutableState<ExportFormat> {
    val context = LocalContext.current
    return remember {
        mutableStateOf(SettingsStore.getDefaultExportFormat(context))
    }
}
