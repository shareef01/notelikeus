package com.aus.notelikeus.data.remote

import com.aus.notelikeus.data.remote.CaptchaNavigationPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pieces Android and desktop share for getting a Turnstile token from the challenge page. */
class CaptchaTokenProviderTest {

    @Test
    fun stateIsUrlSafeLongEnoughForThePageAndFresh() {
        val state = newCaptchaState()
        assertTrue(Regex("^[A-Za-z0-9_-]{16,128}$").matches(state), state)
        assertNotEquals(state, newCaptchaState())
    }

    @Test
    fun challengeUrlAppendsToAnExistingQuery() {
        assertEquals("https://n.example/captcha?port=5&state=s", captchaChallengeUrl("https://n.example/captcha", "port=5&state=s"))
        assertEquals("https://n.example/captcha?v=1&state=s", captchaChallengeUrl("https://n.example/captcha?v=1", "state=s"))
    }

    @Test
    fun aReturnIsAcceptedOnlyWithTheExpectedState() {
        assertEquals("a.b&c", captchaTokenFromReturnQuery("state=S&token=a.b%26c", "S"))
        assertNull(captchaTokenFromReturnQuery("state=other&token=t", "S"))
        assertNull(captchaTokenFromReturnQuery("token=t", "S"))
        assertNull(captchaTokenFromReturnQuery("state=S", "S"))
        assertNull(captchaTokenFromReturnQuery("state=S&token=", "S"))
        assertNull(captchaTokenFromReturnQuery("state=S&state=S&token=t", "S"))
        assertNull(captchaTokenFromReturnQuery("state=S&token=t&token=u", "S"))
        assertNull(captchaTokenFromReturnQuery(null, "S"))
    }

    private val policy = CaptchaNavigationPolicy("https://notes.example/captcha.html")

    @Test
    fun theWebViewTopFrameStaysOnTheChallengePagesOrigin() {
        assertEquals(Decision.Allow, policy.decide("https://notes.example/captcha.html?x=1", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("https://evil.example/", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("http://notes.example/captcha.html", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("https://notes.example.evil.example/", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("https://challenges.cloudflare.com/x", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("intent://x#Intent;end", isMainFrame = true))
        assertEquals(Decision.Block, policy.decide("file:///data/data/x", isMainFrame = true))
    }

    @Test
    fun onlyTurnstileMayLoadInASubframe() {
        assertEquals(Decision.Allow, policy.decide("https://challenges.cloudflare.com/cdn-cgi/x", isMainFrame = false))
        assertEquals(Decision.Block, policy.decide("https://evil.example/", isMainFrame = false))
    }

    @Test
    fun theReturnSchemeIsTheResultOnlyFromTheTopFrame() {
        assertEquals(
            Decision.Result("state=S&token=t"),
            policy.decide("notelikeus-captcha://token?state=S&token=t", isMainFrame = true),
        )
        assertEquals(Decision.Block, policy.decide("notelikeus-captcha://token?state=S&token=t", isMainFrame = false))
    }

    @Test
    fun javaScriptRunsOnlyOnTheChallengePagesOrigin() {
        assertTrue(policy.allowsJavaScript("https://notes.example/captcha.html"))
        assertTrue(policy.allowsJavaScript("https://NOTES.example:443/other"))
        assertFalse(policy.allowsJavaScript("https://evil.example/"))
        assertFalse(policy.allowsJavaScript("about:blank"))
        assertFalse(policy.allowsJavaScript(null))
    }
}
