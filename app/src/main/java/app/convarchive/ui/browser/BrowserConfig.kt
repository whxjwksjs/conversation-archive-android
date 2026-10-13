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
 * Desktop Chrome user-agent used by the in-app WebViews.
 *
 * Documented workaround: Google's login pages sometimes refuse or degrade
 * sign-in from the default embedded-WebView user agent. Presenting as desktop
 * Chrome makes the normal Google login flow work inside the app. This is not
 * guaranteed forever — if Google starts blocking it, the fallback is "Open in
 * external browser" + import (the v1.0 workflow, still supported).
 */
const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
