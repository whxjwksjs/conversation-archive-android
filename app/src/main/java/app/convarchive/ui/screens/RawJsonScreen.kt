package app.convarchive.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.ui.components.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class RawJsonViewModel(app: Application) : AndroidViewModel(app) {
    private val repo get() = (getApplication<Application>() as ConvArchiveApp).repository

    var title by mutableStateOf("")
        private set
    var pretty by mutableStateOf<String?>(null)
        private set

    fun load(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = runCatching { repo.get(id) }.getOrNull()
            val prettyJson = entity?.let {
                runCatching {
                    val element = Json.parseToJsonElement(it.rawJson)
                    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
                }.getOrDefault(it.rawJson)
            }
            withContext(Dispatchers.Main) {
                title = entity?.title?.ifBlank { "Untitled" } ?: "Not found"
                pretty = prettyJson
            }
        }
    }
}

@Composable
fun RawJsonScreen(navController: NavController, id: Long, vm: RawJsonViewModel = viewModel()) {
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(id) { vm.load(id) }

    ScreenScaffold(
        title = "Raw JSON",
        navController = navController,
        actions = {
            val text = vm.pretty
            if (text != null) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy JSON")
                }
            }
        },
    ) { padding ->
        val text = vm.pretty
        when {
            text == null -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        vm.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // remember() keeps scroll position stable across recompositions.
                    val stable = remember(text) { text }
                    Text(
                        stable,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
