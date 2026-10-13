package app.convarchive.ui.browser

/**
 * Per-platform configuration for the in-app browsers.
 *
 * The WebView loads only the official site URL. Login happens on Google's own
 * pages inside the WebView; the app never reads password fields, cookies, or
 * tokens, and makes no network calls of its own except loading these pages
 * (plus same-site file downloads the page itself offers, see DownloadHelper).
 */
data class BrowserPlatform(
    val slug: String,
    val name: String,
    /** Start URL loaded in the WebView. */
    val url: String,
    /** Platform key passed to the extractor JS. */
    val extractorKey: String,
)

val BROWSER_PLATFORMS = listOf(
    BrowserPlatform(
        slug = "gemini",
        name = "Gemini",
        url = "https://gemini.google.com/",
        extractorKey = "gemini",
    ),
    BrowserPlatform(
        slug = "aistudio",
        name = "Google AI Studio",
        url = "https://aistudio.google.com/",
        extractorKey = "aistudio",
    ),
    BrowserPlatform(
        slug = "jules",
        name = "Google Jules",
        url = "https://jules.google.com/",
        extractorKey = "jules",
    ),
)

fun browserPlatformFor(slug: String): BrowserPlatform =
    BROWSER_PLATFORMS.firstOrNull { it.slug == slug } ?: BROWSER_PLATFORMS.first()

/**
 * Desktop Chrome user-agent, kept as a fallback UA mode (v1.2.0).
 *
 * v1.1 set this as the WebView UA by default as a workaround for Google's
 * embedded-WebView sign-in block. On-device testing (2026-10-13) showed the
 * block persisting ("Couldn't sign you in / This browser or app may not be
 * secure"): a spoofed desktop UA with inconsistent client hints is what
 * trips Google's bot detection now, so the default is the honest UA with
 * only `; wv` stripped (see UserAgentMode.STANDARD). This string remains
 * available as the DESKTOP fallback mode.
 */
const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
