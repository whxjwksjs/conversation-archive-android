package app.convarchive.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import app.convarchive.data.ConversationEntity
import app.convarchive.export.ConversationExporter
import app.convarchive.export.ExportFile
import app.convarchive.export.ExportFormat
import app.convarchive.ui.ExportPreselect
import app.convarchive.ui.SettingsStore
import app.convarchive.ui.components.PlatformBadge
import app.convarchive.ui.components.ScreenScaffold
import app.convarchive.ui.components.shareExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    val items = mutableStateListOf<ConversationEntity>()
    val selection = mutableStateListOf<Long>()
    var loading by mutableStateOf(true)
        private set
    var working by mutableStateOf(false)

    fun load(preselected: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = runCatching { repo.all() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(all)
                selection.clear()
                selection.addAll(preselected.filter { id -> all.any { it.id == id } })
                loading = false
            }
        }
    }

    fun toggle(id: Long) {
        if (selection.contains(id)) selection.remove(id) else selection.add(id)
    }

    fun selectAll() {
        selection.clear()
        selection.addAll(items.map { it.id })
    }

    fun clearSelection() = selection.clear()

    /** Builds the ExportFile for the current selection (single or zip). Null when empty. */
    suspend fun buildExport(format: ExportFormat): ExportFile? = withContext(Dispatchers.IO) {
        val selected = items.filter { it.id in selection }
        if (selected.isEmpty()) return@withContext null
        val convs = selected.mapNotNull { runCatching { repo.conversationOf(it) }.getOrNull() }
        if (convs.isEmpty()) return@withContext null
        runCatching {
            if (convs.size == 1) ConversationExporter.exportSingle(convs.first(), format)
            else ConversationExporter.exportMany(convs, format)
        }.getOrNull()
    }
}

@Composable
fun ExportScreen(navController: NavController, vm: ExportViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var format by remember {
        mutableStateOf(SettingsStore.getDefaultExportFormat(context))
    }
    var pendingFile by remember { mutableStateOf<ExportFile?>(null) }

    // Consume ids preselected from the Library multi-select action.
    LaunchedEffect(Unit) {
        val preselected = ExportPreselect.ids
        ExportPreselect.ids = emptySet()
        vm.load(preselected)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val file = pendingFile
        pendingFile = null
        vm.working = false
        if (uri != null && file != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                // Remember the format choice for next time.
                SettingsStore.setDefaultExportFormat(context, format)
                scope.launch { snackbar.showSnackbar("Saved ${file.fileName}") }
            } catch (e: Exception) {
                scope.launch { snackbar.showSnackbar("Save failed: ${e.message}") }
            }
        }
    }

    fun onSaveToFile() {
        if (vm.selection.isEmpty() || vm.working) return
        vm.working = true
        scope.launch {
            val file = vm.buildExport(format)
            if (file == null) {
                vm.working = false
                scope.launch { snackbar.showSnackbar("Export failed: could not build the file.") }
            } else {
                pendingFile = file
                saveLauncher.launch(file.fileName)
            }
        }
    }

    fun onShare() {
        if (vm.selection.isEmpty() || vm.working) return
        vm.working = true
        scope.launch {
            val file = vm.buildExport(format)
            vm.working = false
            if (file == null) {
                snackbar.showSnackbar("Export failed: could not build the file.")
            } else {
                SettingsStore.setDefaultExportFormat(context, format)
                runCatching { shareExportFile(context, file) }
                    .onFailure { snackbar.showSnackbar("Share failed: ${it.message}") }
            }
        }
    }

    ScreenScaffold(
        title = "Export",
        navController = navController,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Format", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ExportFormat.entries.forEach { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { format = f },
                ) {
                    RadioButton(selected = format == f, onClick = { format = f })
                    Text("${f.label} (.${f.ext})")
                }
            }
            if (format == ExportFormat.PDF) {
                Text(
                    "PDF uses built-in fonts only; characters outside WinAnsi become \"?\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            when {
                vm.loading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                vm.items.isEmpty() -> {
                    Text("Your library is empty. Import a conversation first.")
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${vm.selection.size} of ${vm.items.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.selectAll() }) {
                            Text("Select all")
                        }
                        TextButton(onClick = { vm.clearSelection() }) {
                            Text("Clear")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.items, key = { it.id }) { entity ->
                            val selected = vm.selection.contains(entity.id)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggle(entity.id) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (selected) Icons.Filled.CheckBox
                                        else Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entity.title.ifBlank { "Untitled" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                        )
                                        PlatformBadge(entity.platform)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (vm.selection.size > 1)
                            "Multiple conversations export as a single .zip."
                        else "One conversation exports as a single file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onSaveToFile() },
                            enabled = vm.selection.isNotEmpty() && !vm.working,
                            modifier = Modifier.weight(1f),
                        ) { Text("Save to file") }
                        OutlinedButton(
                            onClick = { onShare() },
                            enabled = vm.selection.isNotEmpty() && !vm.working,
                            modifier = Modifier.weight(1f),
                        ) { Text("Share") }
                    }
                    if (vm.working) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}


