package com.aus.notelikeus.data.remote

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Gets a Cloudflare Turnstile token for `POST /auth/v1/otp` (audit S-02, D30).
 *
 * Neither platform has a Turnstile SDK, so both open the web app's challenge page
 * (`NOTELIKEUS_CAPTCHA_PAGE_URL`, `web/captcha.html`): Android in a WebView, desktop in the system
 * browser with a one-shot loopback listener. A build without that URL has no CAPTCHA, which is what
 * the local stack expects (`[auth.captcha]` is off in `supabase/config.toml`).
 */
fun interface CaptchaTokenProvider {
    /**
     * A fresh single-use token, or null when this build has no CAPTCHA configured.
     * Throws [CaptchaUnavailableException] when the check is cancelled, fails or times out.
     */
    suspend fun obtainToken(): String?
}

/** For builds and tests with no CAPTCHA. */
object NoCaptcha : CaptchaTokenProvider {
    override suspend fun obtainToken(): String? = null
}

/** The security check did not produce a token. [message] is fit to show the user. */
class CaptchaUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Android's return scheme; must match `APP_RETURN_SCHEME` in `web/src/lib/auth/captchaReturn.ts`. */
internal const val CAPTCHA_RETURN_SCHEME = "notelikeus-captcha"

internal const val CAPTCHA_CANCELLED_MESSAGE =
    "Sign-in needs a quick security check before we can email a code. Try again."

/** Random, URL-safe; the challenge page accepts 16 to 128 of `[A-Za-z0-9_-]`. */
internal fun newCaptchaState(): String {
    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** [pageUrl] with the challenge page's query appended. */
internal fun captchaChallengeUrl(pageUrl: String, query: String): String =
    pageUrl + (if ('?' in pageUrl) "&" else "?") + query

/**
 * The token from a return URL's raw query (`state=…&token=…`), or null unless its `state` is
 * exactly [expectedState] and a token is present. A page that is not the one this app opened
 * cannot know the state, so it cannot hand the app a token of its choosing.
 */
internal fun captchaTokenFromReturnQuery(rawQuery: String?, expectedState: String): String? {
    if (rawQuery.isNullOrEmpty()) return null
    val params = rawQuery.split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val key = URLDecoder.decode(pair.substring(0, eq), Charsets.UTF_8)
        val value = URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8)
        key to value
    }
    val states = params.filter { it.first == "state" }.map { it.second }
    val tokens = params.filter { it.first == "token" }.map { it.second }
    if (states.size != 1 || tokens.size != 1) return null
    if (states.single() != expectedState) return null
    return tokens.single().takeIf { it.isNotBlank() }
}

/**
 * What Android's challenge WebView may load. The top frame stays on the challenge page's origin,
 * Turnstile's own frame may load from Cloudflare, and the [CAPTCHA_RETURN_SCHEME] navigation is
 * the result. Everything else is blocked.
 */
internal class CaptchaNavigationPolicy(pageUrl: String) {
    private val pageOrigin: String? = origin(pageUrl)

    sealed interface Decision {
        data object Allow : Decision
        data object Block : Decision
        data class Result(val rawQuery: String?) : Decision
    }

    fun decide(url: String, isMainFrame: Boolean): Decision {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Decision.Block
        if (uri.scheme.equals(CAPTCHA_RETURN_SCHEME, ignoreCase = true)) {
            return if (isMainFrame) Decision.Result(uri.rawQuery) else Decision.Block
        }
        val target = origin(url) ?: return Decision.Block
        return when {
            target == pageOrigin -> Decision.Allow
            !isMainFrame && target == TURNSTILE_ORIGIN -> Decision.Allow
            else -> Decision.Block
        }
    }

    /** JavaScript runs only while the top frame is the challenge page's origin. */
    fun allowsJavaScript(url: String?): Boolean = url != null && origin(url) == pageOrigin

    private companion object {
        const val TURNSTILE_ORIGIN = "https://challenges.cloudflare.com"

        fun origin(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return "https://$host$port"
        }
    }
}
