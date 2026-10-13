package app.convarchive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.convarchive.ui.Nav
import app.convarchive.ui.SettingsStore
import app.convarchive.ui.theme.ConversationArchiveTheme
import java.io.File

/**
 * Shared holder for URIs arriving via share sheet (ACTION_SEND) or
 * ACTION_VIEW. [ImportScreen] consumes and clears them on entry.
 *
 * EXTRA_TEXT shares are written to a cache file first so the import pipeline
 * always works with (fileName, bytes) pairs.
 */
object IncomingUris {
    var uris: List<Uri> = emptyList()
    /** Bumped on every set so re-delivery while Import is open re-triggers. */
    var version: Int = 0
        private set

    fun set(newUris: List<Uri>) {
        uris = newUris
        version++
    }

    fun consume(): List<Uri> {
        val out = uris
        uris = emptyList()
        return out
    }
}

class MainActivity : ComponentActivity() {

    private var navControllerRef: NavHostController? = null

    /** Set true when an intent should land the user on the Import screen. */
    private var pendingImport by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(applicationContext)
        setContent {
            val mode by SettingsStore.themeMode.collectAsState()
            ConversationArchiveTheme(mode = mode) {
                val navController = rememberNavController()
                navControllerRef = navController
                Nav(
                    navController = navController,
                    pendingImport = pendingImport,
                    onImportConsumed = { pendingImport = false },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                ).orEmpty()
                val single = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                val uris = buildList {
                    addAll(streams)
                    if (single != null && single !in streams) add(single)
                }
                if (uris.isNotEmpty()) {
                    IncomingUris.set(uris)
                    pendingImport = true
                } else {
                    // Plain-text share: stage it as a .txt file for the importer.
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!text.isNullOrBlank()) {
                        val dir = File(cacheDir, "shared").apply { mkdirs() }
                        val file = File(dir, "shared-text-${System.currentTimeMillis()}.txt")
                        file.writeText(text)
                        IncomingUris.set(listOf(Uri.fromFile(file)))
                        pendingImport = true
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null) {
                    IncomingUris.set(listOf(data))
                    pendingImport = true
                }
            }
        }
    }
}
