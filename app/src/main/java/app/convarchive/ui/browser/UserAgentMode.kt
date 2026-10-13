package app.convarchive.ui.browser

/**
 * User-agent mode for the in-app WebViews (v1.2.0).
 *
 * Background: Google detects embedded WebViews primarily via the `; wv`
 * token in the default user-agent string, and refuses sign-in with
 * "Couldn't sign you in / This browser or app may not be secure". The
 * community-standard fix (used by multiple shipped apps) is to keep the
 * genuine mobile-Chrome UA and strip only the WebView marker. The v1.1
 * approach (full desktop-Chrome UA spoof) proved counterproductive:
 * mismatched/spoofed UAs with inconsistent client hints are what trips
 * Google's bot detection now.
 *
 * Modes:
 * - STANDARD (default): genuine WebView UA with only `; wv` stripped.
 * - DESKTOP: the old v1.1 desktop-Chrome string, kept as a fallback.
 * - SYSTEM: the untouched WebView default UA (contains `; wv`).
 */
enum class UserAgentMode {
    STANDARD,
    DESKTOP,
    SYSTEM,
}

/** Cycle order used by the "Try another mode" block-page dialog action. */
fun nextUserAgentMode(mode: UserAgentMode): UserAgentMode = when (mode) {
    UserAgentMode.STANDARD -> UserAgentMode.DESKTOP
    UserAgentMode.DESKTOP -> UserAgentMode.SYSTEM
    UserAgentMode.SYSTEM -> UserAgentMode.STANDARD
}

/** Short UI label for the Settings radio list. */
fun userAgentModeLabel(mode: UserAgentMode): String = when (mode) {
    UserAgentMode.STANDARD -> "Standard (recommended)"
    UserAgentMode.DESKTOP -> "Desktop"
    UserAgentMode.SYSTEM -> "System default"
}

/** One-line explanation for the Settings screen. */
fun userAgentModeDescription(mode: UserAgentMode): String = when (mode) {
    UserAgentMode.STANDARD ->
        "Your real browser identity with only the WebView marker removed. " +
            "This is what Google currently accepts."
    UserAgentMode.DESKTOP ->
        "Pretends to be desktop Chrome. Older workaround; can look " +
            "suspicious to Google's bot checks now."
    UserAgentMode.SYSTEM ->
        "The untouched WebView user agent. Google usually refuses " +
            "sign-in from this."
}

/**
 * Returns the UA string to set on the WebView for [mode].
 *
 * @param systemDefaultUa the WebView's own default UA (read BEFORE any
 * override is applied). Used as-is for SYSTEM, and with `; wv` stripped
 * for STANDARD.
 */
fun userAgentForMode(mode: UserAgentMode, systemDefaultUa: String): String = when (mode) {
    UserAgentMode.STANDARD -> stripWebViewToken(systemDefaultUa)
    UserAgentMode.DESKTOP -> DESKTOP_CHROME_UA
    UserAgentMode.SYSTEM -> systemDefaultUa
}

/**
 * Strips the WebView marker token (`; wv`) from a user-agent string.
 * A UA without the token is returned unchanged.
 */
fun stripWebViewToken(ua: String): String = ua.replace("; wv", "")

/**
 * Heuristic detection of Google's "Couldn't sign you in" block page.
 *
 * The failing page (accounts.google.com) exposes the refusal in its title:
 * "Couldn't sign you in" (seen on-device 2026-10-13). We match
 * case-insensitively on that phrase, plus the body headline text
 * "may not be secure" in case a future variant moves it into the title.
 * [title] is WebView.getTitle() from onPageFinished; [url] is the page URL.
 */
fun isSignInBlockPage(title: String?, url: String?): Boolean {
    val t = title?.lowercase() ?: return false
    if ("couldn't sign you in" in t || "could not sign you in" in t) return true
    if ("may not be secure" in t) return true
    // Extra guard: an accounts.google.com page whose title mentions an
    // error is very likely the same block under a different wording.
    if (url != null && "accounts.google.com" in url && "error" in t) return true
    return false
}
