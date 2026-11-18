package com.example.newsmonitor.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.newsmonitor.data.BaiduOcrConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream

/**
 * 抓取兜底：离屏 WebView 渲染页面。
 *
 * 1. 优先取渲染后的 DOM（能执行 JS，覆盖 SPA 站点）；
 * 2. DOM 文本不足时，逐屏截图并调用百度 OCR（覆盖纯图片/Canvas 正文）。
 *
 * WebView 的所有操作都在主线程执行，用完立即销毁。
 */
class WebViewCrawlerFallback(
    private val context: Context,
    private val ocrConfigProvider: suspend () -> BaiduOcrConfig?,
    private val log: suspend (level: String, message: String) -> Unit
) : CrawlerFallback {

    override suspend fun fetchDocument(url: String): Document? =
        withContext(Dispatchers.Main.immediate) {
            try {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { renderDocument(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("ERROR", "浏览器兜底失败: ${e.message?.take(120)}")
                null
            }
        }

    private suspend fun renderDocument(url: String): Document? {
        log("INFO", "启用浏览器渲染兜底: $url")
        val webView = try {
            createWebView()
        } catch (e: Exception) {
            log("ERROR", "创建 WebView 失败: ${e.message?.take(120)}")
            return null
        }

        try {
            configure(webView)
            val loaded = CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    loaded.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        loaded.complete(Unit)
                    }
                }
            }

            webView.loadUrl(url)
            val finished = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() }
            if (finished == null) {
                webView.stopLoading()
            }
            delay(RENDER_SETTLE_MS)

            val finalUrl = webView.url ?: url
            val html = evalJs(
                webView,
                "document.documentElement ? document.documentElement.outerHTML : ''"
            )

            if (!html.isNullOrBlank()) {
                val parsed = withContext(Dispatchers.IO) {
                    val document = Jsoup.parse(html, finalUrl)
                    document to ContentExtractor.extractContent(document)
                }
                val text = parsed.second
                if (!text.isNullOrBlank() && text.length >= MIN_USABLE_TEXT) {
                    log("INFO", "浏览器渲染成功，正文 ${text.length} 字: $url")
                    return parsed.first
                }
            }

            val innerText = evalJs(
                webView,
                "(document.body||document.documentElement).innerText || ''"
            )
            if (!innerText.isNullOrBlank() && innerText.length >= MIN_USABLE_TEXT) {
                log("INFO", "浏览器渲染成功，使用页面文本 ${innerText.length} 字: $url")
                return syntheticDocument(finalUrl, innerText)
            }

            val ocrConfig = ocrConfigProvider()
            if (ocrConfig == null) {
                log("WARN", "未配置百度 OCR，跳过截图识别: $url")
                return null
            }

            log("WARN", "DOM 文本不足，启用百度 OCR 截图识别: $url")
            val ocrText = captureOcr(webView, ocrConfig)
            if (ocrText.isNullOrBlank() || ocrText.length < MIN_OCR_TEXT) {
                return null
            }
            log("INFO", "OCR 识别成功，共 ${ocrText.length} 字: $url")
            return syntheticDocument(finalUrl, ocrText)
        } finally {
            destroyQuietly(webView)
        }
    }

    private fun createWebView(): WebView {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        return WebView(themed)
    }

    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = Http.USER_AGENT
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.setBackgroundColor(Color.WHITE)
        webView.isVerticalScrollBarEnabled = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                result?.confirm("")
                return true
            }
        }
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        webView.onResume()
        webView.resumeTimers()
    }

    private suspend fun captureOcr(webView: WebView, config: BaiduOcrConfig): String? {
        val cssHeight = evalJs(webView, "String(window.innerHeight || 600)")
            ?.toDoubleOrNull()?.toInt() ?: 600
        val step = (cssHeight * 0.9).toInt().coerceAtLeast(200)

        val heightText = evalJs(
            webView,
            "String(Math.max(" +
                "(document.body?document.body.scrollHeight:0)," +
                "(document.documentElement?document.documentElement.scrollHeight:0)," +
                "$step))"
        )
        val pageHeight = heightText?.toDoubleOrNull()?.toInt() ?: step
        val tiles = ((pageHeight + step - 1) / step).coerceIn(1, MAX_TILES)

        val builder = StringBuilder()
        for (tile in 0 until tiles) {
            evalJs(webView, "window.scrollTo(0, ${tile * step}); true")
            delay(SCROLL_SETTLE_MS)

            val bitmap = drawViewport(webView) ?: continue
            val blank = isBlankImage(bitmap)
            val jpeg = if (blank) null else bitmapToJpeg(bitmap)
            bitmap.recycle()
            if (jpeg == null) continue

            val text = BaiduOcrApi.recognize(config, jpeg) ?: continue
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            }
            delay(OCR_INTERVAL_MS)
            if (builder.length >= MAX_OCR_TEXT) break
        }
        return builder.toString().ifBlank { null }
    }

    private fun drawViewport(webView: WebView): Bitmap? {
        return try {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

            val bitmap = Bitmap.createBitmap(
                VIEWPORT_WIDTH,
                VIEWPORT_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun isBlankImage(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(0, 0)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != first) return false
                x += 40
            }
            y += 40
        }
        return true
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            val bytes = output.toByteArray()
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) null else bytes
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                webView.evaluateJavascript(script) { raw ->
                    val value = when {
                        raw == null || raw == "null" -> null
                        else -> runCatching {
                            JSONTokener(raw).nextValue() as? String
                        }.getOrNull()
                    }
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(value))
                    }
                }
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(null))
                }
            }
        }

    private fun syntheticDocument(url: String, text: String): Document {
        val document = Document.createShell(url)
        document.body().appendElement("article").text(text)
        document.title(text.lineSequence().firstOrNull()?.take(120).orEmpty())
        return document
    }

    private fun destroyQuietly(webView: WebView) {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        private const val VIEWPORT_WIDTH = 720
        private const val VIEWPORT_HEIGHT = 1600
        private const val MAX_TILES = 4
        private const val MAX_IMAGE_BYTES = 3_500_000
        private const val MAX_OCR_TEXT = 4000
        private const val MIN_USABLE_TEXT = 200
        private const val MIN_OCR_TEXT = 200
        private const val FETCH_TIMEOUT_MS = 50_000L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val RENDER_SETTLE_MS = 2000L
        private const val SCROLL_SETTLE_MS = 500L
        private const val OCR_INTERVAL_MS = 600L
    }
}
