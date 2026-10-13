package app.convarchive.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.convarchive.ConvArchiveApp
import app.convarchive.data.ConversationEntity
import app.convarchive.export.ConversationExporter
import app.convarchive.export.ExportFile
import app.convarchive.export.ExportFormat
import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.ui.Routes
import app.convarchive.ui.components.CodeBlockView
import app.convarchive.ui.components.MarkdownLiteText
import app.convarchive.ui.components.PlatformBadge
import app.convarchive.ui.components.ScreenScaffold
import app.convarchive.ui.components.formatBytes
import app.convarchive.ui.components.formatDateTime
import app.convarchive.ui.components.shareExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    var entity by mutableStateOf<ConversationEntity?>(null)
        private set
    var conversation by mutableStateOf<CanonicalConversation?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var notFound by mutableStateOf(false)
        private set

    var showRename by mutableStateOf(false)
    var showDeleteConfirm by mutableStateOf(false)
    var showExportPicker by mutableStateOf(false)

    fun load(id: Long) {
        loading = true
        notFound = false
        viewModelScope.launch(Dispatchers.IO) {
            val e = runCatching { repo.get(id) }.getOrNull()
            val conv = e?.let { runCatching { repo.conversationOf(it) }.getOrNull() }
            withContext(Dispatchers.Main) {
                entity = e
                conversation = conv
                loading = false
                notFound = e == null
            }
        }
    }

    fun rename(newTitle: String) {
        val e = entity ?: return
        showRename = false
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.updateMeta(
                    e.id, newTitle, e.notes,
                    e.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    e.favorite,
                )
            }
            withContext(Dispatchers.Main) { load(e.id) }
        }
    }

    fun toggleFavorite() {
        val e = entity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.updateMeta(
                    e.id, e.title, e.notes,
                    e.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    !e.favorite,
                )
            }
            withContext(Dispatchers.Main) { load(e.id) }
        }
    }

    fun saveTagsAndNotes(tagsCsv: String, notes: String) {
        val e = entity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.updateMeta(
                    e.id, e.title, notes,
                    tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    e.favorite,
                )
            }
            withContext(Dispatchers.Main) { load(e.id) }
        }
    }

    fun delete(onDone: () -> Unit) {
        val e = entity ?: return
        showDeleteConfirm = false
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.delete(listOf(e.id)) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}

@Composable
fun DetailScreen(navController: NavController, id: Long, vm: DetailViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(id) { vm.load(id) }

    var pendingExport by remember { mutableStateOf<ExportFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) }
            } catch (_: Exception) {
                // Save failed; the user can retry via the Export dialog.
            }
        }
    }

    fun doExport(format: ExportFormat, share: Boolean) {
        val conv = vm.conversation ?: return
        vm.showExportPicker = false
        try {
            val file = ConversationExporter.exportSingle(conv, format)
            if (share) {
                shareExportFile(context, file)
            } else {
                pendingExport = file
                saveLauncher.launch(file.fileName)
            }
        } catch (_: Exception) {
            // Export failed; user can retry.
        }
    }

    ScreenScaffold(
        title = "Conversation",
        navController = navController,
        actions = {
            val fav = vm.entity?.favorite == true
            IconButton(onClick = { vm.toggleFavorite() }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (fav) "Unfavorite" else "Favorite",
                )
            }
            IconButton(onClick = { vm.showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        },
    ) { padding ->
        when {
            vm.loading -> {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            vm.notFound -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                ) {
                    Text("Conversation not found. It may have been deleted.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { navController.popBackStack() }) { Text("Back") }
                }
            }
            else -> {
                val entity = vm.entity!!
                val conv = vm.conversation
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        // Title (tap to rename) + metadata.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entity.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { vm.showRename = true },
                            )
                            IconButton(onClick = { vm.showRename = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformBadge(entity.platform)
                            Text(
                                "${entity.messageCount} messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MetadataRow("Created", formatDateTime(entity.createdAt))
                        MetadataRow("Exported", formatDateTime(entity.exportedAt))
                        MetadataRow("Importer", entity.exporter)
                        if (!entity.sourceUrl.isNullOrBlank()) {
                            MetadataRow("Source", entity.sourceUrl!!)
                        }
                        conv?.let {
                            val model = it.metadata["model"]?.let { el ->
                                (el as? JsonPrimitive)?.contentOrNull
                            }
                            if (!model.isNullOrBlank()) MetadataRow("Model", model)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.showExportPicker = true }) {
                                Icon(Icons.Filled.Share, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Export")
                            }
                            OutlinedButton(
                                onClick = { navController.navigate(Routes.rawJson(entity.id)) }
                            ) {
                                Icon(Icons.Filled.Code, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Raw JSON")
                            }
                        }
                    }

                    item {
                        TagsNotesEditor(
                            tagsCsv = entity.tags,
                            notes = entity.notes,
                            onSave = { tags, notes -> vm.saveTagsAndNotes(tags, notes) },
                        )
                    }

                    if (conv != null) {
                        items(conv.messages) { message ->
                            MessageCard(message = message)
                        }
                    }
                }
            }
        }
    }

    // Rename dialog.
    if (vm.showRename) {
        var draft by remember(vm.entity?.id) { mutableStateOf(vm.entity?.title ?: "") }
        AlertDialog(
            onDismissRequest = { vm.showRename = false },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.rename(draft) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { vm.showRename = false }) { Text("Cancel") }
            },
        )
    }

    // Delete confirmation.
    if (vm.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.showDeleteConfirm = false },
            title = { Text("Delete this conversation?") },
            text = { Text("This permanently removes it from your on-device archive. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.delete { navController.popBackStack() } }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Export format picker.
    if (vm.showExportPicker) {
        var format by remember { mutableStateOf(ExportFormat.JSON) }
        AlertDialog(
            onDismissRequest = { vm.showExportPicker = false },
            title = { Text("Export format") },
            text = {
                Column {
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
                            "Note: PDF uses built-in fonts only; characters outside " +
                                "WinAnsi are replaced with \"?\". Other formats keep full Unicode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doExport(format, share = true) }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { doExport(format, share = false) }) { Text("Save") }
                    TextButton(onClick = { vm.showExportPicker = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TagsNotesEditor(tagsCsv: String, notes: String, onSave: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var tagsDraft by remember { mutableStateOf(tagsCsv) }
    var notesDraft by remember { mutableStateOf(notes) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(tagsCsv, notes) {
        if (!dirty) {
            tagsDraft = tagsCsv
            notesDraft = notes
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Text(
                    "Tags & notes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "Hide" else "Edit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!expanded) {
                if (tagsCsv.isNotBlank()) {
                    Text(tagsCsv, style = MaterialTheme.typography.bodySmall)
                }
                if (notes.isNotBlank()) {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (tagsCsv.isBlank() && notes.isBlank()) {
                    Text(
                        "No tags or notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tagsDraft,
                    onValueChange = { tagsDraft = it; dirty = true },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it; dirty = true },
                    label = { Text("Notes") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    onSave(tagsDraft, notesDraft)
                    dirty = false
                    expanded = false
                }) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: CanonicalMessage) {
    val (label, container) = when (message.role.lowercase()) {
        "user" -> "You" to MaterialTheme.colorScheme.primaryContainer
        "assistant" -> "Assistant" to MaterialTheme.colorScheme.surfaceVariant
        "system" -> "System" to MaterialTheme.colorScheme.tertiaryContainer
        "tool" -> "Tool" to MaterialTheme.colorScheme.surfaceVariant
        "event" -> "Event" to MaterialTheme.colorScheme.secondaryContainer
        else -> message.role.replaceFirstChar { it.uppercase() } to MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SurfaceBadge(label, container)
                if (!message.model.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message.model!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!message.createdAt.isNullOrBlank()) {
                    Text(
                        message.createdAt!!.take(16).replace("T", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val body = message.markdown?.takeIf { it.isNotBlank() } ?: message.text
            if (body.isNotBlank()) {
                MarkdownLiteText(body)
            } else {
                Text(
                    "(empty message)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.codeBlocks.forEach { block ->
                Spacer(Modifier.height(4.dp))
                CodeBlockView(language = block.language, code = block.code)
            }
            message.attachments.forEach { att ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            att.name?.ifBlank { "attachment" } ?: "attachment",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            listOfNotNull(
                                att.mimeType?.takeIf { it.isNotBlank() },
                                (att.sizeBytes ?: 0).takeIf { it > 0 }?.let { formatBytes(it) },
                                att.note?.takeIf { it.isNotBlank() },
                            ).joinToString(" \u00b7 "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceBadge(
    label: String,
    container: androidx.compose.ui.graphics.Color,
) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
