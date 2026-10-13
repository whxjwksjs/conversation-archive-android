package app.convarchive.ui.screens

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.data.ConversationEntity
import app.convarchive.data.SortOrder
import app.convarchive.ui.ExportPreselect
import app.convarchive.ui.Routes
import app.convarchive.ui.components.PlatformBadge
import app.convarchive.ui.components.ScreenScaffold
import app.convarchive.ui.components.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PlatformFilter(val label: String, val value: String?)
private val platformFilters = listOf(
    PlatformFilter("All", null),
    PlatformFilter("Gemini", "gemini"),
    PlatformFilter("AI Studio", "aistudio"),
    PlatformFilter("Jules", "jules"),
    PlatformFilter("Other", "other"),
)

private data class DateFilter(val label: String, val days: Long?)
private val dateFilters = listOf(
    DateFilter("All", null),
    DateFilter("Last 7 days", 7),
    DateFilter("Last 30 days", 30),
)

private val sortOptions = listOf(
    "Newest" to SortOrder.NEWEST,
    "Oldest" to SortOrder.OLDEST,
    "Title" to SortOrder.TITLE,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    var query by mutableStateOf("")
    var platform by mutableStateOf<String?>(null)
    var dateDays by mutableStateOf<Long?>(null)
    var sort by mutableStateOf(SortOrder.NEWEST)

    val items = mutableStateListOf<ConversationEntity>()
    var loading by mutableStateOf(false)
        private set
    val selection = mutableStateListOf<Long>()
    var showDeleteConfirm by mutableStateOf(false)
        private set

    private var queryJob: Job? = null

    fun reload() {
        queryJob?.cancel()
        loading = true
        val q = query
        val p = platform
        val now = System.currentTimeMillis()
        val fromMs = dateDays?.let { now - it * 24 * 60 * 60 * 1000 }
        val s = sort
        queryJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repo.query(q, p, fromMs, null, s) }.getOrDefault(emptyList())
            // Keep selection consistent with what is on screen.
            val visible = result.map { it.id }.toSet()
            launch(Dispatchers.Main) {
                items.clear()
                items.addAll(result)
                selection.retainAll(visible)
                loading = false
            }
        }
    }

    /** Debounced reload for the search field. */
    fun onQueryChanged(newQuery: String) {
        query = newQuery
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun toggleSelect(id: Long) {
        if (selection.contains(id)) selection.remove(id) else selection.add(id)
    }

    fun clearSelection() = selection.clear()

    fun requestDelete() { showDeleteConfirm = true }
    fun dismissDelete() { showDeleteConfirm = false }

    fun confirmDelete() {
        val ids = selection.toList()
        showDeleteConfirm = false
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.delete(ids) }
            launch(Dispatchers.Main) {
                selection.clear()
                reload()
            }
        }
    }

    fun favoriteSelection() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val targets = items.filter { it.id in ids }
            val makeFavorite = targets.any { !it.favorite }
            targets.forEach { e ->
                runCatching {
                    repo.updateMeta(
                        e.id, e.title, e.notes,
                        e.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        makeFavorite,
                    )
                }
            }
            launch(Dispatchers.Main) { reload() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController, vm: LibraryViewModel = viewModel()) {
    val selecting = vm.selection.isNotEmpty()

    LaunchedEffect(Unit) { vm.reload() }

    ScreenScaffold(
        title = if (selecting) "${vm.selection.size} selected" else "Library",
        navController = navController,
        actions = {
            if (selecting) {
                IconButton(onClick = {
                    ExportPreselect.ids = vm.selection.toSet()
                    vm.clearSelection()
                    navController.navigate(Routes.EXPORT)
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Export selected")
                }
                IconButton(onClick = { vm.favoriteSelection() }) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Toggle favorite")
                }
                IconButton(onClick = { vm.requestDelete() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
                IconButton(onClick = { vm.clearSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.onQueryChanged(it) },
                label = { Text("Search title and content") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                platformFilters.forEach { f ->
                    FilterChip(
                        selected = vm.platform == f.value,
                        onClick = {
                            vm.platform = f.value
                            vm.reload()
                        },
                        label = { Text(f.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                dateFilters.forEach { f ->
                    FilterChip(
                        selected = vm.dateDays == f.days,
                        onClick = {
                            vm.dateDays = f.days
                            vm.reload()
                        },
                        label = { Text(f.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                var sortExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = !sortExpanded },
                ) {
                    OutlinedTextField(
                        value = sortOptions.first { it.second == vm.sort }.first,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sort") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sortExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .width(140.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                    ) {
                        sortOptions.forEach { (label, order) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.sort = order
                                    sortExpanded = false
                                    vm.reload()
                                },
                                trailingIcon = {
                                    if (order == vm.sort) Icon(Icons.Filled.Check, null)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (vm.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (vm.items.isEmpty()) {
                Text(
                    "No conversations match. Import one to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.items, key = { it.id }) { entity ->
                        ConversationCard(
                            entity = entity,
                            selected = vm.selection.contains(entity.id),
                            selecting = selecting,
                            onToggleSelect = { vm.toggleSelect(entity.id) },
                            onOpen = { navController.navigate(Routes.detail(entity.id)) },
                        )
                    }
                }
            }
        }
    }

    if (vm.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissDelete() },
            title = { Text("Delete ${vm.selection.size} conversation(s)?") },
            text = { Text("This permanently removes them from your on-device archive. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDelete() }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    entity: ConversationEntity,
    selected: Boolean,
    selecting: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entity.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlatformBadge(entity.platform)
                    Text(
                        "${entity.messageCount} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatDateTime(entity.createdAt ?: entity.importedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entity.favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (selecting) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = "Select",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
