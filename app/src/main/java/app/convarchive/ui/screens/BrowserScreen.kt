package app.convarchive.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import app.convarchive.ConvArchiveApp
import app.convarchive.model.parsers.CanonicalParser
import app.convarchive.model.parsers.ParserRegistry
import app.convarchive.ui.Routes
import app.convarchive.ui.browser.BridgeOutcome
import app.convarchive.ui.browser.DESKTOP_CHROME_UA
import app.convarchive.ui.browser.DownloadHelper
import app.convarchive.ui.browser.JsBridgeResult
import app.convarchive.ui.browser.browserPlatformFor
import app.convarchive.ui.components.ScreenScaffold
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** DTO for the extractor's __convarchiveScrollState() JSON. */
@Serializable
private data class ScrollStateDto(
    val started: Boolean = false,
    val done: Boolean = false,
    val passes: Int = 0,
    val found: Int = 0,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Result shown in the post-capture dialog. */
private sealed interface CaptureResult {
    data class Saved(val id: Long, val title: String, val count: Int) : CaptureResult
    data class Duplicate(val existingId: Long, val title: String) : CaptureResult
    data class NothingFound(val reason: String) : CaptureResult
    data class Failed(val reason: String) : CaptureResult
}

/** Suspend wrapper around WebView.evaluateJavascript. */
private suspend fun WebView.evalJs(script: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { value -> cont.resume(value) }
    }

/**
 * In-app browser for one platform (Gemini / AI Studio / Jules).
 *
 * The user logs in with their Google account on Google's own pages inside
 * this WebView. Login persists via the WebView cookie store
 * (CookieManager, flushed on pause). The app never reads password fields,
 * cookies, or tokens, and makes no network calls of its own except loading
 * the official site pages (and same-site file downloads the page offers).
 *
 * "Save conversation" runs the injected extractor (assets/extractor.js),
 * which reads only the visible conversation DOM and returns canonical
 * JSON v1; the app then saves it into the Room library with the normal
 * duplicate detection.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(navController: NavController, platform: String) {
    val info = remember(platform) { browserPlatformFor(platform) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var captureProgress by remember { mutableStateOf("") }
    var captureCancelled by remember { mutableStateOf(false) }
    var captureResult by remember { mutableStateOf<CaptureResult?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadBusy by remember { mutableStateOf<String?>(null) }

    val extractorJs = remember {
        runCatching {
            context.assets.open("extractor.js").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    val webView = remember {
        WebView(context).apply {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(this, true)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = DESKTOP_CHROME_UA
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    pageError = null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        pageError = "Page failed to load: ${error.description} " +
                            "(check your connection, then reload)"
                    }
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!DownloadHelper.isAllowedUrl(url)) {
                    scope.launch {
                        snackbar.showSnackbar("Download blocked: not a google.com URL")
                    }
                    return@DownloadListener
                }
                downloadBusy = DownloadHelper.fileNameFor(url, contentDisposition, mimeType)
                scope.launch {
                    try {
                        val file = DownloadHelper.download(
                            context, url, userAgent, contentDisposition, mimeType
                        )
                        downloadedFile = file
                    } catch (e: Exception) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Download failed: ${e.message?.take(120) ?: e}"
                            )
                        }
                    } finally {
                        downloadBusy = null
                    }
                }
            })
        }
    }

    // Pause/resume the WebView with the lifecycle; flush cookies on pause so
    // the login survives app restarts.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.destroy()
        }
    }

    LaunchedEffect(info.url) {
        if (webView.url == null) webView.loadUrl(info.url)
    }

    BackHandler(enabled = canGoBack && !capturing) {
        webView.goBack()
    }

    fun startCapture() {
        val js = extractorJs
        if (js == null) {
            captureResult = CaptureResult.Failed(
                "extractor script missing from the app package — reinstall the app"
            )
            return
        }
        captureCancelled = false
        capturing = true
        captureProgress = "Preparing extractor…"
        scope.launch {
            try {
                // 1. Inject the extractor library.
                webView.evalJs(js)
                if (captureCancelled) return@launch
                // 2. Bounded auto-scroll to load virtualized history.
                captureProgress = "Loading full conversation…"
                webView.evalJs("__convarchiveStartScroll('${info.extractorKey}')")
                val scrollDone = withTimeoutOrNull(65_000) {
                    while (true) {
                        if (captureCancelled) return@withTimeoutOrNull false
                        val raw = webView.evalJs("__convarchiveScrollState()")
                        val state: ScrollStateDto? = runCatching {
                            val inner: String = lenientJson.decodeFromString(
                                serializer<String>(),
                                raw?.trim() ?: "null",
                            )
                            lenientJson.decodeFromString(ScrollStateDto.serializer(), inner)
                        }.getOrNull()
                        if (state != null) {
                            if (state.done) return@withTimeoutOrNull true
                            captureProgress =
                                "Loading full conversation… pass ${state.passes}, " +
                                    "${state.found} turns found"
                        }
                        delay(700)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                } ?: false
                if (captureCancelled) return@launch
                if (!scrollDone) {
                    captureResult = CaptureResult.Failed(
                        "timed out while loading the conversation history — " +
                            "try scrolling through it manually first, then save again"
                    )
                    return@launch
                }
                // 3. Extract.
                captureProgress = "Reading conversation…"
                val raw = webView.evalJs("__convarchiveExtract('${info.extractorKey}')")
                when (val outcome = JsBridgeResult.parse(raw)) {
                    is BridgeOutcome.Valid -> {
                        val parsed = CanonicalParser.parse(outcome.json, "webview-capture.json")
                        val conv = parsed.conversation
                        if (!parsed.ok || conv == null) {
                            captureResult = CaptureResult.Failed(
                                "the extracted data was invalid: " +
                                    (parsed.errors.firstOrNull() ?: "unknown error")
                            )
                        } else if (conv.messages.isEmpty()) {
                            captureResult = CaptureResult.NothingFound(
                                "the extractor returned no messages — open a conversation " +
                                    "first, then try again"
                            )
                        } else {
                            val repo = (context.applicationContext as ConvArchiveApp).repository
                            val result = withContext(Dispatchers.IO) { repo.insert(conv) }
                            captureResult = if (result.wasDuplicate) {
                                CaptureResult.Duplicate(
                                    result.existingId ?: -1L,
                                    conv.title.ifBlank { "Untitled" },
                                )
                            } else {
                                CaptureResult.Saved(
                                    result.id,
                                    conv.title.ifBlank { "Untitled" },
                                    conv.messages.size,
                                )
                            }
                        }
                    }
                    is BridgeOutcome.Empty -> captureResult =
                        CaptureResult.NothingFound(outcome.reason)
                    is BridgeOutcome.JsError -> captureResult =
                        CaptureResult.Failed(outcome.message)
                    is BridgeOutcome.Malformed -> captureResult =
                        CaptureResult.Failed(outcome.details)
                }
            } catch (e: Exception) {
                if (!captureCancelled) {
                    captureResult = CaptureResult.Failed(
                        "capture failed: ${e.message?.take(160) ?: e}"
                    )
                }
            } finally {
                capturing = false
            }
        }
    }

    ScreenScaffold(
        title = info.name,
        navController = navController,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { pageError = null; webView.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: info.url)))
            }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = "Open in external browser")
            }
            IconButton(onClick = { navController.navigate(Routes.guide(info.slug)) }) {
                Icon(Icons.Filled.Info, contentDescription = "Capture guide and limitations")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )

            if (pageError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Text(pageError!!, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "If Google refuses to load here, use \"Open in external browser\" " +
                            "above and import the export as in v1.0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExtendedFloatingActionButton(
                onClick = { if (!capturing) startCapture() },
                icon = { Icon(Icons.Filled.Save, contentDescription = null) },
                text = { Text("Save conversation") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )

            if (downloadBusy != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp),
                ) {
                    Text(
                        "Downloading ${downloadBusy}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (capturing) {
        AlertDialog(
            onDismissRequest = { /* must use Cancel */ },
            title = { Text("Saving conversation") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(captureProgress, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    captureCancelled = true
                    capturing = false
                }) { Text("Cancel") }
            },
        )
    }

    captureResult?.let { result ->
        val (title, message, viewId) = when (result) {
            is CaptureResult.Saved -> Triple(
                "Saved to library",
                "\"${result.title}\" — ${result.count} messages saved.",
                result.id,
            )
            is CaptureResult.Duplicate -> Triple(
                "Already in your library",
                "\"${result.title}\" is already saved — skipped as a duplicate.",
                result.existingId,
            )
            is CaptureResult.NothingFound -> Triple(
                "Nothing found",
                result.reason,
                null,
            )
            is CaptureResult.Failed -> Triple(
                "Save failed",
                result.reason,
                null,
            )
        }
        AlertDialog(
            onDismissRequest = { captureResult = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { captureResult = null }) { Text("OK") }
            },
            dismissButton = {
                if (viewId != null && viewId > 0) {
                    TextButton(onClick = {
                        captureResult = null
                        navController.navigate(Routes.detail(viewId))
                    }) { Text("View") }
                }
            },
        )
    }

    downloadedFile?.let { file ->
        AlertDialog(
            onDismissRequest = { downloadedFile = null },
            title = { Text("Download finished") },
            text = { Text("${file.name} saved. Import it into your library?") },
            confirmButton = {
                TextButton(onClick = {
                    downloadedFile = null
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                        val parsed = runCatching {
                            ParserRegistry.parseFile(file.name, bytes)
                        }.getOrElse {
                            captureResult = CaptureResult.Failed(
                                "could not parse ${file.name}: ${it.message?.take(140)}"
                            )
                            return@launch
                        }
                        val conv = parsed.conversation
                        if (!parsed.ok || conv == null) {
                            captureResult = CaptureResult.Failed(
                                parsed.errors.firstOrNull() ?: "could not parse ${file.name}"
                            )
                            return@launch
                        }
                        val repo = (context.applicationContext as ConvArchiveApp).repository
                        val outcome = withContext(Dispatchers.IO) { repo.insert(conv) }
                        captureResult = if (outcome.wasDuplicate) {
                            CaptureResult.Duplicate(
                                outcome.existingId ?: -1L, conv.title.ifBlank { "Untitled" }
                            )
                        } else {
                            CaptureResult.Saved(
                                outcome.id, conv.title.ifBlank { "Untitled" }, conv.messages.size
                            )
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { downloadedFile = null }) { Text("Dismiss") }
            },
        )
    }
}
