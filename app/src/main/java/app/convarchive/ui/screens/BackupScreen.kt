package app.convarchive.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.backup.BackupManager
import app.convarchive.ui.components.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    var working by mutableStateOf(false)
    var lastResult by mutableStateOf<String?>(null)

    fun createBackup(onBytes: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (working) return
        working = true
        lastResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val convs = repo.all().map { repo.conversationOf(it) }
                BackupManager.createBackup(convs)
            }
            withContext(Dispatchers.Main) {
                working = false
                result.fold(
                    onSuccess = { onBytes(it) },
                    onFailure = { onError("Backup failed: ${it.message}") },
                )
            }
        }
    }

    fun restoreBackup(
        bytes: ByteArray,
        onDone: (imported: Int, duplicates: Int, failed: Int, errors: List<String>) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (working) return
        working = true
        lastResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { BackupManager.parseBackup(bytes) }
            if (parsed.isFailure) {
                withContext(Dispatchers.Main) {
                    working = false
                    onError("Restore failed: ${parsed.exceptionOrNull()?.message}")
                }
                return@launch
            }
            var imported = 0
            var duplicates = 0
            var failed = 0
            val errors = mutableListOf<String>()
            for (result in parsed.getOrThrow()) {
                val conv = result.conversation
                if (!result.ok || conv == null) {
                    failed++
                    val name = result.detectedExporter.takeIf { it != "unknown" } ?: "backup entry"
                    errors.add("$name: ${result.errors.joinToString("; ").ifBlank { "unknown error" }}")
                    continue
                }
                val outcome = runCatching { repo.insert(conv) }.getOrNull()
                if (outcome == null) {
                    failed++
                    errors.add("${conv.title.ifBlank { "Untitled" }}: database error")
                } else if (outcome.wasDuplicate) {
                    duplicates++
                } else {
                    imported++
                }
            }
            withContext(Dispatchers.Main) {
                working = false
                lastResult = "Restore complete: $imported imported, $duplicates duplicates skipped, $failed failed."
                onDone(imported, duplicates, failed, errors)
            }
        }
    }
}

@Composable
fun BackupScreen(navController: NavController, vm: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var restoreErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val backupDate = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val bytes = pendingBackup
        pendingBackup = null
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                vm.lastResult = "Backup saved."
                scope.launch { snackbar.showSnackbar("Backup saved.") }
            } catch (e: Exception) {
                scope.launch { snackbar.showSnackbar("Save failed: ${e.message}") }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    scope.launch { snackbar.showSnackbar("Could not read that file.") }
                } else {
                    vm.restoreBackup(
                        bytes,
                        onDone = { _, _, _, errors ->
                            restoreErrors = errors
                            scope.launch {
                                snackbar.showSnackbar(vm.lastResult ?: "Restore complete.")
                            }
                        },
                        onError = { msg ->
                            scope.launch { snackbar.showSnackbar(msg) }
                        },
                    )
                }
            }
        }
    }

    ScreenScaffold(
        title = "Backup",
        navController = navController,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("What a backup contains", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A backup is a .zip file with a manifest plus one canonical JSON " +
                            "file per conversation, including each conversation's original " +
                            "export. Restoring re-imports everything through the normal " +
                            "import pipeline, so backups keep working across app updates.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Uninstalling the app or clearing its data permanently deletes " +
                                "your archive \u2014 keep backups somewhere safe, like cloud " +
                                "storage or your computer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    vm.createBackup(
                        onBytes = { bytes ->
                            pendingBackup = bytes
                            createLauncher.launch("convarchive_backup_$backupDate.zip")
                        },
                        onError = { msg ->
                            scope.launch { snackbar.showSnackbar(msg) }
                        },
                    )
                },
                enabled = !vm.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Backup, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create backup")
            }

            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
                enabled = !vm.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Restore backup")
            }

            if (vm.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            vm.lastResult?.let { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            restoreErrors.forEach { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
