package com.aus.notelikeus.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Android's Turnstile token (D30): [CaptchaActivity] shows the web app's challenge page in a
 * WebView with `?return=app&state=<s>`, and the page hands the token back by navigating to
 * `notelikeus-captcha://token?state=<s>&token=<t>`.
 */
class AndroidCaptchaTokenProvider(
    private val context: Context,
    private val pageUrl: String,
    private val timeout: Duration = 5.minutes,
) : CaptchaTokenProvider {

    private val oneAtATime = Mutex()

    override suspend fun obtainToken(): String? {
        if (pageUrl.isBlank()) return null
        return oneAtATime.withLock {
            val request = CaptchaActivity.Request(newCaptchaState(), CompletableDeferred())
            CaptchaActivity.pending = request
            try {
                context.startActivity(
                    Intent(context, CaptchaActivity::class.java)
                        .putExtra(CaptchaActivity.EXTRA_PAGE_URL, pageUrl)
                        .putExtra(
                            CaptchaActivity.EXTRA_URL,
                            captchaChallengeUrl(pageUrl, "return=app&state=${request.state}"),
                        )
                        // Started from the application context; same task affinity as the app.
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                withTimeoutOrNull(timeout) { request.result.await() }
                    ?: throw CaptchaUnavailableException("The security check timed out. Try again.")
            } finally {
                if (CaptchaActivity.pending === request) CaptchaActivity.pending = null
            }
        }
    }
}

/**
 * Hosts the challenge page. JavaScript runs only while the top frame is on the page's origin, the
 * top frame cannot leave that origin, only Turnstile may load in a subframe, and the only way out
 * with a token is the [CAPTCHA_RETURN_SCHEME] navigation carrying this request's state
 * ([CaptchaNavigationPolicy]). Closing the screen cancels the check.
 */
class CaptchaActivity : ComponentActivity() {

    internal class Request(val state: String, val result: CompletableDeferred<String>)

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = pending
        val url = intent.getStringExtra(EXTRA_URL)
        val pageUrl = intent.getStringExtra(EXTRA_PAGE_URL)
        // Recreated after process death, or opened by something other than the provider.
        val opensThisRequest = request != null && url != null && "state=${request.state}" in url
        if (!opensThisRequest || pageUrl == null) {
            finish()
            return
        }
        val policy = CaptchaNavigationPolicy(pageUrl)
        val view = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = policy.allowsJavaScript(url)
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                    when (val decision = policy.decide(req.url.toString(), req.isForMainFrame)) {
                        CaptchaNavigationPolicy.Decision.Allow -> false
                        CaptchaNavigationPolicy.Decision.Block -> true
                        is CaptchaNavigationPolicy.Decision.Result -> {
                            deliver(request, decision.rawQuery)
                            true
                        }
                    }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    view.settings.javaScriptEnabled = policy.allowsJavaScript(url)
                    super.onPageStarted(view, url, favicon)
                }
            }
        }
        webView = view
        setContentView(view)
        view.loadUrl(url)
    }

    private fun deliver(request: Request, rawQuery: String?) {
        val token = captchaTokenFromReturnQuery(rawQuery, request.state)
        if (token != null) {
            request.result.complete(token)
        } else {
            request.result.completeExceptionally(
                CaptchaUnavailableException("The security check didn't go through. Try again."),
            )
        }
        finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        if (isFinishing) {
            // Back pressed or the screen dismissed before a token came back.
            pending?.result?.completeExceptionally(CaptchaUnavailableException(CAPTCHA_CANCELLED_MESSAGE))
        }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_URL = "com.aus.notelikeus.captcha.URL"
        internal const val EXTRA_PAGE_URL = "com.aus.notelikeus.captcha.PAGE_URL"

        @Volatile
        internal var pending: Request? = null
    }
}
