package app.convarchive.ui.browser

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles WebView DownloadListener downloads.
 *
 * SECURITY: downloads are restricted to google.com hosts (the three official
 * sites and Google's login domains). The request reuses the WebView's cookies
 * via CookieManager so the user's own session applies — the app never reads,
 * stores, or transmits those cookies anywhere else. Files land in the app's
 * private cache; the user is then offered a one-tap import into the library.
 */
object DownloadHelper {

    /** Host allow-list for intercepted downloads. */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        return h == "google.com" || h.endsWith(".google.com")
    }

    fun isAllowedUrl(url: String): Boolean = try {
        val h = URL(url).host
        URL(url).protocol == "https" && isAllowedHost(h)
    } catch (e: Exception) {
        false
    }

    /**
     * File name from a DownloadListener callback, falling back to the URL.
     * Pure Kotlin (no android.webkit.URLUtil) so it stays unit-testable.
     */
    fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromDisposition = contentDisposition?.let { guessFromContentDisposition(it) }
        val fromUrl = try {
            URL(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } catch (e: Exception) {
            null
        }
        var name = (fromDisposition ?: fromUrl ?: "download-${System.currentTimeMillis()}")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(120)
            .ifBlank { "download-${System.currentTimeMillis()}" }
        if (!name.contains('.')) {
            val ext = when (mimeType?.lowercase()) {
                "application/json" -> ".json"
                "application/pdf" -> ".pdf"
                "text/markdown" -> ".md"
                "text/html" -> ".html"
                "text/plain" -> ".txt"
                else -> ""
            }
            name += ext
        }
        return name
    }

    private fun guessFromContentDisposition(header: String): String? {
        // RFC 6266 / RFC 5987: prefer filename*=UTF-8''..., then filename="...".
        val star = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
        if (!star.isNullOrBlank()) return star
        val quoted = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        if (!quoted.isNullOrBlank()) return quoted
        val bare = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()
        return bare?.takeIf { it.isNotBlank() }
    }

    /**
     * Downloads [url] with the WebView session cookies into the app cache and
     * returns the saved file. Throws on HTTP errors or disallowed hosts.
     * Must be called off the main thread.
     */
    suspend fun download(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): File = withContext(Dispatchers.IO) {
        require(isAllowedUrl(url)) { "download blocked: not a google.com URL" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                setRequestProperty("Cookie", cookie)
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("download failed: HTTP $code")
            }
            val name = fileNameFor(url, contentDisposition, mimeType)
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val out = File(dir, name)
            connection.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } finally {
            connection.disconnect()
        }
    }
}
