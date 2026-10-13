package app.convarchive.ui.screens

import android.app.Application
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.IncomingUris
import app.convarchive.data.InsertOutcome
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.ParserRegistry
import app.convarchive.ui.components.ScreenScaffold
import app.convarchive.ui.components.displayNameFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val pickMimeTypes = arrayOf("application/json", "text/*", "application/pdf", "*/*")

data class ImportJob(
    val key: Int,
    val fileName: String,
    val parsing: Boolean = true,
    val result: ImportResult? = null,
    /** Set once the conversation is saved. */
    val outcome: InsertOutcome? = null,
    /** Set when the import reported a duplicate (not yet force-saved). */
    val duplicateOf: Long? = null,
    val parseError: String? = null,
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository
    private val appContext: Context get() = getApplication<Application>().applicationContext

    val jobs: SnapshotStateList<ImportJob> = mutableStateListOf()
    private var nextKey = 0

    val anyParsing: Boolean get() = jobs.any { it.parsing }

    /** Replace a job in the list so Compose recomposes its card. */
    private fun updateJob(job: ImportJob, transform: (ImportJob) -> ImportJob) {
        val index = jobs.indexOfFirst { it.key == job.key }
        if (index >= 0) jobs[index] = transform(jobs[index])
    }

    fun addUris(uris: List<Uri>) {
        val context = appContext
        uris.forEach { uri ->
            val job = ImportJob(key = nextKey++, fileName = context.displayNameFor(uri))
            jobs.add(job)
            viewModelScope.launch(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    withContext(Dispatchers.Main) {
                        updateJob(job) { it.copy(parsing = false, parseError = "Could not read this file.") }
                    }
                    return@launch
                }
                val result = runCatching { ParserRegistry.parseFile(job.fileName, bytes) }
                    .getOrElse { e ->
                        ImportResult(
                            ok = false,
                            errors = listOf("Parser crashed: ${e.message}"),
                        )
                    }
                withContext(Dispatchers.Main) {
                    updateJob(job) { it.copy(result = result, parsing = false) }
                }
            }
        }
    }

    fun save(job: ImportJob) {
        val conv = job.result?.conversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching { repo.insert(conv) }.getOrNull()
            withContext(Dispatchers.Main) {
                updateJob(job) {
                    when {
                        outcome == null -> it.copy(parseError = "Save failed (database error).")
                        outcome.wasDuplicate -> it.copy(duplicateOf = outcome.existingId)
                        else -> it.copy(outcome = outcome)
                    }
                }
            }
        }
    }

    fun saveAnyway(job: ImportJob) {
        val conv = job.result?.conversation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching { repo.insertAnyway(conv) }.getOrNull()
            withContext(Dispatchers.Main) {
                updateJob(job) {
                    if (id == null) it.copy(parseError = "Save failed (database error).")
                    else it.copy(
                        outcome = InsertOutcome(id, wasDuplicate = true, existingId = it.duplicateOf),
                        duplicateOf = null,
                    )
                }
            }
        }
    }

    /** Saves every parsed, unsaved, non-duplicate conversation. */
    fun saveAll() {
        jobs.filter { it.result?.ok == true && it.outcome == null && it.duplicateOf == null }
            .forEach { save(it) }
    }

    fun clearFinished() {
        jobs.removeAll { it.outcome != null }
    }
}

@Composable
fun ImportScreen(navController: NavController, vm: ImportViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.addUris(uris)
    }

    // Consume share-sheet / ACTION_VIEW URIs staged by MainActivity.
    LaunchedEffect(Unit) {
        val incoming = IncomingUris.consume()
        if (incoming.isNotEmpty()) vm.addUris(incoming)
    }

    ScreenScaffold(
        title = "Import",
        navController = navController,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Button(
                onClick = { picker.launch(pickMimeTypes) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pick a file")
            }
            Text(
                "JSON exports, Markdown, text, HTML, or PDF. You can also share a file " +
                    "to Conversation Archive from any app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (vm.anyParsing) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Parsing\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val saveable = vm.jobs.count {
                it.result?.ok == true && it.outcome == null && it.duplicateOf == null && !it.parsing
            }
            if (saveable > 1) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { vm.saveAll() }, modifier = Modifier.weight(1f)) {
                        Text("Save all ($saveable)")
                    }
                    OutlinedButton(onClick = { vm.clearFinished() }) {
                        Text("Clear saved")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.jobs, key = { it.key }) { job ->
                    ImportJobCard(
                        job = job,
                        onSave = { vm.save(job) },
                        onSaveAnyway = { vm.saveAnyway(job) },
                        onRetryDismiss = { vm.jobs.remove(job) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportJobCard(
    job: ImportJob,
    onSave: () -> Unit,
    onSaveAnyway: () -> Unit,
    onRetryDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    job.parsing -> Text("Parsing\u2026", style = MaterialTheme.typography.bodySmall)
                    job.outcome != null -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    job.result?.ok == false || job.parseError != null -> Icon(
                        Icons.Filled.Error,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    job.duplicateOf != null -> Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Duplicate",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            val result = job.result
            if (result != null && result.ok && result.conversation != null) {
                val conv = result.conversation!!
                Spacer(Modifier.height(4.dp))
                Text(conv.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Platform: ${result.detectedPlatform} \u00b7 " +
                        "${conv.messages.size} messages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val exporter = result.detectedExporter.takeIf { it != "unknown" }
                    ?: conv.exporter.takeIf { it.isNotBlank() }
                if (!exporter.isNullOrBlank()) {
                    Text(
                        "Exporter: $exporter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Warnings and errors shown verbatim.
            result?.warnings?.forEach { warning ->
                Spacer(Modifier.height(4.dp))
                Row {
                    Icon(
                        Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall)
                }
            }
            result?.errors?.forEach { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            job.parseError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            when {
                job.outcome != null -> {
                    Text(
                        "Saved to library as #${job.outcome!!.id}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onRetryDismiss) { Text("Dismiss") }
                }
                job.duplicateOf != null -> {
                    Text(
                        "Duplicate of #${job.duplicateOf} \u2014 already in your library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSaveAnyway) { Text("Import anyway") }
                        TextButton(onClick = onRetryDismiss) { Text("Skip") }
                    }
                }
                result?.ok == true -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave) { Text("Save to library") }
                        TextButton(onClick = onRetryDismiss) { Text("Discard") }
                    }
                }
                !job.parsing -> {
                    TextButton(onClick = onRetryDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}
