package app.convarchive

import app.convarchive.ui.browser.DESKTOP_CHROME_UA
import app.convarchive.ui.browser.UserAgentMode
import app.convarchive.ui.browser.isSignInBlockPage
import app.convarchive.ui.browser.nextUserAgentMode
import app.convarchive.ui.browser.stripWebViewToken
import app.convarchive.ui.browser.userAgentForMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v1.2.0 sign-in-block fix:
 * - `; wv` token stripping (the community-standard fix),
 * - UA mode selection and cycling,
 * - Google "Couldn't sign you in" block-page detection heuristics.
 */
class UserAgentModeTest {

    private val realWebViewUa =
        "Mozilla/5.0 (Linux; Android 14; SM-S921B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val plainChromeUa =
        "Mozilla/5.0 (Linux; Android 14; SM-S921B Build/UP1A.231005.007) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Test
    fun `wv token is stripped from a real WebView UA`() {
        assertEquals(plainChromeUa, stripWebViewToken(realWebViewUa))
    }

    @Test
    fun `UA without the token is unchanged`() {
        assertEquals(plainChromeUa, stripWebViewToken(plainChromeUa))
        assertEquals(DESKTOP_CHROME_UA, stripWebViewToken(DESKTOP_CHROME_UA))
    }

    @Test
    fun `empty UA stays empty`() {
        assertEquals("", stripWebViewToken(""))
    }

    @Test
    fun `STANDARD mode strips only the wv token`() {
        assertEquals(plainChromeUa, userAgentForMode(UserAgentMode.STANDARD, realWebViewUa))
    }

    @Test
    fun `STANDARD mode keeps the genuine Chrome version and device`() {
        val ua = userAgentForMode(UserAgentMode.STANDARD, realWebViewUa)
        assertTrue(ua.contains("Chrome/126.0.0.0"))
        assertTrue(ua.contains("SM-S921B"))
        assertFalse(ua.contains("wv"))
    }

    @Test
    fun `DESKTOP mode returns the desktop Chrome string`() {
        assertEquals(DESKTOP_CHROME_UA, userAgentForMode(UserAgentMode.DESKTOP, realWebViewUa))
    }

    @Test
    fun `SYSTEM mode returns the UA untouched`() {
        assertEquals(realWebViewUa, userAgentForMode(UserAgentMode.SYSTEM, realWebViewUa))
    }

    @Test
    fun `mode cycling follows STANDARD DESKTOP SYSTEM order`() {
        assertEquals(UserAgentMode.DESKTOP, nextUserAgentMode(UserAgentMode.STANDARD))
        assertEquals(UserAgentMode.SYSTEM, nextUserAgentMode(UserAgentMode.DESKTOP))
        assertEquals(UserAgentMode.STANDARD, nextUserAgentMode(UserAgentMode.SYSTEM))
    }

    @Test
    fun `block page title from the on-device screenshot is detected`() {
        assertTrue(
            isSignInBlockPage(
                "Couldn't sign you in",
                "https://accounts.google.com/signin/rejected",
            )
        )
    }

    @Test
    fun `detection is case-insensitive`() {
        assertTrue(isSignInBlockPage("COULDN'T SIGN YOU IN", "https://accounts.google.com/"))
    }

    @Test
    fun `not secure headline variant is detected`() {
        assertTrue(
            isSignInBlockPage(
                "This browser or app may not be secure",
                "https://accounts.google.com/",
            )
        )
    }

    @Test
    fun `normal page titles are not flagged`() {
        assertFalse(isSignInBlockPage("Gemini", "https://gemini.google.com/"))
        assertFalse(isSignInBlockPage("Google AI Studio", "https://aistudio.google.com/"))
        assertFalse(
            isSignInBlockPage("Sign in - Google Accounts", "https://accounts.google.com/")
        )
    }

    @Test
    fun `null title is not flagged`() {
        assertFalse(isSignInBlockPage(null, "https://accounts.google.com/"))
    }

    @Test
    fun `accounts error page with error in title is flagged`() {
        assertTrue(
            isSignInBlockPage(
                "Error 400",
                "https://accounts.google.com/o/oauth2/error",
            )
        )
    }

    @Test
    fun `error in title on a non-Google page is not flagged`() {
        assertFalse(isSignInBlockPage("Error 400", "https://example.com/"))
    }
}
