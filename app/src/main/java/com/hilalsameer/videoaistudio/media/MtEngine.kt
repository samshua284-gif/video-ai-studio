package com.hilalsameer.videoaistudio.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Languages the translation engine serves, UI code → NLLB FLORES. */
object MtLanguages {
    val codes: Map<String, String> = linkedMapOf(
        "ar" to "ara_Arab",
        "en" to "eng_Latn",
        "hi" to "hin_Deva",
        "ur" to "urd_Arab",
        "tr" to "tur_Latn"
    )

    fun flores(uiCode: String): String? = codes[uiCode]
}

/**
 * Offline machine translation (Milestone 5): NLLB-200-distilled-
 * 600M running in Transformers.js inside a hidden WebView — the
 * exact stack the owner's voice translator field-proved, model and
 * engine binaries hash-pinned at build time (fetchMtDeps). The
 * page and worker are served from the APK's own assets under a
 * fake https host with cross-origin-isolation headers, mirroring
 * the voice translator's serving pattern; nothing ever leaves the
 * device (the app holds no INTERNET permission at all).
 *
 * The worker translates sentence-by-sentence with a completeness
 * guard (see mt-worker.js): an empty unit result is retried once,
 * then the source unit passes through visibly. This class only
 * moves requests and results across the bridge.
 */
class MtEngine(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageReady: CompletableDeferred<Unit>? = null
    private var readyDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var modelReady = false

    @Volatile
    var lastError: String? = null
        private set

    /** File name currently being read by the model loader. */
    @Volatile
    var onLoadProgress: ((String) -> Unit)? = null

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(1)

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        val wv = WebView(appContext)
        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.databaseEnabled = true
        st.allowFileAccess = true
        st.allowFileAccessFromFileURLs = true
        st.allowUniversalAccessFromFileURLs = true
        st.allowContentAccess = true
        st.mediaPlaybackRequiresUserGesture = false
        wv.addJavascriptInterface(Bridge(), "MtBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url ?: return null
                if (url.host != HOST) return null
                val path = if (url.path.isNullOrEmpty() || url.path == "/") {
                    "/engines/mt.html"
                } else {
                    url.path!!
                }
                val headers = mapOf(
                    "Cross-Origin-Opener-Policy" to "same-origin",
                    "Cross-Origin-Embedder-Policy" to "credentialless",
                    "Cross-Origin-Resource-Policy" to "same-origin"
                )
                return try {
                    val input = appContext.assets.open("mt$path")
                    WebResourceResponse(mimeOf(path), null, 200, "OK", headers, input)
                } catch (e: Exception) {
                    WebResourceResponse(
                        "text/plain", "UTF-8", 404, "Not Found", headers,
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }
        }
        pageReady = CompletableDeferred()
        webView = wv
        wv.loadUrl("https://$HOST/engines/mt.html")
    }

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }

    /** Creates the WebView if needed and loads the NLLB model.
     *  Returns false (with [lastError] set) when loading fails. */
    suspend fun ensureLoaded(): Boolean {
        withContext(Dispatchers.Main) { ensureWebView() }
        pageReady?.await()
        if (modelReady) return true
        val d = CompletableDeferred<Boolean>().also { readyDeferred = it }
        post(JSONObject().put("type", "load").toString())
        val ok = kotlinx.coroutines.withTimeoutOrNull(LOAD_TIMEOUT_MS) { d.await() } ?: false
        return ok && modelReady
    }

    /** Translates one segment's text. The worker splits it into
     *  sentences and guards each one; the returned text is never
     *  silently empty for non-empty input. Throws on bridge or
     *  engine failure — the caller decides the job's fate. */
    suspend fun translate(text: String, srcFlores: String, tgtFlores: String): String {
        check(ensureLoaded()) { "MT model failed to load: ${lastError ?: "unknown"}" }
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<String>()
        pending[id] = d
        post(
            JSONObject()
                .put("type", "mt")
                .put("id", id)
                .put("text", text)
                .put("srcLang", srcFlores)
                .put("tgtLang", tgtFlores)
                .toString()
        )
        return try {
            withTimeout(SEGMENT_TIMEOUT_MS) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun post(json: String) {
        main.post {
            webView?.evaluateJavascript(
                "window.__mtPost(${JSONObject.quote(json)})", null
            )
        }
    }

    private fun handle(json: String) {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        when (o.optString("type")) {
            "page-ready" -> pageReady?.complete(Unit)
            "progress" -> {
                val f = o.optString("file")
                if (f.isNotEmpty()) onLoadProgress?.invoke(f)
            }
            "ready" -> {
                modelReady = true
                readyDeferred?.complete(true)
            }
            "load-error" -> {
                lastError = o.optString("message")
                readyDeferred?.complete(false)
            }
            "mt-done" -> {
                pending[o.optInt("id")]?.complete(o.optString("result"))
            }
            "mt-error" -> {
                val id = o.optInt("id")
                val msg = o.optString("message")
                val d = pending[id]
                if (d != null) {
                    d.completeExceptionally(IllegalStateException(msg))
                } else {
                    lastError = msg
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onMessage(json: String) = handle(json)
    }

    fun release() {
        main.post {
            webView?.destroy()
            webView = null
            modelReady = false
            pageReady = null
        }
    }

    companion object {
        private const val HOST = "mt.local"
        private const val LOAD_TIMEOUT_MS = 15 * 60 * 1000L
        private const val SEGMENT_TIMEOUT_MS = 20 * 60 * 1000L
    }
}
